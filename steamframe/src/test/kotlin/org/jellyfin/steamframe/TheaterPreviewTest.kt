package org.jellyfin.steamframe

import org.jellyfin.steamframe.vr.RoomState
import org.jellyfin.steamframe.vr.TheaterShader
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
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.Platform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test

/**
 * Renders the VR theater environment offscreen (Linux with an X display, e.g. under xvfb-run with Mesa) and writes
 * preview images to build/snapshots. This also proves the shaders compile on Mesa, the driver family of the Steam
 * Frame. The video itself is a separate OpenXR layer and therefore not part of these images.
 */
class TheaterPreviewTest {
	@Test
	fun renderTheaterPreviews() {
		assumeTrue(Platform.get() == Platform.LINUX && System.getenv("DISPLAY") != null, "Needs Linux with an X display")

		glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
		check(glfwInit()) { "GLFW init failed" }
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
		val window = glfwCreateWindow(16, 16, "preview", NULL, NULL)
		check(window != NULL) { "No OpenGL context" }
		glfwMakeContextCurrent(window)
		GL.createCapabilities()
		println("Rendering theater previews on ${glGetString(GL_RENDERER)}")

		try {
			val shader = TheaterShader()
			shader.captureScreen(syntheticMovieFrame(), 0, 0, 320, 180)

			val width = 1920
			val height = 1080
			val target = framebuffer(width, height)

			// name to (house lights, screen glow, look yaw in degrees)
			val scenes = mapOf(
				"theater-house-lights" to Triple(1f, 0f, 30.0),
				"theater-movie-playing" to Triple(0f, 1f, 30.0),
				"theater-front" to Triple(0.25f, 1f, 0.0),
			)
			for ((name, scene) in scenes) {
				val lights = scene.first to scene.second
				val room = RoomState().apply {
					screenWidth = 24f
					screenHeight = 13.5f
					distance = 15f
					houseLights = lights.first
					screenGlow = lights.second
				}
				// Look 30 degrees to the left and slightly down, to see the screen, side wall and floor
				val yaw = Math.toRadians(scene.third).toFloat()
				val pitch = Math.toRadians(-8.0).toFloat()
				val qYaw = floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2))
				val qPitch = floatArrayOf(sin(pitch / 2), 0f, 0f, cos(pitch / 2))
				val q = multiply(qYaw, qPitch)
				val tanH = tan(Math.toRadians(50.0)).toFloat()
				val tanV = tanH * height / width

				glBindFramebuffer(GL_FRAMEBUFFER, target)
				shader.beginFrame(room)
				shader.drawEye(room, 0f, 0f, 0f, q[0], q[1], q[2], q[3], -tanH, tanH, -tanV, tanV, width, height, outputSrgb = true)
				shader.endFrame()
				glBindFramebuffer(GL_FRAMEBUFFER, target)
				writePng(File("build/snapshots/$name.png"), width, height)
				glBindFramebuffer(GL_FRAMEBUFFER, 0)
			}
			shader.close()
		} finally {
			glfwDestroyWindow(window)
			glfwTerminate()
		}
	}

	/** A warm sunset on the left fading to a cool blue on the right, like a typical film frame. */
	private fun syntheticMovieFrame(): Int {
		val w = 320
		val h = 180
		val pixels = BufferUtils.createByteBuffer(w * h * 4)
		for (y in 0 until h) for (x in 0 until w) {
			val t = x / (w - 1f)
			pixels.put((255 * (1 - t) + 40 * t).toInt().toByte())
			pixels.put((140 * (1 - t) + 110 * t).toInt().toByte())
			pixels.put((40 * (1 - t) + 230 * t).toInt().toByte())
			pixels.put(255.toByte())
		}
		pixels.flip()
		val texture = glGenTextures()
		glBindTexture(GL_TEXTURE_2D, texture)
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
		glBindTexture(GL_TEXTURE_2D, 0)
		val fbo = glGenFramebuffers()
		glBindFramebuffer(GL_FRAMEBUFFER, fbo)
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		return fbo
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

	private fun writePng(file: File, width: Int, height: Int) {
		val pixels = BufferUtils.createByteBuffer(width * height * 4)
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		for (y in 0 until height) for (x in 0 until width) {
			val i = ((height - 1 - y) * width + x) * 4
			val rgb = (pixels[i].toInt() and 0xff shl 16) or (pixels[i + 1].toInt() and 0xff shl 8) or (pixels[i + 2].toInt() and 0xff)
			image.setRGB(x, y, rgb)
		}
		file.parentFile.mkdirs()
		ImageIO.write(image, "png", file)
	}

	private fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
		a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
		a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
		a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
		a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
	)
}
