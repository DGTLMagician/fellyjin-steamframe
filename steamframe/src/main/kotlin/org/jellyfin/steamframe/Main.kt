package org.jellyfin.steamframe

import androidx.compose.runtime.DisposableEffect
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
import org.jellyfin.steamframe.data.ConfigStore
import org.jellyfin.steamframe.data.JellyfinRepository
import org.jellyfin.steamframe.input.GamepadInput
import org.jellyfin.steamframe.playback.PlaybackController
import org.jellyfin.steamframe.ui.App
import org.jellyfin.steamframe.ui.AppDependencies
import org.jellyfin.steamframe.ui.Screen
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

fun main() {
	val configStore = ConfigStore()
	val repository = JellyfinRepository(configStore)
	val playback = PlaybackController(repository, configStore)
	val deps = AppDependencies(configStore, repository, playback)

	val initialScreen = when {
		repository.restoreSession() -> Screen.Home
		configStore.current.server != null -> Screen.Login
		else -> Screen.ServerSelect
	}

	application {
		val windowState = rememberWindowState(
			placement = if (runningInGamescope() || System.getenv("JELLYFIN_FULLSCREEN") == "1") WindowPlacement.Fullscreen else WindowPlacement.Maximized,
			width = 1920.dp,
			height = 1080.dp,
		)

		Window(
			onCloseRequest = {
				playback.stop()
				exitApplication()
			},
			state = windowState,
			title = "Jellyfin",
			icon = appIcon,
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
			val gamepad = remember { GamepadInput().also { it.start() } }
			DisposableEffect(Unit) {
				// Ask for keyboard focus too, for Steam Input layouts that send keys instead of gamepad input
				window.toFront()
				window.requestFocus()
				onDispose { gamepad.close() }
			}
			App(deps, initialScreen, gamepad)
		}
	}
}
