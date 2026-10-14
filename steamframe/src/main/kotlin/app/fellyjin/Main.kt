package app.fellyjin

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import app.fellyjin.playback.PlaybackState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.fellyjin.data.ConfigStore
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.input.GamepadInput
import app.fellyjin.playback.PlaybackController
import app.fellyjin.ui.App
import app.fellyjin.ui.AppDependencies
import app.fellyjin.ui.KeyRouter
import app.fellyjin.ui.Screen
import app.fellyjin.input.KeyboardDiagnostics
import app.fellyjin.playback.AwtVideoHost
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/** Gamescope (SteamOS gaming mode, including on the Steam Frame) wants a single fullscreen window. */
private fun runningInGamescope(): Boolean =
	System.getenv("GAMESCOPE_WAYLAND_DISPLAY") != null ||
		System.getenv("XDG_CURRENT_DESKTOP").equals("gamescope", ignoreCase = true) ||
		System.getenv("SteamGamepadUI") != null

private val appIcon by lazy {
	val stream = requireNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) { "icon.png missing" }
	BitmapPainter(stream.use { ImageIO.read(it) }.toComposeImageBitmap())
}

/**
 * Always log to `~/.local/state/fellyjin-steamframe/fellyjin.log` (previous run in `fellyjin.log.1`), so problems on
 * the Steam Frame can be diagnosed without changing Steam's launch options. Must run before the first logger is made.
 */
private fun setupLogging(): Path? = runCatching {
	val base = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
		?: Path.of(System.getProperty("user.home"), ".local", "state")
	val dir = base.resolve("fellyjin-steamframe").also(Files::createDirectories)
	val log = dir.resolve("fellyjin.log")
	if (Files.exists(log)) Files.move(log, dir.resolve("fellyjin.log.1"), StandardCopyOption.REPLACE_EXISTING)
	System.setProperty("org.slf4j.simpleLogger.logFile", log.toString())
	System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
	System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
	log
}.getOrNull()

/** Location of the log file of this run, shown on the controller check screen. */
var logFile: Path? = null
	private set

fun main() {
	logFile = setupLogging()
	LoggerFactory.getLogger("Fellyjin").info("${BuildInfo.CLIENT_NAME} ${BuildInfo.VERSION} starting")
	val configStore = ConfigStore()
	val repository = JellyfinRepository(configStore)
	val playback = PlaybackController(repository, configStore)
	val deps = AppDependencies(configStore, repository, playback)

	val initialScreen = when {
		repository.restoreSession() -> Screen.Home
		configStore.current.server != null -> Screen.Login
		else -> Screen.ServerSelect
	}

	// Keys reach the app through the window, see KeyRouter
	val keys = KeyRouter()

	application {
		val windowState = rememberWindowState(
			placement = if (runningInGamescope() || appEnv("FULLSCREEN") == "1") WindowPlacement.Fullscreen else WindowPlacement.Maximized,
			width = 1920.dp,
			height = 1080.dp,
		)

		Window(
			onCloseRequest = {
				// Let playback report its position and end the VR session / mpv cleanly before the JVM exits
				playback.shutdown()
				exitApplication()
			},
			state = windowState,
			title = BuildInfo.APP_NAME,
			icon = appIcon,
			onKeyEvent = { event -> keys.handler?.invoke(event) ?: false },
			onPreviewKeyEvent = { event ->
				// F11 toggles fullscreen
				if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
					windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else WindowPlacement.Fullscreen
					true
				} else {
					false
				}
			},
		) {
			// The default Steam Frame controls arrive as a gamepad, read it for the UI
			val gamepad = remember { GamepadInput(mapping = { configStore.current.controller }).also { it.start() } }
			DisposableEffect(Unit) {
				// Ask for keyboard focus too, for Steam Input layouts that send keys instead of gamepad input
				KeyboardDiagnostics.install(window)
				// Windowed playback draws inside this window, gamescope only shows one window per app
				playback.videoHost = AwtVideoHost(window)
				window.toFront()
				window.requestFocus()
				// Gamescope doesn't always hand keyboard focus to a Java window. Keep asking while we don't have it (and
				// no other window of ours, like mpv's flat player, is in front) so key based Steam Input layouts work.
				val focusNudger = javax.swing.Timer(2_000) {
					if (!window.isFocused && !playback.isActive && window.isShowing) {
						window.toFront()
						window.requestFocus()
					}
				}.apply { start() }
				onDispose {
					focusNudger.stop()
					gamepad.close()
				}
			}
			// After VR the headset doesn't always bring this window back: bring it to the front ourselves, and map it
			// again (a fresh window for gamescope) when that alone doesn't give it focus
			LaunchedEffect(Unit) {
				var inVr = false
				playback.state.collect { state ->
					when {
						state is PlaybackState.Playing -> inVr = state.output == "VR cinema"
						state is PlaybackState.Starting -> Unit
						inVr -> {
							inVr = false
							LoggerFactory.getLogger("Fellyjin").info("VR playback ended, bringing the window back")
							window.toFront()
							window.requestFocus()
							delay(1_000)
							if (!window.isFocused) {
								LoggerFactory.getLogger("Fellyjin").info("Window still not focused after VR, showing it again")
								window.isVisible = false
								window.isVisible = true
								window.toFront()
								window.requestFocus()
							}
						}
					}
				}
			}
			App(deps, initialScreen, gamepad, keys)
		}
	}
}
