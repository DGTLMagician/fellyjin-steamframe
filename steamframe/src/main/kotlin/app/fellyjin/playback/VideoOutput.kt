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
 * mpv opens its own fullscreen window with the on screen controller. On SteamOS the window is shown on the
 * virtual desktop screen, so this works without OpenXR.
 */
class FlatWindowOutput : VideoOutput {
	override val name = "Flat window"
	override val eventTimeoutSeconds = 0.25

	override fun mpvOptions() = mapOf(
		"vo" to "gpu-next,gpu",
		"fullscreen" to "yes",
		"force-window" to "immediate",
		"osc" to "yes",
		"input-default-bindings" to "yes",
		"input-vo-keyboard" to "yes",
		"input-gamepad" to "yes",
		// Resample audio slightly to lock video to the display refresh (e.g. 24 fps on 144 Hz), no judder
		"video-sync" to "display-resample",
		"scale" to "spline36",
		"deband" to "yes",
		"title" to "\${media-title} - Fellyjin",
	)

	override fun attach(player: MpvPlayer) {
		// Escape (the B button with the default Steam Input desktop layout) leaves playback
		player.command("keybind", "ESC", "quit")
		player.command("keybind", "BS", "quit")
	}

	override fun frame(player: MpvPlayer) = true

	override fun close(player: MpvPlayer) = Unit
}
