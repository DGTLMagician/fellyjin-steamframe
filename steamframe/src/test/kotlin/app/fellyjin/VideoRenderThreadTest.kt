package app.fellyjin

import app.fellyjin.mpv.LibMpv
import app.fellyjin.mpv.MpvEvent
import app.fellyjin.mpv.MpvPlayer
import app.fellyjin.vr.VideoRenderThread
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwInitHint
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.Platform
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Plays a generated video through mpv's OpenGL render API on [VideoRenderThread] and copies frames on this thread,
 * exactly like the VR frame loop does. Needs Linux with an X display (xvfb-run with Mesa) and libmpv.
 */
class VideoRenderThreadTest {
	@Test
	fun rendersFramesOnSharedContext() {
		assumeTrue(Platform.get() == Platform.LINUX && System.getenv("DISPLAY") != null, "Needs Linux with an X display")
		assumeTrue(LibMpv.isAvailable, "libmpv not installed")

		glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
		check(glfwInit())
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
		val window = glfwCreateWindow(16, 16, "frame loop", NULL, NULL)
		check(window != NULL)
		glfwMakeContextCurrent(window)
		GL.createCapabilities()

		val width = 320
		val height = 240
		val target = framebuffer(width, height)
		val player = MpvPlayer(mapOf("vo" to "libmpv", "ao" to "null", "idle" to "yes", "terminal" to "no", "hwdec" to "no"))
		val renderThread = VideoRenderThread(player, window, flipY = true)
		var copies = 0
		try {
			player.load("av://lavfi:testsrc=duration=3:size=${width}x$height:rate=30", start = null, title = "test")
			val start = TimeSource.Monotonic.markNow()
			var ended = false
			while (!ended && start.elapsedNow() < 20.seconds) {
				while (true) {
					when (val event = player.pollEvent(0.0) ?: break) {
						MpvEvent.VideoReconfig -> player.videoSize?.let { (w, h) -> renderThread.setTargetSize(w, h) }
						is MpvEvent.EndFile -> ended = true
						else -> Unit
					}
				}
				if (renderThread.hasNewFrame && renderThread.copyLatest(target, width, height)) copies++
				Thread.sleep(7) // ~144 Hz frame loop
			}

			glFinish()
			val pixels = BufferUtils.createByteBuffer(width * height * 4)
			glBindFramebuffer(GL_FRAMEBUFFER, target)
			glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
			val distinctColors = (0 until width * height).map { pixels.getInt(it * 4) }.toSet().size

			println("Copied $copies frames, $distinctColors distinct colors in the last frame")
			// 3 seconds at 30 fps: most frames should arrive, and the test pattern is colorful
			assertTrue(copies >= 60, "Only $copies frames were copied")
			assertTrue(distinctColors > 20, "The copied frame looks empty ($distinctColors colors)")
		} finally {
			renderThread.close()
			player.close()
			glfwDestroyWindow(window)
			glfwTerminate()
		}
	}

	private fun framebuffer(width: Int, height: Int): Int {
		val texture = glGenTextures()
		glBindTexture(GL_TEXTURE_2D, texture)
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
		glBindTexture(GL_TEXTURE_2D, 0)
		val fbo = glGenFramebuffers()
		glBindFramebuffer(GL_FRAMEBUFFER, fbo)
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		return fbo
	}
}
