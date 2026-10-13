package org.jellyfin.steamframe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.Video3dFormat
import org.jellyfin.steamframe.data.ConfigStore
import org.jellyfin.steamframe.data.JellyfinRepository
import org.jellyfin.steamframe.ui.JellyfinColors
import org.jellyfin.steamframe.ui.JellyfinTheme
import org.jellyfin.steamframe.ui.LandscapeCard
import org.jellyfin.steamframe.ui.PosterCard
import org.jellyfin.steamframe.ui.SectionRow
import org.jellyfin.steamframe.ui.ServerScreen
import org.jellyfin.steamframe.ui.SettingsScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders screens offscreen to build/snapshots for a visual check without a display or server. */
class ScreenSnapshotTest {
	private val outDir = File("build/snapshots").apply { mkdirs() }
	private val configStore = ConfigStore(Files.createTempDirectory("jellyfin-steamframe").resolve("config.json"))
	private val repository = JellyfinRepository(configStore)

	private fun snapshot(name: String, content: @Composable () -> Unit) {
		val scene = ImageComposeScene(width = 1920, height = 1080, density = Density(1f)) {
			Box(Modifier.fillMaxSizeBackground()) { JellyfinTheme { content() } }
		}
		// Render a few frames so effects and animations settle
		repeat(5) { scene.render(it * 100_000_000L) }
		val image = scene.render(1_000_000_000L)
		val file = File(outDir, "$name.png")
		file.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
		scene.close()
		assertTrue(file.length() > 0)
	}

	@Test
	fun serverScreen() = snapshot("server") { ServerScreen(repository, onConnected = {}) }

	@Test
	fun settingsScreen() = snapshot("settings") { SettingsScreen(configStore, repository, onSignedOut = {}, onChangeServer = {}) }

	@Test
	fun cards() = snapshot("cards") {
		val movies = (1..8).map { fakeItem("Movie $it", BaseItemKind.MOVIE, 2000 + it) }
		val episodes = (1..5).map { fakeItem("Episode $it", BaseItemKind.EPISODE, null) }
		Column {
			SectionRow("Continue watching", episodes, key = { it.id }) { LandscapeCard(it, null, onClick = {}) }
			SectionRow("Latest movies", movies, key = { it.id }) { PosterCard(it, null, onClick = {}) }
		}
	}

	private fun fakeItem(name: String, type: BaseItemKind, year: Int?) = BaseItemDto(
		id = UUID.randomUUID(),
		name = name,
		type = type,
		productionYear = year,
		seriesName = if (type == BaseItemKind.EPISODE) "Some Series" else null,
		parentIndexNumber = if (type == BaseItemKind.EPISODE) 1 else null,
		indexNumber = if (type == BaseItemKind.EPISODE) 3 else null,
		video3dFormat = if (year == 2003) Video3dFormat.HALF_SIDE_BY_SIDE else null,
	)
}

private fun Modifier.fillMaxSizeBackground() = fillMaxSize().background(JellyfinColors.Background)
