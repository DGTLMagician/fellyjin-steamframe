package app.fellyjin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.ui.unit.dp
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.playback.PlaybackController
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration

private val logger = LoggerFactory.getLogger("ItemScreen")

private val playableTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.TRAILER)

@Composable
fun ItemScreen(
	repository: JellyfinRepository,
	playback: PlaybackController,
	itemId: UUID,
	onOpenItem: (BaseItemDto) -> Unit,
) {
	var item by remember(itemId) { mutableStateOf<BaseItemDto?>(null) }
	var error by remember(itemId) { mutableStateOf<String?>(null) }
	var seasons by remember(itemId) { mutableStateOf(emptyList<BaseItemDto>()) }
	var selectedSeason by remember(itemId) { mutableStateOf<BaseItemDto?>(null) }
	var episodes by remember(itemId) { mutableStateOf(emptyList<BaseItemDto>()) }
	var children by remember(itemId) { mutableStateOf(emptyList<BaseItemDto>()) }

	LaunchedEffect(itemId) {
		try {
			val loaded = repository.item(itemId)
			item = loaded
			when (loaded.type) {
				BaseItemKind.SERIES -> {
					seasons = repository.seasons(loaded.id)
					// Start at the first season that still has something to watch
					selectedSeason = seasons.firstOrNull { (it.userData?.unplayedItemCount ?: 0) > 0 } ?: seasons.firstOrNull()
				}

				BaseItemKind.SEASON -> {
					selectedSeason = loaded
				}

				BaseItemKind.BOX_SET -> children = repository.libraryItems(loaded.id, 0, 200).first

				else -> Unit
			}
		} catch (err: Exception) {
			logger.warn("Unable to load item $itemId", err)
			error = "Unable to load this item: ${err.message}"
		}
	}

	LaunchedEffect(selectedSeason) {
		val season = selectedSeason ?: return@LaunchedEffect
		val seriesId = season.seriesId ?: item?.id ?: return@LaunchedEffect
		episodes = runCatching { repository.episodes(seriesId, season.id) }
			.onFailure { logger.warn("Unable to load episodes", it) }
			.getOrDefault(emptyList())
	}

	val current = item
	when {
		error != null -> ErrorBox(error!!)
		current == null -> LoadingBox()
		else -> Box(Modifier.fillMaxSize()) {
			repository.backdropUrl(current)?.let { backdrop ->
				ItemImage(backdrop, current.name.orEmpty(), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
				Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(BackdropSideScrim))
				Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(BackdropScrim))
			}

			LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 64.dp)) {
				item {
					Header(current, repository, playback, nextEpisode = episodes.firstOrNull { it.userData?.played != true })
				}

				if (seasons.size > 1) {
					item {
						LazyRow(
							contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp),
							horizontalArrangement = Arrangement.spacedBy(16.dp),
						) {
							items(seasons, key = { it.id }) { season ->
								BigButton(season.name.orEmpty(), onClick = { selectedSeason = season }, primary = season.id == selectedSeason?.id)
							}
						}
					}
				}

				items(episodes, key = { it.id }) { episode ->
					EpisodeRow(episode, repository, onPlay = { playback.play(episode, resume = true) }, onOpen = { onOpenItem(episode) })
				}

				if (children.isNotEmpty()) {
					item {
						SectionRow("In this collection", children, key = { it.id }) { child ->
							PosterCard(child, repository.posterUrl(child), onClick = { onOpenItem(child) })
						}
					}
				}
			}
		}
	}
}

@Composable
private fun Header(
	item: BaseItemDto,
	repository: JellyfinRepository,
	playback: PlaybackController,
	nextEpisode: BaseItemDto?,
) {
	Row(
		Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 120.dp, bottom = 32.dp),
		horizontalArrangement = Arrangement.spacedBy(48.dp),
	) {
		Box(Modifier.width(300.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))) {
			ItemImage(repository.posterUrl(item, 600), item.name.orEmpty())
		}

		Column(Modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) {
				item.seriesName?.let { Text(it, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
			val logo = repository.logoUrl(item)
			if (logo != null && item.type != BaseItemKind.EPISODE) {
				AsyncImage(
					model = logo,
					contentDescription = item.name,
					contentScale = ContentScale.Fit,
					alignment = Alignment.CenterStart,
					modifier = Modifier.height(160.dp).widthIn(max = 620.dp),
				)
			} else {
				Text(item.name.orEmpty(), style = MaterialTheme.typography.displayMedium)
			}
			MetadataLine(item)
			item.genres?.takeIf { it.isNotEmpty() }?.let {
				Text(it.joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			item.overview?.let {
				Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
			}

			Spacer(Modifier.height(8.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
				when {
					item.type in playableTypes -> {
						val position = item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.ticks
						if (position != null) {
							BigButton("Resume from ${formatDuration(position)}", onClick = { playback.play(item, resume = true) }, icon = Icons.Default.PlayArrow)
							BigButton("Play from start", onClick = { playback.play(item, resume = false) }, icon = Icons.Default.Replay, primary = false)
						} else {
							BigButton("Play", onClick = { playback.play(item, resume = false) }, icon = Icons.Default.PlayArrow)
						}
					}

					nextEpisode != null -> {
						val label = listOfNotNull(
							if (nextEpisode.parentIndexNumber != null && nextEpisode.indexNumber != null) "S${nextEpisode.parentIndexNumber}:E${nextEpisode.indexNumber}" else null,
							nextEpisode.name,
						).joinToString(" ")
						BigButton("Play $label", onClick = { playback.play(nextEpisode, resume = true) }, icon = Icons.Default.PlayArrow)
					}
				}
			}
		}
	}
}

@Composable
private fun MetadataLine(item: BaseItemDto) {
	val parts = listOfNotNull(
		item.productionYear?.toString(),
		item.officialRating,
		item.runTimeTicks?.takeIf { it > 0 }?.ticks?.let(::formatRuntime),
		item.communityRating?.let { "★ %.1f".format(it) },
	)
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
		parts.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
		if (item.video3dFormat != null) {
			Box(
				Modifier.clip(RoundedCornerShape(8.dp)).background(FellyjinColors.Brand).padding(horizontal = 12.dp, vertical = 2.dp)
			) {
				Text("3D", style = MaterialTheme.typography.titleMedium, color = Color.White)
			}
		}
	}
}

@Composable
private fun EpisodeRow(episode: BaseItemDto, repository: JellyfinRepository, onPlay: () -> Unit, onOpen: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 14.dp),
		horizontalArrangement = Arrangement.spacedBy(32.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		LandscapeCard(
			item = episode,
			imageUrl = repository.landscapeUrl(episode),
			onClick = onPlay,
			width = 380.dp,
			title = listOfNotNull(episode.indexNumber?.let { "$it." }, episode.name).joinToString(" "),
			subtitle = episode.runTimeTicks?.ticks?.let(::formatRuntime),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			episode.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
			Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				BigButton(if (episode.userData?.playbackPositionTicks?.let { it > 0 } == true) "Resume" else "Play", onClick = onPlay, icon = Icons.Default.PlayArrow)
				BigButton("Details", onClick = onOpen, primary = false)
			}
		}
	}
}

private fun formatRuntime(duration: Duration): String {
	val minutes = duration.inWholeMinutes
	return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

private fun formatDuration(duration: Duration): String = duration.toComponents { hours, minutes, seconds, _ ->
	if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
