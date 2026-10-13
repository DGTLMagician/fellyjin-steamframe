package app.fellyjin.playback

import app.fellyjin.mpv.MpvPlayer

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

	/** Run a single output iteration. Returns false when the user asked to leave playback. */
	fun frame(player: MpvPlayer): Boolean

	/** Release all resources, mpv is closed afterwards. */
	fun close(player: MpvPlayer)
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
	private val windowId: Long? = runCatching { host?.show() }.getOrNull()

	override val name = if (windowId != null) "Window" else "Flat window"
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
			// The surface is an X11 window (XWayland under gamescope), keep mpv off its Wayland backend
			put("gpu-context", "x11egl")
			put("input-vo-keyboard", "no")
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

	override fun close(player: MpvPlayer) {
		if (windowId != null) host?.hide()
	}
}
