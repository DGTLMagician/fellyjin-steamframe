package app.fellyjin.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.fellyjin.vr.CinemaSettings
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AppConfig(
	val deviceId: String = UUID.randomUUID().toString(),
	val server: ServerConfig? = null,
	val playback: PlaybackSettings = PlaybackSettings(),
	val cinema: CinemaSettings = CinemaSettings(),
	val controller: ControllerMapping = ControllerMapping(),
)

/**
 * Raw joystick button numbers chosen by the user on the controller check screen, for controllers SDL has no
 * gamepad mapping for (like the Steam Frame controllers). Null uses the default layout.
 */
@Serializable
data class ControllerMapping(
	val activateButton: Int? = null,
	val backButton: Int? = null,
)

@Serializable
data class ServerConfig(
	val url: String,
	val name: String? = null,
	val userId: String? = null,
	val userName: String? = null,
	val accessToken: String? = null,
)

@Serializable
enum class PlaybackMode(val label: String) {
	/** Render the video on a large virtual screen through OpenXR (SteamVR). */
	VR_CINEMA("VR cinema"),

	/** Let mpv open a regular fullscreen window, SteamOS shows it on its own virtual desktop screen. */
	FLAT("Flat window"),
}

@Serializable
data class PlaybackSettings(
	val mode: PlaybackMode = PlaybackMode.VR_CINEMA,
	/** [HWDEC_AUTO] picks the Steam Frame's hardware decoder, otherwise a raw mpv hwdec value. */
	val hwdec: String = HWDEC_AUTO,
	/** Show mpv's statistics overlay (frame timings, dropped frames, decoder) during playback. */
	val showStats: Boolean = false,
	val autoPlayNextEpisode: Boolean = true,
	val maxBitrateMbps: Int = 120,
)

const val HWDEC_AUTO = "auto"

/**
 * mpv hwdec value for the Steam Frame. The Snapdragon's video decoder is exposed through V4L2 M2M, which mpv's
 * auto-safe mode never tries. The copy variant works with any video output, including the OpenGL render API used by
 * the VR cinema, and frees the CPU from decoding 4K HEVC/AV1. Other GPUs (desktop testing) fall back to auto.
 */
fun resolveHwdec(setting: String): String = when (setting) {
	// "auto-safe" was the default of earlier versions, upgrade it as well
	HWDEC_AUTO, "auto-safe" -> "v4l2m2m-copy,auto-copy-safe"
	else -> setting
}

/**
 * Stores the configuration as JSON in `$XDG_CONFIG_HOME/fellyjin-steamframe/config.json`.
 * The file contains the access token and is therefore only readable by the current user.
 */
class ConfigStore(
	private val file: Path = defaultConfigFile(),
) {
	private val logger = LoggerFactory.getLogger(ConfigStore::class.java)
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
		prettyPrint = true
	}

	private val _config = MutableStateFlow(load())
	val config: StateFlow<AppConfig> = _config.asStateFlow()

	val current: AppConfig get() = _config.value

	fun update(transform: (AppConfig) -> AppConfig) {
		_config.update(transform)
		save(_config.value)
	}

	private fun load(): AppConfig = try {
		if (file.exists()) json.decodeFromString<AppConfig>(file.readText()) else AppConfig().also(::save)
	} catch (err: Exception) {
		logger.warn("Unable to read config from $file, starting fresh", err)
		AppConfig()
	}

	private fun save(config: AppConfig) {
		try {
			file.parent.createDirectories()
			val tmp = file.resolveSibling("${file.fileName}.tmp")
			tmp.writeText(json.encodeToString(config))
			runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (err: Exception) {
			logger.error("Unable to write config to $file", err)
		}
	}

	companion object {
		fun defaultConfigFile(): Path {
			val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
				?: Path.of(System.getProperty("user.home"), ".config")
			val dir = base.resolve("fellyjin-steamframe")
			migrateFromJellyfinName(base.resolve("jellyfin-steamframe"), dir)
			return dir.resolve("config.json")
		}

		/** Keep the login and settings of installs from before the rename to Fellyjin. */
		private fun migrateFromJellyfinName(old: Path, new: Path) {
			if (new.exists() || !old.exists()) return
			runCatching { Files.move(old, new) }
				.onFailure { LoggerFactory.getLogger(ConfigStore::class.java).warn("Unable to migrate $old to $new", it) }
		}
	}
}
