package app.fellyjin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import app.fellyjin.input.KeyboardDiagnostics
import app.fellyjin.playback.PlaybackController
import app.fellyjin.vr.VrDiagnostics
import app.fellyjin.vr.VrStatus
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Theaters
import app.fellyjin.data.ControllerMapping
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.fellyjin.BuildInfo
import app.fellyjin.data.ConfigStore
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.data.HWDEC_AUTO
import app.fellyjin.data.PlaybackMode
import app.fellyjin.vr.CinemaEnvironment
import app.fellyjin.vr.PictureQuality
import app.fellyjin.vr.ScreenPreset

@Composable
fun SettingsScreen(
	configStore: ConfigStore,
	repository: JellyfinRepository,
	playback: PlaybackController?,
	onSignedOut: () -> Unit,
	onChangeServer: () -> Unit,
) {
	val config by configStore.config.collectAsState()
	val scope = rememberCoroutineScope()

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding).widthIn(max = 1400.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
	) {
		Text("Settings", style = MaterialTheme.typography.displayMedium)

		ControllerCheck(configStore)

		Section("Playback") {
			Choice("Play videos in", PlaybackMode.entries, config.playback.mode, { it.label }) { mode ->
				configStore.update { it.copy(playback = it.playback.copy(mode = mode)) }
			}
			Choice("Play next episode automatically", listOf(true, false), config.playback.autoPlayNextEpisode, { if (it) "On" else "Off" }) { value ->
				configStore.update { it.copy(playback = it.playback.copy(autoPlayNextEpisode = value)) }
			}
			Choice("Maximum streaming bitrate", listOf(10, 20, 40, 80, 120, 200), config.playback.maxBitrateMbps, { "$it Mbps" }) { value ->
				configStore.update { it.copy(playback = it.playback.copy(maxBitrateMbps = value)) }
			}
			Choice("Hardware decoding", listOf(HWDEC_AUTO, "auto-copy-safe", "no"), config.playback.hwdec.let { if (it == "auto-safe") HWDEC_AUTO else it }, {
				when (it) {
					HWDEC_AUTO -> "Steam Frame (V4L2)"
					"auto-copy-safe" -> "Generic"
					else -> "Off (software)"
				}
			}) { value ->
				configStore.update { it.copy(playback = it.playback.copy(hwdec = value)) }
			}
			Choice("Performance overlay", listOf(false, true), config.playback.showStats, { if (it) "On" else "Off" }) { value ->
				configStore.update { it.copy(playback = it.playback.copy(showStats = value)) }
			}
		}

		Section("VR cinema") {
			VrStatusPanel(playback)
			Choice("Screen", ScreenPreset.entries, config.cinema.screen, { "${it.label} (${"%.3g".format(it.widthMeters)} m, ${it.horizontalFovDegrees}°)" }) { preset ->
				configStore.update { it.copy(cinema = it.cinema.copy(screen = preset)) }
			}
			Choice("Picture quality", PictureQuality.entries, config.cinema.quality, {
				when (it) {
					PictureQuality.HIGH -> "High (upscaled to 4K, debanding)"
					PictureQuality.BALANCED -> "Balanced (lowest GPU load)"
				}
			}) { value ->
				configStore.update { it.copy(cinema = it.cinema.copy(quality = value)) }
			}
			Choice("Screen shape", listOf(true, false), config.cinema.curved, { if (it) "Curved" else "Flat" }) { value ->
				configStore.update { it.copy(cinema = it.cinema.copy(curved = value)) }
			}
			Choice("Surroundings", CinemaEnvironment.entries, config.cinema.environment, { it.label }) { environment ->
				configStore.update { it.copy(cinema = it.cinema.copy(environment = environment)) }
			}
			Choice("Screen height", listOf(-2f, -1f, 0f, 1f, 2f, 4f), config.cinema.verticalOffsetMeters, {
				when {
					it == 0f -> "Eye level"
					it > 0 -> "+${it.toInt()} m"
					else -> "${it.toInt()} m"
				}
			}) { value ->
				configStore.update { it.copy(cinema = it.cinema.copy(verticalOffsetMeters = value)) }
			}
			Text(
				"Steam Frame controller: A or trigger plays/pauses, B or View stops, Menu shows media info (format, HDR, audio, dropped frames), " +
					"D-pad/stick left/right seeks, up/down moves the screen, bumpers skip chapters, " +
					"X cycles subtitles, Y cycles audio, stick click recenters.",
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		Section("Account") {
			Text(
				"Signed in to ${repository.serverUrl.orEmpty()} as ${repository.user.value?.name.orEmpty()}",
				style = MaterialTheme.typography.bodyLarge,
			)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				BigButton("Sign out", onClick = { scope.launch { repository.logout(); onSignedOut() } }, primary = false)
				BigButton("Change server", onClick = onChangeServer, primary = false)
			}
		}

		Text(
			"${BuildInfo.CLIENT_NAME} ${BuildInfo.VERSION} · config: ${ConfigStore.defaultConfigFile()}",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/** Result of the last VR start, with a preview button to (re)try without a movie. */
@Composable
private fun VrStatusPanel(playback: PlaybackController?) {
	val vr by VrDiagnostics.status.collectAsState()
	Column(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(20.dp))
			.background(FellyjinColors.Surface.copy(alpha = 0.85f))
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		val state = when (vr.state) {
			VrStatus.State.NOT_STARTED -> "Not started yet: press Preview cinema"
			VrStatus.State.RUNNING -> "Working"
			VrStatus.State.FAILED -> "Failed, videos play in a flat window"
		}
		DiagnosticLine("VR status", state, ok = vr.state == VrStatus.State.RUNNING)
		vr.reason?.let { DiagnosticLine("Reason", it, ok = false) }
		DiagnosticLine("Runtime", listOfNotNull(vr.runtime, vr.system).joinToString(" · ").ifEmpty { "unknown" }, ok = vr.runtime != null)
		vr.graphics?.let { DiagnosticLine("Graphics", it, ok = true) }
		if (vr.features.isNotEmpty()) DiagnosticLine("Features", vr.features.joinToString(), ok = true)
		vr.refreshRate?.let { DiagnosticLine("Refresh rate", it, ok = true) }
		vr.controller?.let { DiagnosticLine("Controller", it, ok = !it.startsWith("no ")) }
	}
	if (playback != null) {
		BigButton("Preview cinema", onClick = playback::preview, icon = Icons.Default.Theaters)
	}
}

/** Live view of what the app receives from the controller, to diagnose Steam Input setups. */
@Composable
private fun ControllerCheck(configStore: ConfigStore) {
	val config by configStore.config.collectAsState()
	val gamepad = LocalGamepad.current
	val pad by (gamepad?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(app.fellyjin.input.GamepadStatus("Not started")) }).collectAsState()
	val keyboard by KeyboardDiagnostics.status.collectAsState()

	Section("Controller check") {
		Text(
			"Press buttons on your controller: the counters below should go up. Take a photo of this when reporting a controller problem.",
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Column(
			Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(20.dp))
				.background(FellyjinColors.Surface.copy(alpha = 0.85f))
				.padding(24.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			DiagnosticLine("Gamepad library", pad.sdl, ok = pad.sdl.startsWith("SDL "))
			DiagnosticLine("Devices", pad.devices.ifEmpty { listOf("none found") }.joinToString("\n"), ok = pad.devices.isNotEmpty())
			DiagnosticLine("Used for the UI", pad.active ?: "none", ok = pad.active != null)
			DiagnosticLine("Gamepad input", pad.lastInput?.let { "$it (${pad.inputCount} presses)" } ?: "nothing yet", ok = pad.inputCount > 0)
			DiagnosticLine("Raw buttons held", pad.rawButtons.ifEmpty { null }?.joinToString { "#$it" } ?: "none", ok = pad.rawButtons.isNotEmpty())
			DiagnosticLine("Keyboard input", keyboard.lastKey?.let { "$it (${keyboard.keyCount} keys)" } ?: "nothing yet", ok = keyboard.keyCount > 0)
			DiagnosticLine("Window has focus", if (keyboard.windowFocused) "yes" else "no", ok = keyboard.windowFocused)
			DiagnosticLine("Log file", app.fellyjin.logFile?.toString() ?: "unavailable", ok = app.fellyjin.logFile != null)
		}

		// Assign buttons for controllers without a known layout (click these with the laser pointer)
		Text("Buttons", style = MaterialTheme.typography.titleLarge)
		Text(
			when {
				pad.learning -> "Press the button on your controller now…"
				else -> "Select: ${config.controller.activateButton?.let { "button #$it" } ?: "default (A)"}  ·  " +
					"Back: ${config.controller.backButton?.let { "button #$it" } ?: "default (B)"}"
			},
			style = MaterialTheme.typography.bodyLarge,
			color = if (pad.learning) FellyjinColors.Mint else MaterialTheme.colorScheme.onSurfaceVariant,
		)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			BigButton("Set Select button", enabled = gamepad != null && !pad.learning, onClick = {
				gamepad?.learnButton { button -> configStore.update { it.copy(controller = it.controller.copy(activateButton = button)) } }
			})
			BigButton("Set Back button", enabled = gamepad != null && !pad.learning, primary = false, onClick = {
				gamepad?.learnButton { button -> configStore.update { it.copy(controller = it.controller.copy(backButton = button)) } }
			})
			if (pad.learning) {
				BigButton("Cancel", primary = false, onClick = { gamepad?.cancelLearning() })
			} else if (config.controller != ControllerMapping()) {
				BigButton("Reset to default", primary = false, onClick = { configStore.update { it.copy(controller = ControllerMapping()) } })
			}
		}
	}
}

@Composable
private fun DiagnosticLine(label: String, value: String, ok: Boolean) {
	Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		Text(if (ok) "●" else "○", color = if (ok) FellyjinColors.Mint else FellyjinColors.Coral, style = MaterialTheme.typography.titleMedium)
		Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(260.dp))
		Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Text(title, style = MaterialTheme.typography.headlineMedium, color = FellyjinColors.Accent)
		content()
	}
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(title, style = MaterialTheme.typography.titleLarge)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			options.forEach { option ->
				BigButton(label(option), onClick = { onSelect(option) }, primary = option == selected)
			}
		}
	}
}
