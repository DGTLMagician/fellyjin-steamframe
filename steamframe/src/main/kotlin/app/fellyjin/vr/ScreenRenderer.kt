package app.fellyjin.vr

import app.fellyjin.appEnv
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGetFloat
import org.lwjgl.opengl.GL11.glScissor
import org.lwjgl.opengl.GL11.GL_CULL_FACE
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameterf
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUniform1f
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUniform3f
import org.lwjgl.opengl.GL20.glUniform4f
import org.lwjgl.opengl.GL20.glUniformMatrix3fv
import org.lwjgl.opengl.GL20.glUseProgram
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.openxr.XR10.XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT
import org.lwjgl.openxr.XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO
import org.lwjgl.openxr.XR10.xrEnumerateViewConfigurationViews
import org.lwjgl.openxr.XrCompositionLayerProjection
import org.lwjgl.openxr.XrCompositionLayerProjectionView
import org.lwjgl.openxr.XrInstance
import org.lwjgl.openxr.XrSession
import org.lwjgl.openxr.XrSpace
import org.lwjgl.openxr.XrView
import org.lwjgl.openxr.XrViewConfigurationView
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the curved (or flat) cinema screen as a projection layer, for runtimes without
 * XR_KHR_composition_layer_cylinder (SteamVR on the Steam Frame).
 *
 * Each eye is one full screen pass that intersects the view ray with the screen cylinder and samples the video, so
 * the picture really wraps around you. The video is copied into a mipmapped, anisotropically filtered texture once
 * per video frame, which keeps a 4K film free of shimmer when the screen covers fewer pixels than the video has.
 * Outside the screen the layer is transparent (premultiplied alpha) and the theater layer below shows through.
 */
class ScreenRenderer(
	instance: XrInstance,
	systemId: Long,
	session: XrSession,
	private val space: XrSpace,
) : AutoCloseable {
	private val eyes: List<XrSwapchainImages>
	private val shader = ScreenShader()

	init {
		val (width, height) = stackPush().use { stack ->
			val count = stack.mallocInt(1)
			xrCheck(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null), "xrEnumerateViewConfigurationViews")
			val views = XrViewConfigurationView.calloc(count[0], stack)
			views.forEach { it.`type$Default`() }
			xrCheck(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, views), "xrEnumerateViewConfigurationViews")
			val view = views[0]
			val scale = appEnv("SCREEN_SCALE")?.toFloatOrNull()?.coerceIn(0.4f, 2f) ?: 1f
			// SteamVR may ask for supersampled sizes (3408 px per eye seen on the Frame). Beyond ~2880 px the screen
			// gains nothing visible but costs GPU time, power and missed refreshes (judder when turning the head).
			val cap = if (appEnv("SCREEN_SCALE") != null) Int.MAX_VALUE else MAX_EYE_SIZE
			max(512, (view.recommendedImageRectWidth() * scale).toInt().coerceAtMost(cap)) to
				max(512, (view.recommendedImageRectHeight() * scale).toInt().coerceAtMost(cap))
		}
		eyes = List(2) { XrSwapchainImages.create(session, width, height, "screen eye $it") }
	}

	/**
	 * Render both eyes with [videoTexture] (an sRGB texture with mipmaps) and return the projection layer address.
	 * [eyeRects] are per eye UV rects (x0, y0, x1, y1).
	 */
	fun render(stack: MemoryStack, views: XrView.Buffer, room: RoomState, curved: Boolean, eyeRects: List<FloatArray>, videoTexture: Int): Long {
		shader.begin(room, curved, videoTexture)
		val projectionViews = XrCompositionLayerProjectionView.calloc(2, stack)
		for (eye in 0 until 2) {
			val view = views[eye]
			val images = eyes[eye]
			val pose = view.pose()
			val q = pose.orientation()
			val p = pose.`position$`()
			val fov = view.fov()
			images.render(stack) {
				shader.drawEye(
					room = room,
					eyeX = p.x(), eyeY = p.y(), eyeZ = p.z(),
					qx = q.x(), qy = q.y(), qz = q.z(), qw = q.w(),
					tanLeft = tan(fov.angleLeft()), tanRight = tan(fov.angleRight()),
					tanDown = tan(fov.angleDown()), tanUp = tan(fov.angleUp()),
					rect = eyeRects[eye.coerceAtMost(eyeRects.lastIndex)],
					width = images.width, height = images.height,
				)
			}
			projectionViews[eye]
				.`type$Default`()
				.pose(pose)
				.fov(fov)
				.subImage { sub ->
					sub.swapchain(images.swapchain).imageArrayIndex(0).imageRect { r ->
						r.offset { it.x(0).y(0) }.extent { it.width(images.width).height(images.height) }
					}
				}
		}
		shader.end()

		return XrCompositionLayerProjection.calloc(stack)
			.`type$Default`()
			.layerFlags(XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT.toLong())
			.space(space)
			.views(projectionViews)
			.address()
	}

	override fun close() {
		eyes.forEach(XrSwapchainImages::close)
		shader.close()
	}

	private companion object {
		const val MAX_EYE_SIZE = 2880
	}
}

/** The screen shader and its video texture. Independent of OpenXR so it can be previewed offscreen. */
class ScreenShader : AutoCloseable {
	private val program = linkShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
	private val vao = glGenVertexArrays()
	private val texture = glGenTextures()
	private val framebuffer = glGenFramebuffers()
	private var width = 0
	private var height = 0
	private val viewToRoom = FloatArray(9)

	var hasContent = false
		private set

	private val uViewToRoom = glGetUniformLocation(program, "uViewToRoom")
	private val uEye = glGetUniformLocation(program, "uEye")
	private val uFov = glGetUniformLocation(program, "uFov")
	private val uScreen = glGetUniformLocation(program, "uScreen")
	private val uCenterY = glGetUniformLocation(program, "uCenterY")
	private val uCurved = glGetUniformLocation(program, "uCurved")
	private val uRect = glGetUniformLocation(program, "uRect")

	init {
		glUseProgram(program)
		glUniform1i(glGetUniformLocation(program, "uVideo"), 0)
		glUseProgram(0)
	}

	/** (Re)allocate the video texture, with a full mip chain for shimmer free minification. */
	fun resize(width: Int, height: Int) {
		if (width == this.width && height == this.height) return
		this.width = width
		this.height = height
		glBindTexture(GL_TEXTURE_2D, texture)
		// sRGB storage: sampling and mipmapping happen in linear light, which keeps fine detail and colors right when
		// a 4K frame is minified. The blit from mpv's 8 bit buffer copies the encoded values unchanged.
		glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
		glGenerateMipmap(GL_TEXTURE_2D)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
			glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY).coerceIn(1f, 16f))
		}
		glBindTexture(GL_TEXTURE_2D, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		hasContent = false
	}

	fun update(copy: (framebuffer: Int, width: Int, height: Int) -> Boolean) {
		if (width == 0) return
		if (!copy(framebuffer, width, height)) return
		glBindTexture(GL_TEXTURE_2D, texture)
		glGenerateMipmap(GL_TEXTURE_2D)
		glBindTexture(GL_TEXTURE_2D, 0)
		hasContent = true
	}

	/** [videoTexture] overrides the shader's own texture (filled through [update]), e.g. a frame of the render thread. */
	fun begin(room: RoomState, curved: Boolean, videoTexture: Int = texture) {
		this.room = room
		this.curved = curved
		glUseProgram(program)
		glBindVertexArray(vao)
		glActiveTexture(GL_TEXTURE0)
		glBindTexture(GL_TEXTURE_2D, videoTexture)
		glDisable(GL_BLEND)
		glDisable(GL_DEPTH_TEST)
		glDisable(GL_SCISSOR_TEST)
		glDisable(GL_CULL_FACE)
		// The shader outputs linear light: let the hardware encode it for sRGB swapchains (no-op for linear ones)
		glEnable(GL_FRAMEBUFFER_SRGB)
		val halfAngle = (room.screenWidth / room.distance / 2f).coerceAtMost(MAX_HALF_ANGLE)
		// radius, half angle (curved) or half width (flat), half height
		glUniform3f(uScreen, room.distance, if (curved) halfAngle else room.screenWidth / 2f, room.screenHeight / 2f)
		glUniform1f(uCenterY, room.verticalOffset)
		glUniform1i(uCurved, if (curved) 1 else 0)
	}

	fun drawEye(
		room: RoomState,
		eyeX: Float, eyeY: Float, eyeZ: Float,
		qx: Float, qy: Float, qz: Float, qw: Float,
		tanLeft: Float, tanRight: Float, tanDown: Float, tanUp: Float,
		rect: FloatArray,
		width: Int, height: Int,
	) {
		val cy = cos(-room.yaw)
		val sy = sin(-room.yaw)
		val dx = eyeX - room.originX
		val dy = eyeY - room.originY
		val dz = eyeZ - room.originZ
		glUniform3f(uEye, cy * dx + sy * dz, dy, -sy * dx + cy * dz)
		viewToRoom(viewToRoom, qx, qy, qz, qw, cy, sy)
		glUniformMatrix3fv(uViewToRoom, false, viewToRoom)
		glUniform4f(uFov, tanLeft, tanRight, tanDown, tanUp)
		glUniform4f(uRect, rect[0], rect[1], rect[2], rect[3])
		glViewport(0, 0, width, height)

		// Clear to transparent (a fast clear), then shade only the screen's bounding box: the screen rarely fills the
		// view, and pixels around it would only compute "transparent" at full resolution, 144 times per second
		glDisable(GL_SCISSOR_TEST)
		glClearColor(0f, 0f, 0f, 0f)
		glClear(GL_COLOR_BUFFER_BIT)
		val box = screenBounds(cy * dx + sy * dz, dy, -sy * dx + cy * dz, tanLeft, tanRight, tanDown, tanUp, width, height)
		if (box != null && box[2] <= 0) return // screen not in view
		if (box != null) {
			glEnable(GL_SCISSOR_TEST)
			glScissor(box[0], box[1], box[2], box[3])
		}
		glDrawArrays(GL_TRIANGLES, 0, 3)
		glDisable(GL_SCISSOR_TEST)
	}

	private var room = RoomState()
	private var curved = true
	private val bounds = IntArray(4)

	/**
	 * Pixel rect (x, y, width, height) around the screen as seen from the eye, null when it can't be bounded (the
	 * screen reaches behind the eye), width 0 when it is out of view. Samples points along the screen's outline in
	 * room space, projects them with the eye's view and adds a margin for the curve between samples.
	 */
	private fun screenBounds(ex: Float, ey: Float, ez: Float, tanLeft: Float, tanRight: Float, tanDown: Float, tanUp: Float, width: Int, height: Int): IntArray? {
		val m = viewToRoom
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		val halfHeight = room.screenHeight / 2f
		val halfAngle = (room.screenWidth / room.distance / 2f).coerceAtMost(MAX_HALF_ANGLE)
		for (i in 0..OUTLINE_SAMPLES) {
			val f = i / OUTLINE_SAMPLES.toFloat() * 2f - 1f
			val px = if (curved) room.distance * sin(halfAngle * f) else room.screenWidth / 2f * f
			val pz = if (curved) -room.distance * cos(halfAngle * f) else -room.distance
			for (side in 0..1) {
				val py = room.verticalOffset + if (side == 0) -halfHeight else halfHeight
				// Room space to view space: multiply by the transpose of the (orthonormal) view to room rotation
				val rx = px - ex
				val ry = py - ey
				val rz = pz - ez
				val vx = m[0] * rx + m[1] * ry + m[2] * rz
				val vy = m[3] * rx + m[4] * ry + m[5] * rz
				val vz = m[6] * rx + m[7] * ry + m[8] * rz
				if (vz > -0.05f) return null
				val sx = vx / -vz
				val sy = vy / -vz
				minX = minOf(minX, sx)
				maxX = maxOf(maxX, sx)
				minY = minOf(minY, sy)
				maxY = maxOf(maxY, sy)
			}
		}
		// Tangent space to pixels, with a margin for the curve between samples and the anti-aliased edge
		val marginX = (maxX - minX) * 0.03f + 2f * (tanRight - tanLeft) / width
		val marginY = (maxY - minY) * 0.06f + 2f * (tanUp - tanDown) / height
		val x0 = (((minX - marginX - tanLeft) / (tanRight - tanLeft)) * width).toInt().coerceIn(0, width)
		val x1 = (((maxX + marginX - tanLeft) / (tanRight - tanLeft)) * width).toInt().coerceIn(0, width)
		val y0 = (((minY - marginY - tanDown) / (tanUp - tanDown)) * height).toInt().coerceIn(0, height)
		val y1 = (((maxY + marginY - tanDown) / (tanUp - tanDown)) * height).toInt().coerceIn(0, height)
		bounds[0] = x0
		bounds[1] = y0
		bounds[2] = if (x1 > x0 && y1 > y0) x1 - x0 else 0
		bounds[3] = (y1 - y0).coerceAtLeast(0)
		return bounds
	}

	fun end() {
		glDisable(GL_FRAMEBUFFER_SRGB)
		glBindVertexArray(0)
		glUseProgram(0)
		glBindTexture(GL_TEXTURE_2D, 0)
	}

	override fun close() {
		glDeleteProgram(program)
		glDeleteVertexArrays(vao)
		glDeleteFramebuffers(framebuffer)
		glDeleteTextures(texture)
	}

	private companion object {
		const val GL_TEXTURE_MAX_ANISOTROPY = 0x84FE
		const val GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF
		const val GL_SRGB8_ALPHA8 = 0x8C43
		const val GL_FRAMEBUFFER_SRGB = 0x8DB9
		const val OUTLINE_SAMPLES = 12
		const val MAX_HALF_ANGLE = 1.5f

		val VERTEX_SHADER = """
			#version 330 core
			uniform vec4 uFov; // tan(left), tan(right), tan(down), tan(up)
			out vec3 vViewDir;

			void main() {
				vec2 ndc = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
				vec2 t = ndc * 0.5 + 0.5;
				vViewDir = vec3(mix(uFov.x, uFov.y, t.x), mix(uFov.z, uFov.w, t.y), -1.0);
				gl_Position = vec4(ndc, 0.0, 1.0);
			}
		""".trimIndent()

		val FRAGMENT_SHADER = """
			#version 330 core
			in vec3 vViewDir;

			uniform mat3 uViewToRoom;
			uniform vec3 uEye;
			uniform vec3 uScreen; // radius, half angle (curved) or half width (flat), half height
			uniform float uCenterY;
			uniform int uCurved;
			uniform vec4 uRect; // this eye's part of the video texture: x0, y0, x1, y1
			uniform sampler2D uVideo;

			out vec4 fragColor;

			void main() {
				vec3 d = normalize(uViewToRoom * vViewDir);
				vec2 uv;
				if (uCurved == 1) {
					// The screen is part of a vertical cylinder around the viewer (radius = distance)
					float a = d.x * d.x + d.z * d.z;
					float b = 2.0 * (uEye.x * d.x + uEye.z * d.z);
					float c = uEye.x * uEye.x + uEye.z * uEye.z - uScreen.x * uScreen.x;
					float disc = b * b - 4.0 * a * c;
					if (a < 1e-6 || disc < 0.0) { fragColor = vec4(0.0); return; }
					float t = (-b + sqrt(disc)) / (2.0 * a);
					vec3 p = uEye + d * t;
					float theta = atan(p.x, -p.z);
					uv = vec2(theta / (2.0 * uScreen.y) + 0.5, (p.y - uCenterY) / (2.0 * uScreen.z) + 0.5);
				} else {
					if (d.z > -1e-4) { fragColor = vec4(0.0); return; }
					float t = (-uScreen.x - uEye.z) / d.z;
					vec3 p = uEye + d * t;
					uv = vec2(p.x / (2.0 * uScreen.y) + 0.5, (p.y - uCenterY) / (2.0 * uScreen.z) + 0.5);
				}

				// Coverage of the screen rectangle with a one pixel soft edge (no jaggies along the border)
				vec2 inside = min(uv, 1.0 - uv) / max(fwidth(uv), vec2(1e-6));
				float alpha = clamp(min(inside.x, inside.y) + 0.5, 0.0, 1.0);
				if (alpha <= 0.0) { fragColor = vec4(0.0); return; }

				vec3 color = texture(uVideo, mix(uRect.xy, uRect.zw, clamp(uv, 0.0, 1.0))).rgb;
				// Premultiplied alpha, so the theater layer below shows through around the screen
				fragColor = vec4(color * alpha, alpha);
			}
		""".trimIndent()
	}
}
