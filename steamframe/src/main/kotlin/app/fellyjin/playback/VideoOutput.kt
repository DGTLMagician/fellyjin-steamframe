package app.fellyjin.playback

import app.fellyjin.appEnv
import app.fellyjin.mpv.MpvPlayer
import org.slf4j.LoggerFactory

/** Where mpv draws its video: a VR cinema screen or a plain window. All functions run on the playback thread. */
interface VideoOutput {
	val name: String

	/** Seconds to wait for mpv events per loop iteration, 0 for outputs that pace themselves (VR). */
	val eventTimeoutSeconds: Double

	fun mpvOptions(): Map<String, String>

	/** Called once mpv is initialized. */
	fun attach(player: MpvPlayer)

	fun onStreamLoaded(player: MpvPlayer, stream: ResolvedStream) = Unit

	fun onVideoReconfig(player: MpvPlayer) = Unit

	/** Paused or buffering changed (from mpv's property events, no blocking calls). */
	fun onPausedChanged(paused: Boolean) = Unit

	/** Run a single output iteration. Returns false when the user asked to leave playback. */
	fun frame(player: MpvPlayer): Boolean

	/** Release all resources. [player] is null when mpv failed to start; mpv is closed afterwards otherwise. */
	fun close(player: MpvPlayer?)
}

/**
 * Plays in a regular window instead of VR. Preferably mpv draws into a surface inside the Fellyjin window
 * ([VideoHost]), because gamescope only shows one window per app: a separate mpv window plays unseen behind the UI.
 * Without a host (or when embedding fails) mpv opens its own fullscreen window.
 *
 * Controls come from the app (gamepad and keys, see [PlaybackController.handle]), mpv's own input is limited to the
 * keyboard in a separate window.
 */
class FlatWindowOutput(private val host: VideoHost? = null) : VideoOutput {
	private val logger = LoggerFactory.getLogger(FlatWindowOutput::class.java)
	private val windowId: Long? = runCatching { host?.show() }.getOrNull()

	override val name = if (windowId != null) "Window" else "Flat window"

	/** GPU contexts to try in order, FELLYJIN_GPU_CONTEXT forces one. */
	private val contexts = appEnv("GPU_CONTEXT")?.let { listOf(it) } ?: listOf("x11vk", "x11egl")
	private var contextIndex = 0

	/** The video output opens while mpv initializes, so a failing GPU context fails there: try the next one. */
	fun fallBack(): Boolean {
		if (windowId == null || contextIndex >= contexts.lastIndex) return false
		contextIndex++
		logger.warn("Video output failed, retrying with ${contexts[contextIndex]}")
		return true
	}

	val gpuContext: String? get() = if (windowId != null) contexts[contextIndex] else null
	override val eventTimeoutSeconds = 0.05

	override fun mpvOptions() = buildMap {
		put("vo", "gpu-next,gpu")
		put("force-window", "immediate")
		put("osc", "yes")
		// Resample audio slightly to lock video to the display refresh (e.g. 24 fps on 144 Hz), no judder
		put("video-sync", "display-resample")
		put("scale", "spline36")
		put("deband", "yes")
		if (windowId != null) {
			put("wid", windowId.toString())
			// The surface is an X11 window (XWayland under gamescope), keep mpv off its Wayland backend. Vulkan first:
			// it is the Steam Frame's native driver (Turnip), OpenGL there goes through Zink and showed wrong colors.
			val context = contexts[contextIndex]
			put("gpu-context", context)
			put("gpu-api", if (context.endsWith("vk")) "vulkan" else "opengl")
			// Keys come from the app (gamepad and keyboard). The pointer (VR laser) drives mpv's on-screen controller:
			// an auto-hiding bar with seek bar, play/pause, chapters, audio and subtitle tracks and volume.
			put("input-vo-keyboard", "no")
			put("input-default-bindings", "yes")
			put("osc", "yes")
			put("script-opts", OSC_OPTIONS)
		} else {
			put("fullscreen", "yes")
			put("input-default-bindings", "yes")
			put("input-vo-keyboard", "yes")
			put("title", "\${media-title} - Fellyjin")
		}
	}

	override fun attach(player: MpvPlayer) {
		if (windowId == null) {
			// Escape (the B button with the default Steam Input desktop layout) leaves playback
			player.command("keybind", "ESC", "quit")
			player.command("keybind", "BS", "quit")
		}
	}

	override fun frame(player: MpvPlayer) = true

	private companion object {
		/** Large bottom bar, readable on a big virtual screen, hides 3 s after the pointer stops moving. */
		const val OSC_OPTIONS = "osc-layout=bottombar,osc-scalewindowed=2.2,osc-scalefullscreen=2.2,osc-vidscale=no," +
			"osc-hidetimeout=3000,osc-fadeduration=250,osc-seekbarstyle=bar,osc-deadzonesize=0"
	}

	override fun close(player: MpvPlayer?) {
		if (windowId != null) host?.hide()
	}
}
