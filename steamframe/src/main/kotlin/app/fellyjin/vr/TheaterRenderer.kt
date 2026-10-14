package app.fellyjin.vr

import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_CULL_FACE
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1f
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUniform3f
import org.lwjgl.opengl.GL20.glUniform4f
import org.lwjgl.opengl.GL20.glUniformMatrix3fv
import org.lwjgl.opengl.GL20.glUseProgram
import org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glBlitFramebuffer
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Where the screen and viewer are, in the LOCAL reference space. */
class RoomState {
	var originX = 0f
	var originY = 0f
	var originZ = 0f
	var yaw = 0f
	var screenWidth = 24f
	var screenHeight = 13.5f
	var distance = 15f
	var verticalOffset = 0f

	/** 1 = house lights fully on (paused, loading), 0 = dark theater during the movie. */
	var houseLights = 1f

	/** 0 before the first video frame, then fades to 1 so the screen glow doesn't pop in. */
	var screenGlow = 0f
}

/**
 * Renders a movie theater auditorium around the cinema screen as an OpenXR projection layer.
 *
 * The room is ray cast analytically in a single full screen pass per eye ([TheaterShader]): no meshes, and only a
 * handful of ALU operations per pixel. It is lit by three light zones taken from the current video frame (left,
 * center, right), so explosions light up the room and night scenes leave it dark, like a real theater. The room is
 * dark and smooth, so it renders at half resolution to leave GPU budget for 144 Hz. The video itself stays a separate
 * composition layer at full sharpness.
 */
class TheaterRenderer(
	instance: XrInstance,
	systemId: Long,
	session: XrSession,
	private val space: XrSpace,
) : AutoCloseable {
	private val eyes: List<XrSwapchainImages>
	private val shader = TheaterShader()

	init {
		val (width, height) = stackPush().use { stack ->
			val count = stack.mallocInt(1)
			xrCheck(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null), "xrEnumerateViewConfigurationViews")
			val views = XrViewConfigurationView.calloc(count[0], stack)
			views.forEach { it.`type$Default`() }
			xrCheck(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, views), "xrEnumerateViewConfigurationViews")
			val view = views[0]
			// Half resolution, at most 1280 px: the room is dark and soft, more pixels only cost power
			max(512, (view.recommendedImageRectWidth() * RENDER_SCALE).toInt().coerceAtMost(MAX_EYE_SIZE)) to
				max(512, (view.recommendedImageRectHeight() * RENDER_SCALE).toInt().coerceAtMost(MAX_EYE_SIZE))
		}
		eyes = List(2) { XrSwapchainImages.create(session, width, height, "theater eye $it") }
	}

	/** Light the room with the freshly rendered video frame, see [TheaterShader.captureScreen]. */
	fun captureScreen(videoFramebuffer: Int, x: Int, y: Int, width: Int, height: Int) =
		shader.captureScreen(videoFramebuffer, x, y, width, height)

	/** Render both eyes and return the projection layer address, ready for xrEndFrame. */
	fun render(stack: MemoryStack, views: XrView.Buffer, room: RoomState): Long {
		shader.beginFrame(room)
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
					width = images.width, height = images.height,
					outputSrgb = images.isSrgb,
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
		shader.endFrame()

		return XrCompositionLayerProjection.calloc(stack)
			.`type$Default`()
			.space(space)
			.views(projectionViews)
			.address()
	}

	override fun close() {
		eyes.forEach(XrSwapchainImages::close)
		shader.close()
	}

	private companion object {
		/** The room is dark with soft gradients, half resolution is indistinguishable and saves 75% of the pixels. */
		const val RENDER_SCALE = 0.5f
		const val MAX_EYE_SIZE = 1280
	}
}

/** The theater shader and its glow texture. Independent of OpenXR so it can be previewed offscreen. */
class TheaterShader : AutoCloseable {
	private val program = linkShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
	private val vao = glGenVertexArrays()

	// Small mipmapped copy of the video frame used to light the room
	private val glowTexture: Int
	private val glowFramebuffer: Int

	private val uViewToRoom = glGetUniformLocation(program, "uViewToRoom")
	private val uEye = glGetUniformLocation(program, "uEye")
	private val uRoomMin = glGetUniformLocation(program, "uRoomMin")
	private val uRoomMax = glGetUniformLocation(program, "uRoomMax")
	private val uScreen = glGetUniformLocation(program, "uScreen")
	private val uScreenDistance = glGetUniformLocation(program, "uScreenDistance")
	private val uHouse = glGetUniformLocation(program, "uHouse")
	private val uGlow = glGetUniformLocation(program, "uGlow")
	private val uOutputSrgb = glGetUniformLocation(program, "uOutputSrgb")
	private val uFrame = glGetUniformLocation(program, "uFrame")
	private val uFov = glGetUniformLocation(program, "uFov")
	private var frame = 0
	private val viewToRoom = FloatArray(9)

	init {
		glUseProgram(program)
		glUniform1i(glGetUniformLocation(program, "uGlowTexture"), 0)
		glUseProgram(0)

		glowTexture = glGenTextures()
		glBindTexture(GL_TEXTURE_2D, glowTexture)
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, GLOW_WIDTH, GLOW_HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
		glGenerateMipmap(GL_TEXTURE_2D)
		glBindTexture(GL_TEXTURE_2D, 0)
		glowFramebuffer = glGenFramebuffers()
		glBindFramebuffer(GL_FRAMEBUFFER, glowFramebuffer)
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glowTexture, 0)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
	}

	/**
	 * Copy the freshly rendered video frame (only the given rect, e.g. one eye of a 3D video) into the glow texture.
	 * Runs once per video frame (24-60 times per second), not per display frame, and never reads back to the CPU.
	 */
	fun captureScreen(videoFramebuffer: Int, x: Int, y: Int, width: Int, height: Int) {
		glBindFramebuffer(GL_READ_FRAMEBUFFER, videoFramebuffer)
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, glowFramebuffer)
		glBlitFramebuffer(x, y, x + width, y + height, 0, 0, GLOW_WIDTH, GLOW_HEIGHT, GL_COLOR_BUFFER_BIT, GL_LINEAR)
		glBindFramebuffer(GL_FRAMEBUFFER, 0)
		glBindTexture(GL_TEXTURE_2D, glowTexture)
		glGenerateMipmap(GL_TEXTURE_2D)
		glBindTexture(GL_TEXTURE_2D, 0)
	}

	fun beginFrame(room: RoomState) {
		frame++
		glUseProgram(program)
		glBindVertexArray(vao)
		glActiveTexture(GL_TEXTURE0)
		glBindTexture(GL_TEXTURE_2D, glowTexture)
		glDisable(GL_BLEND)
		glDisable(GL_DEPTH_TEST)
		glDisable(GL_SCISSOR_TEST)
		glDisable(GL_CULL_FACE)
		glDisable(GL_FRAMEBUFFER_SRGB_ENABLE) // the shader encodes its output itself

		// Room layout around the screen, in room space: viewer at the origin, screen centered on -Z
		val halfWidth = max(room.screenWidth * 0.5f + 3f, 6f)
		val floor = min(-1.2f, room.verticalOffset - room.screenHeight * 0.5f - 0.6f)
		val ceiling = max(room.verticalOffset + room.screenHeight * 0.5f + 3f, 4f)
		val front = -room.distance - 0.4f
		val back = max(6f, room.distance * 0.45f)
		glUniform3f(uRoomMin, -halfWidth, floor, front)
		glUniform3f(uRoomMax, halfWidth, ceiling, back)
		glUniform4f(uScreen, room.screenWidth * 0.5f, room.screenHeight * 0.5f, room.verticalOffset, 0f)
		glUniform1f(uScreenDistance, room.distance)
		glUniform1f(uHouse, room.houseLights)
		glUniform1f(uGlow, room.screenGlow)
		glUniform1f(uFrame, (frame % 1024).toFloat())
	}

	/** Draw one eye into the currently bound framebuffer. Positions and orientation are in the LOCAL space. */
	fun drawEye(
		room: RoomState,
		eyeX: Float, eyeY: Float, eyeZ: Float,
		qx: Float, qy: Float, qz: Float, qw: Float,
		tanLeft: Float, tanRight: Float, tanDown: Float, tanUp: Float,
		width: Int, height: Int,
		outputSrgb: Boolean,
	) {
		// Eye position and orientation relative to the room (rotate the LOCAL space by -yaw around the origin)
		val cy = cos(-room.yaw)
		val sy = sin(-room.yaw)
		val dx = eyeX - room.originX
		val dy = eyeY - room.originY
		val dz = eyeZ - room.originZ
		glUniform3f(uEye, cy * dx + sy * dz, dy, -sy * dx + cy * dz)
		viewToRoom(viewToRoom, qx, qy, qz, qw, cy, sy)
		glUniformMatrix3fv(uViewToRoom, false, viewToRoom)
		glUniform4f(uFov, tanLeft, tanRight, tanDown, tanUp)
		glUniform1i(uOutputSrgb, if (outputSrgb) 1 else 0)
		glViewport(0, 0, width, height)
		glDrawArrays(GL_TRIANGLES, 0, 3)
	}

	fun endFrame() {
		glBindVertexArray(0)
		glUseProgram(0)
		glBindTexture(GL_TEXTURE_2D, 0)
	}

	override fun close() {
		glDeleteProgram(program)
		glDeleteVertexArrays(vao)
		glDeleteFramebuffers(glowFramebuffer)
		glDeleteTextures(glowTexture)
	}

	private companion object {
		const val GLOW_WIDTH = 64
		const val GLOW_HEIGHT = 36

		// Full screen triangle. The view ray is linear in screen space, so it is computed per vertex. The three screen
		// light zones are sampled per vertex too (flat), which keeps texture fetches out of the fragment shader.
		val VERTEX_SHADER = """
			#version 330 core
			uniform sampler2D uGlowTexture;
			uniform vec4 uFov; // tan(left), tan(right), tan(down), tan(up)
			out vec3 vViewDir;
			flat out vec3 vZone0;
			flat out vec3 vZone1;
			flat out vec3 vZone2;

			vec3 zone(float u) {
				// Mip level 3 of the 64x36 copy is 8x4 texels: a soft average of a third of the screen
				vec3 c = textureLod(uGlowTexture, vec2(u, 0.5), 3.0).rgb;
				return pow(c, vec3(2.2));
			}

			void main() {
				vec2 ndc = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
				vec2 t = ndc * 0.5 + 0.5;
				vViewDir = vec3(mix(uFov.x, uFov.y, t.x), mix(uFov.z, uFov.w, t.y), -1.0);
				vZone0 = zone(1.0 / 6.0);
				vZone1 = zone(0.5);
				vZone2 = zone(5.0 / 6.0);
				gl_Position = vec4(ndc, 0.0, 1.0);
			}
		""".trimIndent()

		val FRAGMENT_SHADER = """
			#version 330 core
			in vec3 vViewDir;
			flat in vec3 vZone0;
			flat in vec3 vZone1;
			flat in vec3 vZone2;

			uniform mat3 uViewToRoom;
			uniform vec3 uEye;
			uniform vec3 uRoomMin;
			uniform vec3 uRoomMax;
			uniform vec4 uScreen; // half width, half height, center y
			uniform float uScreenDistance;
			uniform float uHouse;
			uniform float uGlow;
			uniform int uOutputSrgb;
			uniform float uFrame;

			out vec4 fragColor;

			const vec3 WARM = vec3(1.0, 0.72, 0.45);
			// Fellyjin mint for the aisle step lights
			const vec3 STEP = vec3(0.18, 0.95, 0.78);

			float hash(vec2 p) {
				return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
			}

			// Light from one third of the screen, treated as a soft area light facing the audience (+Z)
			vec3 screenLight(vec3 p, vec3 n, float x, vec3 color) {
				vec3 lightPos = vec3(x, uScreen.z, -uScreenDistance);
				vec3 l = lightPos - p;
				float d2 = dot(l, l);
				l *= inversesqrt(d2);
				float emit = max(-l.z, 0.0);
				float receive = max(dot(n, l), 0.0);
				float area = uScreen.x * uScreen.y * 0.66;
				return color * emit * receive * area / (d2 * 3.14159 + area);
			}

			// Soft round glowing spot (wall sconce, step light, ceiling downlight)
			float spot(vec2 p, vec2 center, float radius) {
				vec2 d = p - center;
				return exp(-dot(d, d) / (radius * radius));
			}

			void main() {
				vec3 dir = normalize(uViewToRoom * vViewDir);

				// Exit point of the ray from the inside of the room box
				vec3 inv = 1.0 / dir;
				vec3 tA = (uRoomMin - uEye) * inv;
				vec3 tB = (uRoomMax - uEye) * inv;
				vec3 tFar = max(tA, tB);
				float t = min(min(tFar.x, tFar.y), tFar.z);
				vec3 p = uEye + dir * t;

				vec3 n;
				vec3 albedo;
				vec3 emission = vec3(0.0);
				float halfW = uScreen.x;

				if (t == tFar.y && dir.y < 0.0) {
					// Floor: dark carpet with seat rows and amber aisle step lights
					n = vec3(0.0, 1.0, 0.0);
					float rows = step(0.55, fract(p.z / 1.1));
					float audience = step(-uScreenDistance * 0.55, p.z);
					albedo = vec3(0.030, 0.022, 0.026) * (1.0 - 0.35 * rows * audience);
					albedo *= 0.9 + 0.2 * hash(floor(p.xz * 30.0));
					float aisle = halfW * 0.55 + 1.0;
					vec2 cell = vec2(abs(p.x), p.z);
					vec2 stepLight = vec2(aisle, floor(p.z / 1.1) * 1.1 + 0.55);
					emission += STEP * 1.6 * spot(cell, stepLight, 0.05) * audience;
				} else if (t == tFar.y) {
					// Ceiling: matte black with downlights that are part of the house lights
					n = vec3(0.0, -1.0, 0.0);
					albedo = vec3(0.012);
					vec2 grid = vec2(mod(p.x + 1.5, 3.0) - 1.5, mod(p.z + 1.5, 3.0) - 1.5);
					emission += WARM * 2.5 * uHouse * spot(grid, vec2(0.0), 0.12);
				} else if (t == tFar.x) {
					// Side walls: fabric acoustic panels above a dark wainscot, with wall washers that stay faintly lit
					n = vec3(-sign(dir.x), 0.0, 0.0);
					float strip = fract(p.z / 1.6);
					float gap = step(0.03, strip) * step(strip, 0.97);
					float wainscot = step(p.y, uRoomMin.y + 1.1);
					albedo = vec3(0.034, 0.026, 0.046) * (0.6 + 0.4 * gap) * (0.85 + 0.15 * sin(strip * 6.2832));
					albedo = mix(albedo, vec3(0.018, 0.014, 0.014), wainscot);
					vec2 wall = vec2(mod(p.z + 2.0, 4.0) - 2.0, p.y - (uRoomMin.y + 2.4));
					float fixture = spot(wall, vec2(0.0), 0.10);
					float wash = exp(-wall.x * wall.x / 0.35) * exp(-max(wall.y, 0.0) * 0.8) * step(0.0, wall.y);
					emission += WARM * (fixture * (0.25 + 2.0 * uHouse) + wash * 0.10 * (0.12 + uHouse));
				} else if (dir.z < 0.0) {
					// Front wall: the screen in a black masking frame, flanked by violet velvet curtains
					n = vec3(0.0, 0.0, 1.0);
					vec2 s = abs(vec2(p.x, p.y - uScreen.z)) - uScreen.xy;
					float edge = max(s.x, s.y);
					float folds = 0.5 + 0.5 * sin(p.x * 9.0 + sin(p.x * 2.3) * 2.0);
					vec3 curtain = vec3(0.12, 0.022, 0.20) * (0.35 + 0.65 * folds);
					albedo = mix(curtain, vec3(0.006), step(edge, 0.4));
					// Grazing spill from the picture onto the curtains, fading with the distance to the screen edge
					emission += curtain * (vZone0 + vZone1 + vZone2) * 1.2 * uGlow * exp(-max(edge, 0.0) * 0.5) * step(0.4, edge);
					// Matte white screen surface, only visible before the movie starts (the video layer covers it)
					albedo = mix(albedo, vec3(0.35), step(edge, 0.0));
				} else {
					// Back wall with the projection booth window
					n = vec3(0.0, 0.0, -1.0);
					albedo = vec3(0.03, 0.022, 0.025);
					vec2 booth = vec2(p.x, p.y - (uScreen.z + uScreen.y * 0.6 + 1.0));
					float window = step(abs(booth.x), 0.6) * step(abs(booth.y), 0.25);
					emission += vec3(0.85, 0.9, 1.0) * window * (0.25 + 1.5 * uGlow * (1.0 - uHouse));
				}

				vec3 light = WARM * 0.05 * uHouse;
				vec3 glow = screenLight(p, n, -halfW * 2.0 / 3.0, vZone0)
					+ screenLight(p, n, 0.0, vZone1)
					+ screenLight(p, n, halfW * 2.0 / 3.0, vZone2);
				light += glow * 2.2 * uGlow;

				vec3 color = albedo * light * 12.0 + emission;
				// Soft shoulder so bright scenes don't clip the walls to white
				color = color / (1.0 + color);

				if (uOutputSrgb == 1) color = pow(color, vec3(1.0 / 2.2));
				// Dither: dark gradients would otherwise band visibly in 8 bit
				color += (hash(gl_FragCoord.xy + uFrame) - 0.5) / 255.0;
				fragColor = vec4(color, 1.0);
			}
		""".trimIndent()
	}
}

/**
 * Column-major mat3 into [out]: rotate by the eye orientation quaternion, then by -yaw around Y (cos/sin of -yaw given
 * as [cy]/[sy]). Converts view space directions into room space. Written out to avoid per frame allocations.
 */
internal fun viewToRoom(out: FloatArray, x: Float, y: Float, z: Float, w: Float, cy: Float, sy: Float) {
	val m00 = 1 - 2 * (y * y + z * z)
	val m10 = 2 * (x * y + z * w)
	val m20 = 2 * (x * z - y * w)
	val m01 = 2 * (x * y - z * w)
	val m11 = 1 - 2 * (x * x + z * z)
	val m21 = 2 * (y * z + x * w)
	val m02 = 2 * (x * z + y * w)
	val m12 = 2 * (y * z - x * w)
	val m22 = 1 - 2 * (x * x + y * y)
	// Ry(-yaw) * M, with Ry rows (cy, 0, sy), (0, 1, 0), (-sy, 0, cy)
	out[0] = cy * m00 + sy * m20
	out[1] = m10
	out[2] = -sy * m00 + cy * m20
	out[3] = cy * m01 + sy * m21
	out[4] = m11
	out[5] = -sy * m01 + cy * m21
	out[6] = cy * m02 + sy * m22
	out[7] = m12
	out[8] = -sy * m02 + cy * m22
}

	/** Compile and link a GLSL program, throwing [XrException] with the driver's log on errors. */
/** GL_FRAMEBUFFER_SRGB, kept off for passes that write already encoded values. */
private const val GL_FRAMEBUFFER_SRGB_ENABLE = 0x8DB9

internal fun linkShaderProgram(vertex: String, fragment: String): Int {
		fun compile(type: Int, source: String): Int {
			val shader = glCreateShader(type)
			glShaderSource(shader, source)
			glCompileShader(shader)
			if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
				throw XrException("Shader failed to compile: ${glGetShaderInfoLog(shader)}")
			}
			return shader
		}

		val vs = compile(GL_VERTEX_SHADER, vertex)
		val fs = compile(GL_FRAGMENT_SHADER, fragment)
		val program = glCreateProgram()
		glAttachShader(program, vs)
		glAttachShader(program, fs)
		glLinkProgram(program)
		glDeleteShader(vs)
		glDeleteShader(fs)
		if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
			throw XrException("Shader failed to link: ${glGetProgramInfoLog(program)}")
		}
		return program
	}
