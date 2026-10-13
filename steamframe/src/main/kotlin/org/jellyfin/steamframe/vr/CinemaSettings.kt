package org.jellyfin.steamframe.vr

import kotlinx.serialization.Serializable
import kotlin.math.atan

/**
 * Virtual screen presets. The width and distance are in meters, the field of view is the horizontal angle the
 * screen covers from the viewer's point of view.
 */
@Serializable
enum class ScreenPreset(val label: String, val widthMeters: Float, val distanceMeters: Float) {
	LIVING_ROOM("Living room", 2.2f, 3.0f),
	CINEMA("Cinema", 12.0f, 12.0f),
	HUGE("Huge screen", 24.0f, 15.0f),
	IMAX("IMAX", 30.0f, 14.0f);

	val horizontalFovDegrees: Int
		get() = Math.toDegrees(2.0 * atan(widthMeters / 2.0 / distanceMeters)).toInt()
}

@Serializable
enum class CinemaEnvironment(val label: String) {
	/** A movie theater auditorium lit by the screen, with house lights that dim when the movie starts. */
	THEATER("Movie theater"),

	/** Pitch black surroundings, only the screen is visible. */
	VOID("Black void"),

	/** Show the real room through the headset cameras when the runtime supports alpha blending. */
	PASSTHROUGH("Passthrough"),
}

@Serializable
enum class PictureQuality(val label: String, val maxTextureWidth: Int) {
	/** Video at its native resolution, the compositor scales it. Lowest GPU load. */
	BALANCED("Balanced", 0),

	/** mpv upscales to (up to) 4K with a sharp scaler and debanding before the compositor samples it. */
	HIGH("High", 3840),
}

@Serializable
data class CinemaSettings(
	val screen: ScreenPreset = ScreenPreset.HUGE,
	val curved: Boolean = true,
	val environment: CinemaEnvironment = CinemaEnvironment.THEATER,
	val quality: PictureQuality = PictureQuality.HIGH,
	/** Height offset of the screen center relative to eye level, in meters. */
	val verticalOffsetMeters: Float = 0f,
)
