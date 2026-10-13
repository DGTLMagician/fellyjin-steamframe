package org.jellyfin.steamframe.ui

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.steamframe.BuildInfo
import org.jellyfin.steamframe.data.ConfigStore
import org.jellyfin.steamframe.data.JellyfinRepository
import org.jellyfin.steamframe.data.HWDEC_AUTO
import org.jellyfin.steamframe.data.PlaybackMode
import org.jellyfin.steamframe.vr.CinemaEnvironment
import org.jellyfin.steamframe.vr.PictureQuality
import org.jellyfin.steamframe.vr.ScreenPreset

@Composable
fun SettingsScreen(
	configStore: ConfigStore,
	repository: JellyfinRepository,
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
				"Steam Frame controller: A or trigger plays/pauses, B or View stops, Menu shows progress, " +
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Text(title, style = MaterialTheme.typography.headlineMedium, color = JellyfinColors.Blue)
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
