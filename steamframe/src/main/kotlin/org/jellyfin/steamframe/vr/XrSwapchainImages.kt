package org.jellyfin.steamframe.vr

import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.openxr.XR10.XR_INFINITE_DURATION
import org.lwjgl.openxr.XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT
import org.lwjgl.openxr.XR10.XR_SWAPCHAIN_USAGE_SAMPLED_BIT
import org.lwjgl.openxr.XR10.XR_SWAPCHAIN_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.openxr.XR10.xrAcquireSwapchainImage
import org.lwjgl.openxr.XR10.xrCreateSwapchain
import org.lwjgl.openxr.XR10.xrDestroySwapchain
import org.lwjgl.openxr.XR10.xrEnumerateSwapchainFormats
import org.lwjgl.openxr.XR10.xrEnumerateSwapchainImages
import org.lwjgl.openxr.XR10.xrReleaseSwapchainImage
import org.lwjgl.openxr.XR10.xrWaitSwapchainImage
import org.lwjgl.openxr.XrSession
import org.lwjgl.openxr.XrSwapchain
import org.lwjgl.openxr.XrSwapchainCreateInfo
import org.lwjgl.openxr.XrSwapchainImageAcquireInfo
import org.lwjgl.openxr.XrSwapchainImageBaseHeader
import org.lwjgl.openxr.XrSwapchainImageOpenGLKHR
import org.lwjgl.openxr.XrSwapchainImageReleaseInfo
import org.lwjgl.openxr.XrSwapchainImageWaitInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.slf4j.LoggerFactory

/** An OpenXR swapchain with an OpenGL framebuffer per image. Must be used on the OpenGL thread. */
class XrSwapchainImages private constructor(
	val swapchain: XrSwapchain,
	val width: Int,
	val height: Int,
	val format: Long,
	private val framebuffers: IntArray,
) : AutoCloseable {
	/** True once at least one image was released, before that the swapchain may not be used in a layer. */
	var hasContent = false
		private set

	val isSrgb: Boolean get() = format == GL_SRGB8_ALPHA8

	/** Acquire the next image, run [draw] with its framebuffer bound and release it again. */
	inline fun render(stack: MemoryStack, draw: (framebuffer: Int) -> Unit) {
		val fbo = acquire(stack)
		try {
			draw(fbo)
		} finally {
			release(stack)
		}
	}

	fun acquire(stack: MemoryStack): Int {
		val index = stack.mallocInt(1)
		xrCheck(xrAcquireSwapchainImage(swapchain, XrSwapchainImageAcquireInfo.calloc(stack).`type$Default`(), index), "xrAcquireSwapchainImage")
		xrCheck(
			xrWaitSwapchainImage(swapchain, XrSwapchainImageWaitInfo.calloc(stack).`type$Default`().timeout(XR_INFINITE_DURATION)),
			"xrWaitSwapchainImage",
		)
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[index[0]])
		return framebuffers[index[0]]
	}

	fun release(stack: MemoryStack) {
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		xrCheck(xrReleaseSwapchainImage(swapchain, XrSwapchainImageReleaseInfo.calloc(stack).`type$Default`()), "xrReleaseSwapchainImage")
		hasContent = true
	}

	override fun close() {
		glDeleteFramebuffers(framebuffers)
		xrDestroySwapchain(swapchain)
	}

	companion object {
		private val logger = LoggerFactory.getLogger(XrSwapchainImages::class.java)

		const val GL_RGBA8 = 0x8058L
		const val GL_SRGB8_ALPHA8 = 0x8C43L

		fun create(session: XrSession, width: Int, height: Int, label: String): XrSwapchainImages = stackPush().use { stack ->
			val count = stack.mallocInt(1)
			xrCheck(xrEnumerateSwapchainFormats(session, count, null), "xrEnumerateSwapchainFormats")
			val formats = stack.mallocLong(count[0])
			xrCheck(xrEnumerateSwapchainFormats(session, count, formats), "xrEnumerateSwapchainFormats")
			val available = (0 until count[0]).map { formats[it] }
			// Video is gamma encoded, an sRGB swapchain makes the compositor decode it the way the display expects
			val format = listOf(GL_SRGB8_ALPHA8, GL_RGBA8).firstOrNull { it in available }
				?: throw XrException("No supported swapchain format in $available")

			val ptr = stack.mallocPointer(1)
			xrCheck(
				xrCreateSwapchain(
					session,
					XrSwapchainCreateInfo.calloc(stack)
						.`type$Default`()
						.usageFlags((XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT or XR_SWAPCHAIN_USAGE_SAMPLED_BIT or XR_SWAPCHAIN_USAGE_TRANSFER_SRC_BIT).toLong())
						.format(format)
						.sampleCount(1)
						.width(width)
						.height(height)
						.faceCount(1)
						.arraySize(1)
						.mipCount(1),
					ptr,
				),
				"xrCreateSwapchain",
			)
			val swapchain = XrSwapchain(ptr[0], session)

			xrCheck(xrEnumerateSwapchainImages(swapchain, count, null), "xrEnumerateSwapchainImages")
			val images = XrSwapchainImageOpenGLKHR.calloc(count[0], stack)
			images.forEach { it.`type$Default`() }
			xrCheck(xrEnumerateSwapchainImages(swapchain, count, XrSwapchainImageBaseHeader.create(images)), "xrEnumerateSwapchainImages")

			val framebuffers = IntArray(count[0]) { index ->
				val fbo = glGenFramebuffers()
				glBindFramebuffer(GL_FRAMEBUFFER, fbo)
				glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images[index].image(), 0)
				val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
				if (status != GL_FRAMEBUFFER_COMPLETE) logger.warn("$label framebuffer $index incomplete: $status")
				fbo
			}
			glBindFramebuffer(GL_FRAMEBUFFER, 0)

			logger.info("Created $label swapchain ${width}x$height, format 0x${format.toString(16)}, ${framebuffers.size} images")
			XrSwapchainImages(swapchain, width, height, format, framebuffers)
		}
	}
}

fun xrCheck(result: Int, what: String) {
	if (result < 0) throw XrException("$what failed with $result")
}
