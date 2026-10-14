package app.fellyjin.vr

import app.fellyjin.mpv.MpvPlayer
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API
import org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetProcAddress
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.glGetFloat
import org.lwjgl.opengl.GL11.glTexParameterf
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glFlush
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBlitFramebuffer
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.GL_TIMEOUT_IGNORED
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL32.glWaitSync
import org.lwjgl.system.MemoryUtil.NULL
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Renders mpv on its own thread with its own OpenGL context, shared with the OpenXR context.
 *
 * Rendering a 4K frame (texture upload, chroma scaling, tone mapping) can take longer than a 144 Hz display frame
 * (6.9 ms). On the frame loop thread that made the whole headset stutter. Here mpv renders into a ring of three
 * textures at its own pace, and the frame loop only copies the newest finished frame into the swapchain, which is a
 * GPU blit that takes a fraction of a millisecond. GPU fences keep both sides from touching a texture that is in use.
 *
 * Create on the frame loop thread (which owns [sharedWindow]'s context), then call [copyLatest] from that thread.
 */
class VideoRenderThread(
	private val player: MpvPlayer,
	sharedWindow: Long,
	private val flipY: Boolean,
	/** The frame loop context is EGL: a shared context must be created with the same API. */
	egl: Boolean = false,
	/**
	 * The frame loop samples the frames directly ([acquireLatestForSampling]) instead of copying them: buffers are
	 * sRGB with a mip chain that this thread generates, so no copy and no mipmapping happens on the frame loop.
	 */
	private val sampled: Boolean = false,
) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(VideoRenderThread::class.java)

	private class Buffer {
		var texture = 0
		var framebuffer = 0
		var width = 0
		var height = 0

		/** Signaled when mpv finished rendering into this buffer. */
		var renderedFence = NULL

		/** Signaled when the frame loop finished copying from this buffer. */
		var copiedFence = NULL
	}

	private val window: Long
	private val egl: Boolean
	private val buffers = Array(3) { Buffer() }
	private val lock = Any()
	private var published = -1
	private var reading = -1
	private var publishedFrame = 0L
	private var copiedFrame = 0L

	@Volatile
	private var running = true

	@Volatile
	private var targetWidth = 0

	@Volatile
	private var targetHeight = 0

	private val wake = Semaphore(0)
	private val started = CountDownLatch(1)

	@Volatile
	private var startError: Throwable? = null
	private val thread: Thread

	// Frame loop side: a framebuffer in the frame loop context to read the shared textures through
	private val readFramebuffer = glGenFramebuffers()

	init {
		glfwDefaultWindowHints()
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
		if (egl) glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API)
		this.egl = egl
		window = glfwCreateWindow(16, 16, "Fellyjin video", NULL, sharedWindow)
		if (window == NULL) throw XrException("Unable to create the shared video OpenGL context")

		thread = Thread(::run, "mpv-render").apply {
			isDaemon = true
			priority = Thread.MAX_PRIORITY - 1
			start()
		}
		if (!started.await(10, TimeUnit.SECONDS)) {
			close()
			throw XrException("The video render thread did not start in time")
		}
		startError?.let { close(); throw it }
	}

	/** Size mpv should render at, normally the swapchain size. Called from the frame loop on video reconfig. */
	fun setTargetSize(width: Int, height: Int) {
		targetWidth = width
		targetHeight = height
		wake.release()
	}

	val hasNewFrame: Boolean get() = synchronized(lock) { publishedFrame != copiedFrame && published >= 0 }

	/** A finished frame the frame loop may sample until it acquires the next one. */
	class SampledFrame(val texture: Int, val width: Int, val height: Int)

	/**
	 * Switch to the newest frame for sampling (frame loop thread, [sampled] mode). The previous frame is handed back to
	 * mpv after the GPU finished the commands issued so far (which include every draw that sampled it). [onNew] can
	 * read the new frame through the given framebuffer (bound as `GL_READ_FRAMEBUFFER`), e.g. to light the theater.
	 */
	fun acquireLatestForSampling(onNew: (framebuffer: Int, width: Int, height: Int) -> Unit = { _, _, _ -> }): SampledFrame? {
		val index: Int
		synchronized(lock) {
			if (published < 0 || publishedFrame == copiedFrame) return null
			val previous = reading
			index = published
			if (previous >= 0 && previous != index) {
				val done = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
				buffers[previous].copiedFence.takeIf { it != NULL }?.let(::glDeleteSync)
				buffers[previous].copiedFence = done
			}
			reading = index
			copiedFrame = publishedFrame
		}
		glFlush()
		val buffer = buffers[index]
		// Wait on the GPU (not the CPU) until mpv's commands and the mipmaps for this frame are done
		if (buffer.renderedFence != NULL) glWaitSync(buffer.renderedFence, 0, GL_TIMEOUT_IGNORED)
		glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
		glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, buffer.texture, 0)
		onNew(readFramebuffer, buffer.width, buffer.height)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		return SampledFrame(buffer.texture, buffer.width, buffer.height)
	}

	/**
	 * Copy the newest frame into [targetFramebuffer] (frame loop thread). [onCopied] can read from the source frame,
	 * bound as `GL_READ_FRAMEBUFFER` through the given framebuffer name, before it is handed back to mpv.
	 */
	fun copyLatest(targetFramebuffer: Int, width: Int, height: Int, onCopied: (framebuffer: Int, width: Int, height: Int) -> Unit = { _, _, _ -> }): Boolean {
		val index: Int
		val frame: Long
		synchronized(lock) {
			if (published < 0 || publishedFrame == copiedFrame) return false
			index = published
			frame = publishedFrame
			reading = index
		}
		val buffer = buffers[index]
		try {
			// Wait on the GPU (not the CPU) until mpv's commands for this frame are done
			if (buffer.renderedFence != NULL) glWaitSync(buffer.renderedFence, 0, GL_TIMEOUT_IGNORED)

			glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
			glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, buffer.texture, 0)
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
			val filter = if (buffer.width == width && buffer.height == height) GL_NEAREST else GL_LINEAR
			glBlitFramebuffer(0, 0, buffer.width, buffer.height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, filter)
			onCopied(readFramebuffer, buffer.width, buffer.height)

			val copied = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
			glFlush()
			synchronized(lock) {
				if (buffer.copiedFence != NULL) glDeleteSync(buffer.copiedFence)
				buffer.copiedFence = copied
				copiedFrame = frame
			}
		} finally {
			glBindFramebuffer(GL_FRAMEBUFFER, 0)
			synchronized(lock) { reading = -1 }
		}
		return true
	}

	private fun run() {
		try {
			glfwMakeContextCurrent(window)
			GL.createCapabilities()
			player.createGlRenderContext(
				procAddress = { glfwGetProcAddress(it) },
				// Only used by mpv for VA-API interop with GLX, not needed with EGL (and copy-back hwdec)
				x11Display = if (egl) null else glfwGetX11Display(),
				onUpdate = { wake.release() },
			)
		} catch (err: Throwable) {
			startError = err
			started.countDown()
			return
		}
		started.countDown()

		try {
			while (running) {
				wake.tryAcquire(20, TimeUnit.MILLISECONDS)
				if (!running) break
				if (!player.renderUpdateAvailable()) continue
				val width = targetWidth
				val height = targetHeight
				if (width <= 0 || height <= 0) continue
				renderFrame(width, height)
			}
		} catch (err: Throwable) {
			logger.error("Video render thread failed", err)
		} finally {
			player.freeRenderContext()
			buffers.forEach { buffer ->
				if (buffer.framebuffer != 0) glDeleteFramebuffers(buffer.framebuffer)
				if (buffer.texture != 0) glDeleteTextures(buffer.texture)
				if (buffer.renderedFence != NULL) glDeleteSync(buffer.renderedFence)
				if (buffer.copiedFence != NULL) glDeleteSync(buffer.copiedFence)
			}
			glfwMakeContextCurrent(NULL)
		}
	}

	private fun renderFrame(width: Int, height: Int) {
		// Pick a buffer that is neither the newest frame (about to be copied) nor being copied right now
		val index = synchronized(lock) { (0 until buffers.size).first { it != published && it != reading } }
		val buffer = buffers[index]

		// Don't overwrite the buffer before the frame loop's last copy from it has finished on the GPU
		val copied = synchronized(lock) { buffer.copiedFence.also { buffer.copiedFence = NULL } }
		if (copied != NULL) {
			glWaitSync(copied, 0, GL_TIMEOUT_IGNORED)
			glDeleteSync(copied)
		}

		if (buffer.width != width || buffer.height != height) allocate(buffer, width, height)

		player.render(buffer.framebuffer, width, height, flip = flipY, blockForTargetTime = true)
		if (sampled) {
			// Mipmaps here, on mpv's thread: on the frame loop a 4K mip chain cost a missed refresh now and then
			glBindTexture(GL_TEXTURE_2D, buffer.texture)
			glGenerateMipmap(GL_TEXTURE_2D)
			glBindTexture(GL_TEXTURE_2D, 0)
		}

		val rendered = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		glFlush()
		synchronized(lock) {
			if (buffer.renderedFence != NULL) glDeleteSync(buffer.renderedFence)
			buffer.renderedFence = rendered
			published = index
			publishedFrame++
		}
		player.reportSwap()
	}

	private fun allocate(buffer: Buffer, width: Int, height: Int) {
		if (buffer.texture == 0) buffer.texture = glGenTextures()
		glBindTexture(GL_TEXTURE_2D, buffer.texture)
		if (sampled) {
			// sRGB storage: sampling and mipmaps in linear light. mpv writes encoded values, stored unchanged.
			glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
			glGenerateMipmap(GL_TEXTURE_2D)
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
			if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
				glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY).coerceIn(1f, 16f))
			}
		} else {
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
		}
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
		glBindTexture(GL_TEXTURE_2D, 0)
		if (buffer.framebuffer == 0) buffer.framebuffer = glGenFramebuffers()
		glBindFramebuffer(GL_FRAMEBUFFER, buffer.framebuffer)
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, buffer.texture, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		buffer.width = width
		buffer.height = height
		logger.info("Video buffer ${width}x$height")
	}

	private companion object {
		const val GL_SRGB8_ALPHA8 = 0x8C43
		const val GL_TEXTURE_MAX_ANISOTROPY = 0x84FE
		const val GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF
	}

	/** Stop the thread (it frees mpv's render context on its own context) and release the frame loop side. */
	override fun close() {
		running = false
		wake.release()
		thread.join(5_000)
		glDeleteFramebuffers(readFramebuffer)
		if (thread.isAlive) {
			// Still inside mpv (a stalled decoder): destroying its context now would crash. Leak it instead, and
			// MpvPlayer.close() leaves that mpv instance alone as long as its render context exists.
			logger.error("Video render thread did not stop, leaving its OpenGL context alive")
			return
		}
		glfwDestroyWindow(window)
	}
}
