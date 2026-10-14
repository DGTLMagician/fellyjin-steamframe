package app.fellyjin.vr

import app.fellyjin.BuildInfo
import app.fellyjin.appEnv
import app.fellyjin.mpv.MpvPlayer
import app.fellyjin.playback.ResolvedStream
import app.fellyjin.playback.StereoLayout
import app.fellyjin.playback.VideoOutput
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetProcAddress
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwInitHint
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwSetErrorCallback
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWNativeGLX.glfwGetGLXContext
import org.lwjgl.glfw.GLFWNativeGLX.glfwGetGLXFBConfig
import org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.glFlush
import org.lwjgl.opengl.GLX.glXGetCurrentDrawable
import org.lwjgl.opengl.GLX13.glXGetVisualFromFBConfig
import org.lwjgl.openxr.KHRCompositionLayerCylinder
import org.lwjgl.openxr.FBDisplayRefreshRate
import org.lwjgl.openxr.KHROpenGLEnable
import org.lwjgl.openxr.MNDXEGLEnable
import org.lwjgl.openxr.XrGraphicsBindingEGLMNDX
import org.lwjgl.openxr.XrInstanceProperties
import org.lwjgl.openxr.XrSystemProperties
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API
import org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API
import org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLConfig
import org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLContext
import org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLDisplay
import org.lwjgl.openxr.XR
import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrAction
import org.lwjgl.openxr.XrActionCreateInfo
import org.lwjgl.openxr.XrActionSet
import org.lwjgl.openxr.XrActionSetCreateInfo
import org.lwjgl.openxr.XrActionStateBoolean
import org.lwjgl.openxr.XrActionStateGetInfo
import org.lwjgl.openxr.XrActionStateVector2f
import org.lwjgl.openxr.XrActionSuggestedBinding
import org.lwjgl.openxr.XrActionsSyncInfo
import org.lwjgl.openxr.XrActiveActionSet
import org.lwjgl.openxr.XrApplicationInfo
import org.lwjgl.openxr.XrCompositionLayerCylinderKHR
import org.lwjgl.openxr.XrCompositionLayerQuad
import org.lwjgl.openxr.XrEventDataBuffer
import org.lwjgl.openxr.XrEventDataSessionStateChanged
import org.lwjgl.openxr.XrExtensionProperties
import org.lwjgl.openxr.XrFrameBeginInfo
import org.lwjgl.openxr.XrFrameEndInfo
import org.lwjgl.openxr.XrFrameState
import org.lwjgl.openxr.XrFrameWaitInfo
import org.lwjgl.openxr.XrGraphicsBindingOpenGLXlibKHR
import org.lwjgl.openxr.XrGraphicsRequirementsOpenGLKHR
import org.lwjgl.openxr.XrInstance
import org.lwjgl.openxr.XrInstanceCreateInfo
import org.lwjgl.openxr.XrInteractionProfileState
import org.lwjgl.openxr.XrInteractionProfileSuggestedBinding
import org.lwjgl.openxr.XrPosef
import org.lwjgl.openxr.XrReferenceSpaceCreateInfo
import org.lwjgl.openxr.XrSession
import org.lwjgl.openxr.XrSessionActionSetsAttachInfo
import org.lwjgl.openxr.XrSessionBeginInfo
import org.lwjgl.openxr.XrSessionCreateInfo
import org.lwjgl.openxr.XrSpace
import org.lwjgl.openxr.XrSpaceLocation
import org.lwjgl.openxr.XrSystemGetInfo
import org.lwjgl.openxr.XrView
import org.lwjgl.openxr.XrViewLocateInfo
import org.lwjgl.openxr.XrViewState
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.Configuration
import org.lwjgl.system.Platform
import org.slf4j.LoggerFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class XrException(message: String) : RuntimeException(message)

/**
 * Plays video on a huge virtual cinema screen using OpenXR (SteamVR on the Steam Frame).
 *
 * mpv renders through its OpenGL render API straight into an OpenXR swapchain image, which is shown as a curved
 * (cylinder) or flat (quad) composition layer. Composition layers are sampled by the compositor at display
 * resolution, which gives a much sharper image than drawing a textured quad in a projection layer.
 *
 * Stereoscopic 3D videos are shown by giving each eye its own layer with half of the frame.
 *
 * Around the screen a [TheaterRenderer] draws an auditorium lit by the movie. Performance notes for 144 Hz:
 * - mpv only renders when there is a new video frame (24-60 per second), every other display frame just resubmits
 *   the layers, which costs the compositor nothing extra.
 * - The theater is a single analytic full screen pass at half resolution per eye.
 * - The frame loop allocates nothing on the heap, all OpenXR structs live on the LWJGL stack.
 *
 * Everything in this class runs on the playback thread, which owns the OpenGL context.
 */
class XrCinemaOutput(private val settings: CinemaSettings) : VideoOutput {
	private val logger = LoggerFactory.getLogger(XrCinemaOutput::class.java)

	override val name = "VR cinema"
	override val eventTimeoutSeconds = 0.0

	// OpenGL
	private var window = NULL
	private var glfwInitialized = false
	private var errorCallback: GLFWErrorCallback? = null

	// OpenXR
	private var instance: XrInstance? = null
	private var systemId = NULL
	private var session: XrSession? = null
	private var localSpace: XrSpace? = null
	private var viewSpace: XrSpace? = null
	private var supportsCylinder = false
	private var blendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE
	private var sessionRunning = false
	private var sessionFocused = false
	private var exitRequested = false

	private var supportsRefreshRate = false
	private var supportsFrameController = false

	// Video swapchain
	private var video: XrSwapchainImages? = null
	private var stereoLayout = StereoLayout.MONO

	// Theater environment
	private var theater: TheaterRenderer? = null

	/** Curved screen drawn by the app, when the runtime has no cylinder layers (SteamVR on the Steam Frame). */
	private var screen: ScreenRenderer? = null
	private var screenWidthPx = 0
	private var screenHeightPx = 0

	/** True once the first video frame is on screen, through either path. */
	private val hasVideo: Boolean get() = video?.hasContent == true || screen?.hasContent == true
	private var videoThread: VideoRenderThread? = null
	private var pendingOsd: String? = null
	private val room = RoomState()
	private var paused = true
	private var lastPausePoll = TimeSource.Monotonic.markNow()
	private var displayHz = 0f
	private var lastFrameTime = 0L

	// Screen placement, relative to the LOCAL reference space
	private var distance = settings.screen.distanceMeters
	private val screenWidth = settings.screen.widthMeters
	private var screenYaw = 0f
	private var screenX = 0f
	private var screenY = settings.verticalOffsetMeters
	private var screenZ = 0f
	private var viewerY = 0f
	private var recenterPending = true

	// Input
	private var actionSet: XrActionSet? = null
	private val actions = mutableMapOf<CinemaAction, XrAction>()
	private var stickAction: XrAction? = null
	private var seekHeldSince: TimeSource.Monotonic.ValueTimeMark? = null
	private var lastSeek: TimeSource.Monotonic.ValueTimeMark? = null

	private enum class CinemaAction(val id: String, val label: String) {
		PLAY_PAUSE("play_pause", "Play / pause"),
		EXIT("exit", "Stop playback"),
		CYCLE_SUBTITLES("cycle_subtitles", "Next subtitle track"),
		CYCLE_AUDIO("cycle_audio", "Next audio track"),
		RECENTER("recenter", "Recenter screen"),
		INFO("info", "Show progress"),
		SEEK_BACK("seek_back", "Rewind 10 seconds"),
		SEEK_FORWARD("seek_forward", "Forward 30 seconds"),
		SCREEN_CLOSER("screen_closer", "Move screen closer"),
		SCREEN_FURTHER("screen_further", "Move screen further away"),
		PREVIOUS_CHAPTER("previous_chapter", "Previous chapter"),
		NEXT_CHAPTER("next_chapter", "Next chapter"),
	}

	/** How the OpenGL context is created and handed to OpenXR. */
	private enum class GlApi(val label: String) {
		/** EGL context with XR_MNDX_egl_enable: no dependency on GLX through XWayland. */
		EGL("OpenGL (EGL)"),

		/** GLX context with the XR_KHR_opengl_enable Xlib binding. */
		GLX("OpenGL (GLX)"),
	}

	private var glApi = GlApi.GLX

	init {
		if (Platform.get() != Platform.LINUX) throw XrException("OpenXR playback is only supported on Linux")
		ensureLoader()
		val extensions = stackPush().use { availableExtensions(it) }
		if (KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME !in extensions) {
			throw XrException("The OpenXR runtime does not support OpenGL (extensions: ${extensions.sorted().joinToString()})")
		}

		// Prefer EGL when the runtime offers it, fall back to GLX. FELLYJIN_XR_GL=glx|egl forces one.
		val forced = appEnv("XR_GL")?.lowercase()
		val candidates = buildList {
			if (MNDXEGLEnable.XR_MNDX_EGL_ENABLE_EXTENSION_NAME in extensions && forced != "glx") add(GlApi.EGL)
			if (forced != "egl") add(GlApi.GLX)
		}
		val errors = mutableListOf<String>()
		for (api in candidates) {
			try {
				initOpenGl(api)
				initOpenXr(api, extensions)
				glApi = api
				break
			} catch (err: Throwable) {
				logger.warn("VR start with ${api.label} failed", err)
				errors += "${api.label}: ${err.message ?: err}"
				destroy()
			}
		}
		if (errors.size == candidates.size) throw XrException(errors.joinToString("; ").ifEmpty { "No usable OpenGL binding" })
		VrDiagnostics.update { it.copy(state = VrStatus.State.RUNNING, reason = null, graphics = glApi.label) }
	}

	// region Initialization

	private fun initOpenGl(api: GlApi) {
		errorCallback = GLFWErrorCallback.create { error, description ->
			logger.warn("GLFW error $error: ${GLFWErrorCallback.getDescription(description)}")
		}
		glfwSetErrorCallback(errorCallback)

		// X11 (XWayland under gamescope) for both bindings: GLX needs it, and EGL on X11 is the best tested GLFW path
		glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
		if (!glfwInit()) throw XrException("Unable to initialize GLFW with X11, is DISPLAY set?")
		glfwInitialized = true

		glfwDefaultWindowHints()
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
		if (api == GlApi.EGL) glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API)
		window = glfwCreateWindow(16, 16, "Fellyjin VR", NULL, NULL)
		if (window == NULL) throw XrException("Unable to create an ${api.label} context")
		glfwMakeContextCurrent(window)
		GL.createCapabilities()
	}

	private fun initOpenXr(api: GlApi, extensions: Set<String>) = stackPush().use { stack ->
		supportsCylinder = KHRCompositionLayerCylinder.XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME in extensions
		supportsRefreshRate = FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME in extensions
		supportsFrameController = FRAME_CONTROLLER_EXTENSION in extensions
		logger.info("OpenXR extensions: cylinder=$supportsCylinder refreshRate=$supportsRefreshRate frameController=$supportsFrameController")

		val enabledExtensions = buildList {
			add(KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)
			if (api == GlApi.EGL) add(MNDXEGLEnable.XR_MNDX_EGL_ENABLE_EXTENSION_NAME)
			if (supportsCylinder) add(KHRCompositionLayerCylinder.XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME)
			if (supportsRefreshRate) add(FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)
			if (supportsFrameController) add(FRAME_CONTROLLER_EXTENSION)
		}
		val extensionNames = stack.mallocPointer(enabledExtensions.size)
		enabledExtensions.forEach { extensionNames.put(stack.UTF8(it)) }
		extensionNames.flip()

		val createInfo = XrInstanceCreateInfo.calloc(stack)
			.`type$Default`()
			.applicationInfo(
				XrApplicationInfo.calloc(stack)
					.applicationName(stack.UTF8(BuildInfo.CLIENT_NAME))
					.applicationVersion(1)
					.engineName(stack.UTF8(BuildInfo.APP_NAME))
					.engineVersion(1)
					// Request 1.0 so runtimes that do not implement 1.1 yet still accept the instance
					.apiVersion(XR_API_VERSION_1_0)
			)
			.enabledExtensionNames(extensionNames)

		val instancePtr = stack.mallocPointer(1)
		xrCheck(xrCreateInstance(createInfo, instancePtr), "xrCreateInstance")
		val instance = XrInstance(instancePtr[0], createInfo)
		this.instance = instance
		val properties = XrInstanceProperties.calloc(stack).`type$Default`()
		if (xrGetInstanceProperties(instance, properties) >= 0) {
			val v = properties.runtimeVersion()
			val runtime = "${properties.runtimeNameString()} ${v ushr 48}.${(v ushr 32) and 0xffff}.${v and 0xffffffffL}"
			logger.info("OpenXR runtime: $runtime")
			VrDiagnostics.update { it.copy(runtime = runtime) }
		}

		val systemPtr = stack.mallocLong(1)
		val systemResult = xrGetSystem(
			instance,
			XrSystemGetInfo.calloc(stack).`type$Default`().formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY),
			systemPtr,
		)
		if (systemResult < 0) throw XrException("No headset found (xrGetSystem: $systemResult), is SteamVR running?")
		systemId = systemPtr[0]
		val systemProperties = XrSystemProperties.calloc(stack).`type$Default`()
		if (xrGetSystemProperties(instance, systemId, systemProperties) >= 0) {
			VrDiagnostics.update { it.copy(system = systemProperties.systemNameString()) }
		}
		VrDiagnostics.update {
			it.copy(features = listOfNotNull(
				"curved screen".takeIf { supportsCylinder },
				"refresh rate control".takeIf { supportsRefreshRate },
				"Steam Frame controllers".takeIf { supportsFrameController },
			))
		}

		// Mandatory before creating an OpenGL session
		val requirements = XrGraphicsRequirementsOpenGLKHR.calloc(stack).`type$Default`()
		xrCheck(KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR(instance, systemId, requirements), "xrGetOpenGLGraphicsRequirementsKHR")

		blendMode = selectBlendMode(stack, instance)
		createSession(stack, instance, api)
		createSpaces(stack)
		createActions(stack, instance)
		createTheater(instance)
		createScreen(instance)
	}

	private fun createTheater(instance: XrInstance) {
		if (settings.environment != CinemaEnvironment.THEATER || blendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE) return
		theater = try {
			TheaterRenderer(instance, systemId, requireNotNull(session), requireNotNull(localSpace))
		} catch (err: Exception) {
			// A missing environment is no reason to stop the movie
			logger.warn("Theater environment unavailable, using a black void", err)
			null
		}
	}

	/**
	 * A curved screen needs XR_KHR_composition_layer_cylinder or our own projection layer. The projection layer is
	 * also used for flat screens when FELLYJIN_SCREEN=projection is set (for comparison on a device).
	 */
	private fun createScreen(instance: XrInstance) {
		val forced = appEnv("SCREEN")?.lowercase() == "projection"
		if (!forced && (!settings.curved || supportsCylinder)) return
		screen = try {
			ScreenRenderer(instance, systemId, requireNotNull(session), requireNotNull(localSpace))
		} catch (err: Exception) {
			logger.warn("Curved screen unavailable, using a flat screen", err)
			null
		}
		logger.info("Screen: ${if (screen != null) "curved, drawn by the app" else "flat quad layer"}")
		if (screen != null) VrDiagnostics.update { it.copy(features = it.features + "curved screen (drawn by Fellyjin)") }
	}

	/** Refresh rates the headset offers, empty when the runtime doesn't let apps choose. */
	private var availableRates = emptyList<Float>()
	private var requestedRate = 0f

	/** Read the selectable refresh rates (XR_FB_display_refresh_rate) and request the initial one. */
	private fun initRefreshRates(stack: MemoryStack) {
		val session = session ?: return
		if (!supportsRefreshRate) {
			logger.info("Runtime does not expose XR_FB_display_refresh_rate, the refresh rate follows the SteamVR setting")
			return
		}
		val count = stack.mallocInt(1)
		if (FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, count, null) < 0 || count[0] == 0) return
		val rates = stack.mallocFloat(count[0])
		if (FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, count, rates) < 0) return
		availableRates = (0 until count[0]).map { rates[it] }.sorted()
		// Until the video frame rate is known: the highest rate for maximum, 90 Hz class otherwise
		requestRefreshRate(if (settings.refreshMode == RefreshMode.MAXIMUM) availableRates.last() else chooseRate(null))
	}

	/**
	 * Energy saving picks the lowest rate of at least 90 Hz that is a whole multiple of the video frame rate (24p -> 120
	 * Hz, 30p -> 90 Hz, 60p -> 120 Hz), so frames are paced evenly without rendering more often than useful.
	 */
	private fun chooseRate(videoFps: Double?): Float {
		val candidates = availableRates.filter { it >= MIN_COMFORT_RATE }.ifEmpty { availableRates }
		if (videoFps == null || videoFps <= 0) return candidates.first()
		return candidates.firstOrNull { rate ->
			val multiple = rate / videoFps
			multiple >= 1 && kotlin.math.abs(multiple - kotlin.math.round(multiple)) < 0.02
		} ?: candidates.last()
	}

	private fun requestRefreshRate(rate: Float) {
		val session = session ?: return
		if (availableRates.isEmpty() || rate == requestedRate) return
		val result = FBDisplayRefreshRate.xrRequestDisplayRefreshRateFB(session, rate)
		requestedRate = rate
		logger.info("Display refresh rates $availableRates, requested $rate Hz (result $result)")
		VrDiagnostics.update {
			it.copy(refreshRate = "${rate.toInt()} Hz (${settings.refreshMode.label}; available: ${availableRates.joinToString { r -> r.toInt().toString() }})")
		}
	}

	private fun availableExtensions(stack: MemoryStack): Set<String> {
		val count = stack.mallocInt(1)
		xrCheck(xrEnumerateInstanceExtensionProperties(null as CharSequence?, count, null), "xrEnumerateInstanceExtensionProperties")
		val properties = XrExtensionProperties.calloc(count[0], stack)
		properties.forEach { it.`type$Default`() }
		xrCheck(xrEnumerateInstanceExtensionProperties(null as CharSequence?, count, properties), "xrEnumerateInstanceExtensionProperties")
		return properties.map { it.extensionNameString() }.toSet()
	}

	private fun selectBlendMode(stack: MemoryStack, instance: XrInstance): Int {
		val count = stack.mallocInt(1)
		xrCheck(xrEnumerateEnvironmentBlendModes(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null), "xrEnumerateEnvironmentBlendModes")
		val modes = stack.mallocInt(count[0])
		xrCheck(xrEnumerateEnvironmentBlendModes(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, modes), "xrEnumerateEnvironmentBlendModes")
		val available = (0 until count[0]).map { modes[it] }

		return when {
			settings.environment == CinemaEnvironment.PASSTHROUGH && XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND in available -> XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND
			XR_ENVIRONMENT_BLEND_MODE_OPAQUE in available -> XR_ENVIRONMENT_BLEND_MODE_OPAQUE
			else -> available.first()
		}
	}

	private fun createSession(stack: MemoryStack, instance: XrInstance, api: GlApi) {
		val binding = if (api == GlApi.EGL) eglBinding(stack) else glxBinding(stack)
		val createInfo = XrSessionCreateInfo.calloc(stack)
			.`type$Default`()
			.next(binding)
			.systemId(systemId)

		val sessionPtr = stack.mallocPointer(1)
		xrCheck(xrCreateSession(instance, createInfo, sessionPtr), "xrCreateSession")
		session = XrSession(sessionPtr[0], instance)
	}

	private fun eglBinding(stack: MemoryStack): Long {
		val config = stack.mallocPointer(1)
		if (!glfwGetEGLConfig(window, config)) throw XrException("Unable to query the EGL config")
		val getProcAddress = com.sun.jna.NativeLibrary.getInstance("EGL").getFunction("eglGetProcAddress")
		return XrGraphicsBindingEGLMNDX.calloc(stack)
			.`type$Default`()
			.getProcAddress(com.sun.jna.Pointer.nativeValue(getProcAddress))
			.display(glfwGetEGLDisplay())
			.config(config[0])
			.context(glfwGetEGLContext(window))
			.address()
	}

	private fun glxBinding(stack: MemoryStack): Long {
		val display = glfwGetX11Display()
		val fbConfig = stack.mallocPointer(1)
		if (!glfwGetGLXFBConfig(window, fbConfig)) throw XrException("Unable to query the GLX framebuffer config")
		val visual = glXGetVisualFromFBConfig(display, fbConfig[0]) ?: throw XrException("No X visual for the GLX config")

		val binding = XrGraphicsBindingOpenGLXlibKHR.calloc(stack)
			.`type$Default`()
			.xDisplay(display)
			.visualid(visual.visualid().toInt())
			.glxFBConfig(fbConfig[0])
			.glxDrawable(glXGetCurrentDrawable())
			.glxContext(glfwGetGLXContext(window))
		return binding.address()
	}

	private fun createSpaces(stack: MemoryStack) {
		val session = requireNotNull(session)
		fun create(type: Int): XrSpace {
			val createInfo = XrReferenceSpaceCreateInfo.calloc(stack)
				.`type$Default`()
				.referenceSpaceType(type)
				.poseInReferenceSpace(identityPose(stack))
			val ptr = stack.mallocPointer(1)
			xrCheck(xrCreateReferenceSpace(session, createInfo, ptr), "xrCreateReferenceSpace")
			return XrSpace(ptr[0], session)
		}
		localSpace = create(XR_REFERENCE_SPACE_TYPE_LOCAL)
		viewSpace = create(XR_REFERENCE_SPACE_TYPE_VIEW)
	}

	private fun createActions(stack: MemoryStack, instance: XrInstance) {
		val session = requireNotNull(session)
		val setPtr = stack.mallocPointer(1)
		xrCheck(
			xrCreateActionSet(
				instance,
				XrActionSetCreateInfo.calloc(stack)
					.`type$Default`()
					.actionSetName(stack.UTF8("cinema"))
					.localizedActionSetName(stack.UTF8("Cinema"))
					.priority(0),
				setPtr,
			),
			"xrCreateActionSet",
		)
		val set = XrActionSet(setPtr[0], instance)
		actionSet = set

		fun action(id: String, label: String, type: Int): XrAction {
			val ptr = stack.mallocPointer(1)
			xrCheck(
				xrCreateAction(
					set,
					XrActionCreateInfo.calloc(stack)
						.`type$Default`()
						.actionName(stack.UTF8(id))
						.localizedActionName(stack.UTF8(label))
						.actionType(type),
					ptr,
				),
				"xrCreateAction($id)",
			)
			return XrAction(ptr[0], set)
		}

		CinemaAction.entries.forEach { actions[it] = action(it.id, it.label, XR_ACTION_TYPE_BOOLEAN_INPUT) }
		val stick = action("seek_and_distance", "Seek and screen distance", XR_ACTION_TYPE_VECTOR2F_INPUT)
		stickAction = stick

		val left = "/user/hand/left/input"
		val right = "/user/hand/right/input"
		val profiles = buildMap {
			// Native Steam Frame controllers: right hand A/B/X/Y + Menu, left hand D-pad + View, bumpers on both
			if (supportsFrameController) put(
				FRAME_CONTROLLER_PROFILE,
				listOf(
					CinemaAction.PLAY_PAUSE to "$right/a/click",
					CinemaAction.PLAY_PAUSE to "$right/trigger/click",
					CinemaAction.PLAY_PAUSE to "$left/trigger/click",
					CinemaAction.EXIT to "$right/b/click",
					CinemaAction.EXIT to "$left/view/click",
					CinemaAction.CYCLE_SUBTITLES to "$right/x/click",
					CinemaAction.CYCLE_AUDIO to "$right/y/click",
					CinemaAction.INFO to "$right/menu/click",
					CinemaAction.RECENTER to "$left/thumbstick/click",
					CinemaAction.RECENTER to "$right/thumbstick/click",
					CinemaAction.SEEK_BACK to "$left/dpad_left/click",
					CinemaAction.SEEK_FORWARD to "$left/dpad_right/click",
					CinemaAction.SCREEN_CLOSER to "$left/dpad_up/click",
					CinemaAction.SCREEN_FURTHER to "$left/dpad_down/click",
					CinemaAction.PREVIOUS_CHAPTER to "$left/bumper/click",
					CinemaAction.NEXT_CHAPTER to "$right/bumper/click",
					null to "$left/thumbstick",
					null to "$right/thumbstick",
				),
			)
			put("/interaction_profiles/khr/simple_controller", listOf(
				CinemaAction.PLAY_PAUSE to "$left/select/click",
				CinemaAction.PLAY_PAUSE to "$right/select/click",
				CinemaAction.EXIT to "$left/menu/click",
				CinemaAction.EXIT to "$right/menu/click",
			))
			put("/interaction_profiles/valve/index_controller", listOf(
				CinemaAction.PLAY_PAUSE to "$right/a/click",
				CinemaAction.PLAY_PAUSE to "$right/trigger/click",
				CinemaAction.PLAY_PAUSE to "$left/trigger/click",
				CinemaAction.EXIT to "$right/b/click",
				CinemaAction.CYCLE_SUBTITLES to "$left/a/click",
				CinemaAction.CYCLE_AUDIO to "$left/b/click",
				CinemaAction.RECENTER to "$left/thumbstick/click",
				CinemaAction.RECENTER to "$right/thumbstick/click",
				null to "$left/thumbstick",
				null to "$right/thumbstick",
			))
			put("/interaction_profiles/oculus/touch_controller", listOf(
				CinemaAction.PLAY_PAUSE to "$right/a/click",
				CinemaAction.PLAY_PAUSE to "$right/trigger/value",
				CinemaAction.PLAY_PAUSE to "$left/trigger/value",
				CinemaAction.EXIT to "$right/b/click",
				CinemaAction.EXIT to "$left/menu/click",
				CinemaAction.CYCLE_SUBTITLES to "$left/x/click",
				CinemaAction.CYCLE_AUDIO to "$left/y/click",
				CinemaAction.RECENTER to "$left/thumbstick/click",
				CinemaAction.RECENTER to "$right/thumbstick/click",
				null to "$left/thumbstick",
				null to "$right/thumbstick",
			))
		}

		for ((profile, bindings) in profiles) {
			val suggested = XrActionSuggestedBinding.calloc(bindings.size, stack)
			bindings.forEachIndexed { index, (action, path) ->
				suggested[index].action(if (action == null) stick else actions.getValue(action)).binding(path(stack, instance, path))
			}
			val result = xrSuggestInteractionProfileBindings(
				instance,
				XrInteractionProfileSuggestedBinding.calloc(stack)
					.`type$Default`()
					.interactionProfile(path(stack, instance, profile))
					.suggestedBindings(suggested),
			)
			if (result < 0) logger.warn("Runtime rejected bindings for $profile: $result")
		}

		xrCheck(
			xrAttachSessionActionSets(
				session,
				XrSessionActionSetsAttachInfo.calloc(stack).`type$Default`().actionSets(stack.pointers(set)),
			),
			"xrAttachSessionActionSets",
		)
	}

	private fun path(stack: MemoryStack, instance: XrInstance, value: String): Long {
		val ptr = stack.mallocLong(1)
		xrCheck(xrStringToPath(instance, value, ptr), "xrStringToPath($value)")
		return ptr[0]
	}

	// endregion

	// region VideoOutput

	override fun mpvOptions() = buildMap {
		put("vo", "libmpv")
		put("osc", "no")
		put("osd-level", "1")
		// Keep the OSD readable on a screen that is meters wide
		put("osd-scale-by-window", "yes")
		// The swapchain is sRGB: let mpv convert from BT.1886 (video) to sRGB so shadows look as mastered, and
		// tone map HDR down to SDR
		put("target-prim", "bt.709")
		put("target-trc", "srgb")
		put("dither-depth", "8")
		put("vd-lavc-dr", "yes")
		when (settings.quality) {
			PictureQuality.HIGH -> {
				// Separable scalers keep a 1080p to 4K upscale within a couple of milliseconds on mobile GPUs
				put("scale", "spline36")
				put("cscale", "spline36")
				put("dscale", "mitchell")
				put("deband", "yes")
				put("deband-iterations", "2")
				// Subtle grain hides the deband steps without the noisy look of the default strength
				put("deband-grain", "16")
				// Fewer halos around edges when upscaling, correct gamma when downscaling
				put("sigmoid-upscaling", "yes")
				put("scale-antiring", "0.6")
				put("linear-downscaling", "yes")
				put("correct-downscaling", "yes")
			}

			PictureQuality.BALANCED -> {
				put("scale", "bilinear")
				put("cscale", "bilinear")
				put("deband", "no")
			}
		}
	}

	override fun attach(player: MpvPlayer) {
		// mpv renders on its own thread and shared context, so a slow (4K) frame never stalls the 144 Hz frame loop
		videoThread = VideoRenderThread(player, window, FLIP_Y, egl = glApi == GlApi.EGL)
	}

	override fun onStreamLoaded(player: MpvPlayer, stream: ResolvedStream) {
		if (settings.refreshMode == RefreshMode.ENERGY_SAVING) {
			val fps = player.getProperty("container-fps")?.toDoubleOrNull()
			requestRefreshRate(chooseRate(fps))
		}
		stereoLayout = stream.stereoLayout
		if (stereoLayout != StereoLayout.MONO) player.command("show-text", "3D: ${stereoLayout.name.lowercase().replace('_', ' ')}", "3000")
	}

	override fun onVideoReconfig(player: MpvPlayer) {
		val (videoWidth, videoHeight) = player.videoSize ?: return
		// Balanced: native size. High: let mpv upscale to 4K width so the compositor gets a sharp source.
		val target = settings.quality.maxTextureWidth.takeIf { it > videoWidth } ?: videoWidth
		val scale = minOf(target.toFloat() / videoWidth, MAX_TEXTURE_SIZE.toFloat() / maxOf(videoWidth, videoHeight))
		val width = (videoWidth * scale).toInt().coerceAtLeast(16)
		val height = (videoHeight * scale).toInt().coerceAtLeast(16)
		tuneForResolution(player, videoWidth)
		screen?.let { screen ->
			// The video goes into the screen's own mipmapped texture, no video swapchain needed
			screenWidthPx = width
			screenHeightPx = height
			screen.resize(width, height)
			videoThread?.setTargetSize(width, height)
			return
		}
		val current = video
		if (current != null && current.width == width && current.height == height) return

		current?.close()
		video = XrSwapchainImages.create(requireNotNull(session), width, height, "video")
		videoThread?.setTargetSize(width, height)
	}

	/**
	 * 4K sources are sharp and rarely band, but debanding, high quality chroma scaling and HDR peak detection at 4K
	 * cost the Steam Frame's GPU several milliseconds per frame. Use the cheap paths for them, the full chain for
	 * everything below.
	 */
	private fun tuneForResolution(player: MpvPlayer, videoWidth: Int) {
		val uhd = videoWidth >= 2560
		val high = settings.quality == PictureQuality.HIGH && !uhd
		player.trySetProperty("deband", if (high) "yes" else "no")
		player.trySetProperty("cscale", if (high) "spline36" else "bilinear")
		player.trySetProperty("scale", if (high) "spline36" else "bilinear")
		player.trySetProperty("hdr-compute-peak", if (uhd) "no" else "auto")
		player.trySetProperty("sigmoid-upscaling", if (high) "yes" else "no")
		logger.info("Video ${videoWidth}px wide, ${if (high) "high quality" else "fast"} render path")
	}

	override fun frame(player: MpvPlayer): Boolean {
		pollXrEvents()
		if (exitRequested) return false

		val session = session ?: return false
		if (!sessionRunning) {
			// Not visible yet (or anymore), avoid spinning while waiting for the session state to change
			Thread.sleep(10)
			return true
		}

		stackPush().use { stack ->
			val frameState = XrFrameState.calloc(stack).`type$Default`()
			xrCheck(xrWaitFrame(session, XrFrameWaitInfo.calloc(stack).`type$Default`(), frameState), "xrWaitFrame")
			xrCheck(xrBeginFrame(session, XrFrameBeginInfo.calloc(stack).`type$Default`()), "xrBeginFrame")

			val displayTime = frameState.predictedDisplayTime()
			updateDisplayRate(player, frameState.predictedDisplayPeriod())
			if (sessionFocused) handleInput(stack, player)
			if (recenterPending) recenter(stack, displayTime)
			updateHouseLights(player, displayTime)

			if (videoThread?.hasNewFrame == true) {
				if (screen != null) copyVideoToScreen() else if (video != null) copyVideo(stack)
			}
			pendingOsd?.let {
				player.command("show-text", it, "4000")
				pendingOsd = null
			}

			val layers = stack.mallocPointer(3)
			if (frameState.shouldRender()) {
				val views = if (theater != null || screen != null) locateViews(stack, displayTime) else null
				if (views != null) theater?.let { layers.put(it.render(stack, views, room)) }
				val screen = screen
				if (screen != null) {
					if (screen.hasContent && views != null) {
						updateRoom(displayAspect(screenWidthPx, screenHeightPx))
						val rects = eyeRects(screenWidthPx, screenHeightPx).map { r ->
							floatArrayOf(
								r.x / screenWidthPx.toFloat(), r.y / screenHeightPx.toFloat(),
								(r.x + r.width) / screenWidthPx.toFloat(), (r.y + r.height) / screenHeightPx.toFloat(),
							)
						}
						layers.put(screen.render(stack, views, room, curved = settings.curved, eyeRects = rects))
					}
				} else if (video?.hasContent == true) {
					addVideoLayers(stack, layers)
				}
			}
			layers.flip()

			xrCheck(
				xrEndFrame(
					session,
					XrFrameEndInfo.calloc(stack)
						.`type$Default`()
						.displayTime(displayTime)
						.environmentBlendMode(blendMode)
						.layers(if (layers.remaining() > 0) layers else null),
				),
				"xrEndFrame",
			)
		}

		return !exitRequested
	}

	/**
	 * Tell mpv the real display rate (e.g. 144 Hz) so it can pick frames with the right cadence. 24 fps divides 144 Hz
	 * evenly (6 refreshes per frame), so films play without judder.
	 */
	private fun updateDisplayRate(player: MpvPlayer, periodNanos: Long) {
		if (periodNanos <= 0) return
		val hz = 1_000_000_000f / periodNanos
		if (kotlin.math.abs(hz - displayHz) < 1f) return
		displayHz = hz
		val value = "%.3f".format(java.util.Locale.ROOT, hz)
		// Renamed in mpv 0.38, try both
		if (!player.trySetProperty("display-fps-override", value)) player.trySetProperty("override-display-fps", value)
		logger.info("Display running at %.1f Hz".format(hz))
		if (!supportsRefreshRate) VrDiagnostics.update { it.copy(refreshRate = "%.0f Hz (set in SteamVR)".format(hz)) }
	}

	/** Dim the house lights while the movie plays and bring them up when paused, like a real theater. */
	private fun updateHouseLights(player: MpvPlayer, displayTime: Long) {
		if (lastPausePoll.elapsedNow() >= 100.milliseconds) {
			lastPausePoll = TimeSource.Monotonic.markNow()
			paused = player.isPaused || player.getProperty("paused-for-cache") == "yes" || !hasVideo
		}
		val dt = if (lastFrameTime == 0L) 0f else ((displayTime - lastFrameTime) / 1e9f).coerceIn(0f, 0.1f)
		lastFrameTime = displayTime
		val house = if (paused) 1f else 0f
		// Lights dim over ~3 seconds and come up a bit faster, glow fades in with the first frames
		val houseRate = if (house > room.houseLights) 1.2f else 0.6f
		room.houseLights += (house - room.houseLights) * (1f - kotlin.math.exp(-houseRate * dt))
		val glow = if (hasVideo) 1f else 0f
		room.screenGlow += (glow - room.screenGlow) * (1f - kotlin.math.exp(-1.5f * dt))
	}

	private fun locateViews(stack: MemoryStack, displayTime: Long): XrView.Buffer? {
		val views = XrView.calloc(2, stack)
		views.forEach { it.`type$Default`() }
		val count = stack.mallocInt(1)
		val viewState = XrViewState.calloc(stack).`type$Default`()
		val result = xrLocateViews(
			session ?: return null,
			XrViewLocateInfo.calloc(stack)
				.`type$Default`()
				.viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
				.displayTime(displayTime)
				.space(localSpace ?: return null),
			viewState,
			count,
			views,
		)
		// When tracking is lost the poses are undefined: skip the projection layers rather than show garbage
		val valid = (XR_VIEW_STATE_ORIENTATION_VALID_BIT or XR_VIEW_STATE_POSITION_VALID_BIT).toLong()
		return if (result >= 0 && count[0] == 2 && viewState.viewStateFlags() and valid == valid) views else null
	}

	override fun close(player: MpvPlayer?) {
		// Stops the render thread, which frees mpv's render context on its own OpenGL context
		videoThread?.close()
		videoThread = null
		destroy()
	}

	// endregion

	// region Frame loop

	private fun pollXrEvents(): Unit = stackPush().use { stack ->
		val instance = instance ?: return
		val buffer = XrEventDataBuffer.calloc(stack)
		while (true) {
			buffer.`type$Default`().next(NULL)
			val result = xrPollEvent(instance, buffer)
			if (result == XR_EVENT_UNAVAILABLE || result < 0) break

			when (buffer.type()) {
				XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING -> exitRequested = true
				XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED -> reportInteractionProfiles(stack)
				XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
					val state = XrEventDataSessionStateChanged.create(buffer.address()).state()
					onSessionStateChanged(stack, state)
				}
			}
		}
	}

	/** Log and show which controller profile SteamVR picked, so missing bindings are visible in the headset. */
	private fun reportInteractionProfiles(stack: MemoryStack) {
		val instance = instance ?: return
		val session = session ?: return
		val profiles = listOf("left", "right").associateWith { hand ->
			val state = XrInteractionProfileState.calloc(stack).`type$Default`()
			if (xrGetCurrentInteractionProfile(session, path(stack, instance, "/user/hand/$hand"), state) < 0) return@associateWith null
			state.interactionProfile().takeIf { it != NULL }?.let { pathToString(stack, instance, it) }
		}
		logger.info("Active controller profiles: $profiles")
		VrDiagnostics.update { it.copy(controller = profiles.values.filterNotNull().distinct().joinToString().ifEmpty { "no bindings active" }) }
		val active = profiles.values.filterNotNull().distinct()
		pendingOsd = when {
			active.isEmpty() -> "No controller bindings active: check SteamVR controller bindings"
			else -> "Controller: " + active.joinToString { profile ->
				when {
					profile.endsWith("frame_controller_valve") -> "Steam Frame"
					profile.endsWith("touch_controller") -> "Touch (emulated)"
					profile.endsWith("index_controller") -> "Index"
					else -> profile.substringAfterLast('/')
				}
			}
		}
	}

	private fun pathToString(stack: MemoryStack, instance: XrInstance, path: Long): String {
		val count = stack.mallocInt(1)
		if (xrPathToString(instance, path, count, null) < 0) return path.toString()
		val buffer = stack.malloc(count[0])
		if (xrPathToString(instance, path, count, buffer) < 0) return path.toString()
		return org.lwjgl.system.MemoryUtil.memUTF8(buffer, count[0] - 1)
	}

	private fun onSessionStateChanged(stack: MemoryStack, state: Int) {
		val session = session ?: return
		logger.info("OpenXR session state $state")
		sessionFocused = state == XR_SESSION_STATE_FOCUSED
		when (state) {
			XR_SESSION_STATE_READY -> {
				xrCheck(
					xrBeginSession(
						session,
						XrSessionBeginInfo.calloc(stack).`type$Default`().primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO),
					),
					"xrBeginSession",
				)
				sessionRunning = true
				initRefreshRates(stack)
			}

			XR_SESSION_STATE_STOPPING -> {
				xrEndSession(session)
				sessionRunning = false
			}

			XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> {
				sessionRunning = false
				exitRequested = true
			}
		}
	}

	/** Copy the newest frame from the render thread into the video swapchain (a GPU blit, well under 1 ms). */
	private fun copyVideo(stack: MemoryStack) {
		val video = video ?: return
		val thread = videoThread ?: return
		video.render(stack) { fbo ->
			thread.copyLatest(fbo, video.width, video.height) { source, width, height ->
				// Light the theater with this frame (left eye only for 3D)
				theater?.let {
					val rect = eyeRects(width, height).first()
					it.captureScreen(source, rect.x, rect.y, rect.width, rect.height)
				}
			}
		}
	}

	private data class EyeRect(val visibility: Int, val x: Int, val y: Int, val width: Int, val height: Int)

	/** Rect per eye in the swapchain image (OpenGL: origin bottom left, so the top half has offset y = h / 2). */
	private fun eyeRects(w: Int, h: Int): List<EyeRect> = when (stereoLayout) {
		StereoLayout.MONO -> listOf(EyeRect(XR_EYE_VISIBILITY_BOTH, 0, 0, w, h))
		StereoLayout.SIDE_BY_SIDE_HALF, StereoLayout.SIDE_BY_SIDE_FULL -> listOf(
			EyeRect(XR_EYE_VISIBILITY_LEFT, 0, 0, w / 2, h),
			EyeRect(XR_EYE_VISIBILITY_RIGHT, w / 2, 0, w / 2, h),
		)

		StereoLayout.TOP_BOTTOM_HALF, StereoLayout.TOP_BOTTOM_FULL -> listOf(
			EyeRect(XR_EYE_VISIBILITY_LEFT, 0, h / 2, w, h / 2),
			EyeRect(XR_EYE_VISIBILITY_RIGHT, 0, 0, w, h / 2),
		)
	}

	/** Aspect ratio of the picture as it should be displayed, for a (possibly stereo packed) frame of w x h. */
	private fun displayAspect(w: Int, h: Int): Float = when (stereoLayout) {
		StereoLayout.MONO, StereoLayout.SIDE_BY_SIDE_HALF, StereoLayout.TOP_BOTTOM_HALF -> w.toFloat() / h
		StereoLayout.SIDE_BY_SIDE_FULL -> (w / 2f) / h
		StereoLayout.TOP_BOTTOM_FULL -> w / (h / 2f)
	}

	/** Copy the newest frame into the curved screen's texture (and light the theater with it). */
	private fun copyVideoToScreen() {
		val screen = screen ?: return
		val thread = videoThread ?: return
		screen.update { fbo, width, height ->
			thread.copyLatest(fbo, width, height) { source, w, h ->
				theater?.let {
					val rect = eyeRects(w, h).first()
					it.captureScreen(source, rect.x, rect.y, rect.width, rect.height)
				}
			}
		}
	}

	private fun addVideoLayers(stack: MemoryStack, layers: PointerBuffer) {
		val video = requireNotNull(video)
		val swapchain = video.swapchain
		val localSpace = requireNotNull(localSpace)
		val w = video.width
		val h = video.height
		val rects = eyeRects(w, h)

		val aspect = displayAspect(w, h)
		updateRoom(aspect)

		val orientation = yawQuaternion(screenYaw)
		for (rect in rects) {
			val layer = if (supportsCylinder && settings.curved) {
				// The cylinder is centered on the viewer, the picture faces its center on the -Z axis
				XrCompositionLayerCylinderKHR.calloc(stack)
					.`type$Default`()
					.space(localSpace)
					.eyeVisibility(rect.visibility)
					.subImage { sub ->
						sub.swapchain(swapchain).imageArrayIndex(0).imageRect { r ->
							r.offset { it.x(rect.x).y(rect.y) }.extent { it.width(rect.width).height(rect.height) }
						}
					}
					.pose { pose ->
						pose.orientation { it.x(orientation[0]).y(orientation[1]).z(orientation[2]).w(orientation[3]) }
						pose.`position$` { it.x(screenX).y(screenY).z(screenZ) }
					}
					.radius(distance)
					.centralAngle((screenWidth / distance).coerceAtMost(MAX_CENTRAL_ANGLE))
					.aspectRatio(aspect)
					.address()
			} else {
				val centerX = screenX - distance * sin(screenYaw)
				val centerZ = screenZ - distance * cos(screenYaw)
				XrCompositionLayerQuad.calloc(stack)
					.`type$Default`()
					.space(localSpace)
					.eyeVisibility(rect.visibility)
					.subImage { sub ->
						sub.swapchain(swapchain).imageArrayIndex(0).imageRect { r ->
							r.offset { it.x(rect.x).y(rect.y) }.extent { it.width(rect.width).height(rect.height) }
						}
					}
					.pose { pose ->
						pose.orientation { it.x(orientation[0]).y(orientation[1]).z(orientation[2]).w(orientation[3]) }
						pose.`position$` { it.x(centerX).y(screenY).z(centerZ) }
					}
					.size { it.width(screenWidth).height(screenWidth / aspect) }
					.address()
			}
			layers.put(layer)
		}
	}

	private fun updateRoom(aspect: Float) {
		room.originX = screenX
		room.originY = viewerY
		room.originZ = screenZ
		room.yaw = screenYaw
		room.distance = distance
		room.screenWidth = screenWidth
		room.screenHeight = screenWidth / aspect
		room.verticalOffset = settings.verticalOffsetMeters
	}

	// endregion

	// region Input

	private fun handleInput(stack: MemoryStack, player: MpvPlayer) {
		val session = session ?: return
		val set = actionSet ?: return
		val sync = XrActionsSyncInfo.calloc(stack)
			.`type$Default`()
			.activeActionSets(XrActiveActionSet.calloc(1, stack).actionSet(set).subactionPath(NULL))
		if (xrSyncActions(session, sync) < 0) return

		fun pressed(action: CinemaAction): Boolean {
			val state = XrActionStateBoolean.calloc(stack).`type$Default`()
			val info = XrActionStateGetInfo.calloc(stack).`type$Default`().action(actions.getValue(action))
			if (xrGetActionStateBoolean(session, info, state) < 0) return false
			return state.isActive && state.changedSinceLastSync() && state.currentState()
		}

		if (pressed(CinemaAction.PLAY_PAUSE)) {
			player.commandString("cycle pause")
			player.commandString("show-progress")
		}
		if (pressed(CinemaAction.EXIT)) exitRequested = true
		if (pressed(CinemaAction.CYCLE_SUBTITLES)) player.commandString("osd-msg cycle sub")
		if (pressed(CinemaAction.CYCLE_AUDIO)) player.commandString("osd-msg cycle audio")
		if (pressed(CinemaAction.RECENTER)) recenterPending = true
		if (pressed(CinemaAction.INFO)) {
			// Media info card for video fans: format, HDR, audio and how well playback keeps up
			player.commandString("show-progress")
			player.commandString(MEDIA_INFO_COMMAND)
		}
		if (pressed(CinemaAction.SEEK_BACK)) player.commandString("osd-msg-bar seek -10")
		if (pressed(CinemaAction.SEEK_FORWARD)) player.commandString("osd-msg-bar seek 30")
		if (pressed(CinemaAction.SCREEN_CLOSER)) distance = (distance * 0.9f).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
		if (pressed(CinemaAction.SCREEN_FURTHER)) distance = (distance / 0.9f).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
		if (pressed(CinemaAction.PREVIOUS_CHAPTER)) player.commandString("osd-msg-bar add chapter -1")
		if (pressed(CinemaAction.NEXT_CHAPTER)) player.commandString("osd-msg-bar add chapter 1")

		val stick = stickAction ?: return
		val stickState = XrActionStateVector2f.calloc(stack).`type$Default`()
		if (xrGetActionStateVector2f(session, XrActionStateGetInfo.calloc(stack).`type$Default`().action(stick), stickState) < 0) return
		if (!stickState.isActive) return
		val x = stickState.currentState().x()
		val y = stickState.currentState().y()

		// Horizontal: seek, repeats while held and speeds up after a while
		if (abs(x) > 0.6f && abs(x) > abs(y)) {
			val now = TimeSource.Monotonic.markNow()
			val heldSince = seekHeldSince ?: now.also { seekHeldSince = it }
			val last = lastSeek
			if (last == null || last.elapsedNow() >= 400.milliseconds) {
				val step = if (heldSince.elapsedNow() > 2.seconds) 60 else 10
				player.commandString("osd-msg-bar seek ${(sign(x) * step).toInt()}")
				lastSeek = now
			}
		} else {
			seekHeldSince = null
			lastSeek = null
		}

		// Vertical: move the screen closer (up) or further away (down)
		if (abs(y) > 0.3f && abs(y) > abs(x)) {
			distance = (distance * (1f - y * 0.015f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
		}
	}

	/** Place the screen in front of the viewer, using only the head yaw so the screen stays upright. */
	private fun recenter(stack: MemoryStack, time: Long) {
		val location = XrSpaceLocation.calloc(stack).`type$Default`()
		if (xrLocateSpace(viewSpace ?: return, localSpace ?: return, time, location) < 0) return
		val valid = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT.toLong() or XR_SPACE_LOCATION_POSITION_VALID_BIT.toLong()
		if (location.locationFlags() and valid != valid) return

		val pose = location.pose()
		val q = pose.orientation()
		screenYaw = atan2(2f * (q.w() * q.y() + q.x() * q.z()), 1f - 2f * (q.y() * q.y() + q.x() * q.x()))
		screenX = pose.`position$`().x()
		viewerY = pose.`position$`().y()
		screenY = viewerY + settings.verticalOffsetMeters
		screenZ = pose.`position$`().z()
		recenterPending = false
	}

	// endregion


	private fun destroy() {
		session?.let { session ->
			if (sessionRunning) {
				// Give the runtime a moment to transition to STOPPING so the session ends cleanly
				xrRequestExitSession(session)
				val deadline = TimeSource.Monotonic.markNow()
				while (sessionRunning && deadline.elapsedNow() < 2.seconds) {
					pollXrEvents()
					Thread.sleep(5)
				}
			}
		}

		if (window != NULL) {
			theater?.close()
			screen?.close()
			video?.close()
		}
		theater = null
		screen = null
		video = null
		actions.values.forEach(::xrDestroyAction)
		actions.clear()
		stickAction?.let(::xrDestroyAction)
		stickAction = null
		actionSet?.let(::xrDestroyActionSet)
		actionSet = null
		viewSpace?.let(::xrDestroySpace)
		localSpace?.let(::xrDestroySpace)
		viewSpace = null
		localSpace = null
		session?.let(::xrDestroySession)
		session = null
		instance?.let(::xrDestroyInstance)
		instance = null

		if (window != NULL) {
			glfwMakeContextCurrent(NULL)
			glfwDestroyWindow(window)
			window = NULL
		}
		if (glfwInitialized) glfwTerminate()
		glfwInitialized = false
		errorCallback?.free()
		errorCallback = null
	}

	private fun identityPose(stack: MemoryStack): XrPosef = XrPosef.calloc(stack).apply {
		orientation().w(1f)
	}

	/** Quaternion (x, y, z, w) for a rotation of [yaw] radians around the Y (up) axis. */
	private fun yawQuaternion(yaw: Float) = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))

	internal companion object {
		const val MAX_TEXTURE_SIZE = 4096
		const val FRAME_CONTROLLER_EXTENSION = "XR_VALVE_frame_controller_interaction"
		const val FRAME_CONTROLLER_PROFILE = "/interaction_profiles/valve/frame_controller_valve"
		const val MIN_DISTANCE = 1.5f
		/** Below this head tracking feels less smooth, energy saving never goes lower. */
		const val MIN_COMFORT_RATE = 89.5f
		const val MAX_DISTANCE = 60f
		val MAX_CENTRAL_ANGLE = (PI * 0.95).toFloat()

		/** Override with FELLYJIN_XR_FLIP_Y=0 if a runtime shows the video upside down. */
		val FLIP_Y = appEnv("XR_FLIP_Y") != "0"

		@Volatile
		var loaderCreated = false

		/**
		 * Load the OpenXR loader once. LWJGL already loads it when the XR class initializes (unless explicit init is
		 * configured), so a second XR.create() throws "OpenXR has already been created": treat that as success.
		 */
		@Synchronized
		internal fun ensureLoader() {
			if (loaderCreated) return
			appEnv("OPENXR_LOADER")?.let { Configuration.OPENXR_LIBRARY_NAME.set(it) }
			try {
				XR.create()
			} catch (err: IllegalStateException) {
				if (err.message?.contains("already been created") != true) throw err
			}
			loaderCreated = true
		}
	}
}

/** mpv expands the ${...} properties when the text is shown. */
internal const val MEDIA_INFO_COMMAND = "show-text \"\${media-title}\\n" +
	"\${video-format} \${width}x\${height} at \${container-fps} fps   \${video-params/primaries} \${video-params/gamma}\\n" +
	"\${audio-codec-name} \${audio-params/channel-count} channels\\n" +
	"Decoder \${hwdec-current}   dropped frames \${frame-drop-count}   display \${display-fps} Hz\" 6000"
