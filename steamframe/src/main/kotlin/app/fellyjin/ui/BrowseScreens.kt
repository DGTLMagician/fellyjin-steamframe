package app.fellyjin.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.playback.PlaybackController
import app.fellyjin.playback.PlaybackState
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("BrowseScreens")

private data class HomeContent(
	val views: List<BaseItemDto>,
	val resume: List<BaseItemDto>,
	val nextUp: List<BaseItemDto>,
	val latest: List<Pair<BaseItemDto, List<BaseItemDto>>>,
)

@Composable
fun HomeScreen(
	repository: JellyfinRepository,
	playback: PlaybackController,
	onOpenItem: (BaseItemDto) -> Unit,
	onOpenSettings: () -> Unit,
	onQuit: () -> Unit = {},
) {
	val user by repository.user.collectAsState()
	val playbackState by playback.state.collectAsState()
	val backdrop = LocalBackdrop.current
	var content by remember { mutableStateOf<HomeContent?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var reloadKey by remember { mutableIntStateOf(0) }
	var spotlight by remember { mutableStateOf<BaseItemDto?>(null) }

	fun highlight(item: BaseItemDto) {
		spotlight = item
		repository.backdropUrl(item)?.let { backdrop.url = it }
	}

	// Reload when playback stops so continue watching and next up are up to date
	val idle = playbackState is PlaybackState.Idle
	LaunchedEffect(reloadKey, idle) {
		if (!idle) return@LaunchedEffect
		error = null
		try {
			val loaded = coroutineScope {
				val views = async { repository.userViews() }
				val resume = async { repository.resumeItems() }
				val nextUp = async { repository.nextUp() }
				val viewList = views.await()
				val latest = viewList
					.filter { it.collectionType == CollectionType.MOVIES || it.collectionType == CollectionType.TVSHOWS }
					.map { view -> async { view to runCatching { repository.latest(view.id) }.getOrDefault(emptyList()) } }
				HomeContent(viewList, resume.await(), nextUp.await(), latest.map { it.await() })
			}
			content = loaded
			if (spotlight == null) {
				(loaded.resume + loaded.nextUp + loaded.latest.flatMap { it.second }).firstOrNull()?.let(::highlight)
			}
		} catch (err: Exception) {
			logger.warn("Unable to load home", err)
			error = "Unable to load your library: ${err.message}"
		}
	}

	val current = content
	when {
		current == null && error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
		current == null -> LoadingBox()
		else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 64.dp)) {
			item {
				Row(
					Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 32.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Wordmark()
					Spacer(Modifier.weight(1f))
					ControllerChip()
					Spacer(Modifier.width(28.dp))
					Clock()
					Spacer(Modifier.width(32.dp))
					user?.let {
						Text(it.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Spacer(Modifier.width(24.dp))
					}
					BigButton("Settings", onClick = onOpenSettings, icon = Icons.Default.Settings, primary = false)
					Spacer(Modifier.width(14.dp))
					// Gaming mode has no window close button: always offer a way out
					BigButton("Quit", onClick = onQuit, icon = Icons.Default.PowerSettingsNew, primary = false)
				}
			}

			item {
				Spotlight(spotlight, repository, playback, onOpenItem)
			}

			item {
				SectionRow("Continue watching", current.resume, key = { it.id }) { item ->
					LandscapeCard(item, repository.landscapeUrl(item), onClick = { onOpenItem(item) }, onHighlight = { highlight(item) })
				}
			}
			item {
				SectionRow("Next up", current.nextUp, key = { it.id }) { item ->
					LandscapeCard(item, repository.landscapeUrl(item), onClick = { onOpenItem(item) }, onHighlight = { highlight(item) })
				}
			}
			current.latest.forEach { (view, items) ->
				item(key = "latest-${view.id}") {
					SectionRow("Latest in ${view.name}", items, key = { it.id }) { item ->
						PosterCard(item, repository.posterUrl(item), onClick = { onOpenItem(item) }, onHighlight = { highlight(item) })
					}
				}
			}
			item {
				SectionRow("My media", current.views, key = { it.id }) { view ->
					LandscapeCard(view, repository.posterUrl(view, 640), onClick = { onOpenItem(view) }, width = 340.dp, subtitle = null)
				}
			}
		}
	}
}

/** Large title area at the top of home, shows the focused item over the backdrop like a movie poster. */
@Composable
private fun Spotlight(
	item: BaseItemDto?,
	repository: JellyfinRepository,
	playback: PlaybackController,
	onOpenItem: (BaseItemDto) -> Unit,
) {
	Box(Modifier.fillMaxWidth().height(440.dp).padding(horizontal = ScreenPadding)) {
		if (item == null) return@Box
		Crossfade(item, animationSpec = tween(400)) { shown ->
			Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				val logo = repository.logoUrl(shown)
				if (logo != null) {
					AsyncImage(
						model = logo,
						contentDescription = shown.seriesName ?: shown.name,
						contentScale = ContentScale.Fit,
						alignment = Alignment.CenterStart,
						modifier = Modifier.height(150.dp).widthIn(max = 560.dp),
					)
				} else {
					Text(
						shown.seriesName ?: shown.name.orEmpty(),
						style = MaterialTheme.typography.displayMedium,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
				if (shown.type == BaseItemKind.EPISODE) {
					Text(
						listOfNotNull(landscapeSubtitle(shown)?.substringAfter(" · ", ""), shown.name).filter { it.isNotBlank() }.joinToString(" · "),
						style = MaterialTheme.typography.titleLarge,
					)
				}
				Text(
					listOfNotNull(
						shown.productionYear?.toString(),
						shown.officialRating,
						shown.communityRating?.let { "★ %.1f".format(it) },
						shown.genres?.take(3)?.joinToString(", ")?.ifBlank { null },
					).joinToString("   "),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				shown.overview?.let {
					Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
				}
				Spacer(Modifier.height(4.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
					if (shown.type != BaseItemKind.SERIES) {
						val resumable = (shown.userData?.playbackPositionTicks ?: 0) > 0
						BigButton(if (resumable) "Resume" else "Play", onClick = { playback.play(shown, resume = true) }, icon = Icons.Default.PlayArrow)
					}
					BigButton("More info", onClick = { onOpenItem(shown) }, icon = Icons.Default.Info, primary = false)
				}
			}
		}
	}
}

/** Small status pill: whether a controller is driving the UI. */
@Composable
private fun ControllerChip() {
	val gamepad = LocalGamepad.current ?: return
	val status by gamepad.status.collectAsState()
	val connected = status.active != null
	Row(
		Modifier
			.clip(RoundedCornerShape(50))
			.background(Color.White.copy(alpha = 0.06f))
			.padding(horizontal = 18.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Icon(
			Icons.Default.SportsEsports,
			contentDescription = null,
			tint = if (connected) FellyjinColors.Mint else FellyjinColors.Coral,
			modifier = Modifier.size(28.dp),
		)
		Text(if (connected) "Controller" else "No controller", style = MaterialTheme.typography.labelMedium)
	}
}

@Composable
private fun Clock() {
	var now by remember { mutableStateOf(LocalTime.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			now = LocalTime.now()
			// Wake up right after the next minute starts
			delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
		}
	}
	Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.headlineMedium)
}

@Composable
fun LibraryScreen(
	repository: JellyfinRepository,
	libraryId: UUID,
	name: String,
	onOpenItem: (BaseItemDto) -> Unit,
) {
	val items = remember(libraryId) { mutableStateListOf<BaseItemDto>() }
	var total by remember(libraryId) { mutableStateOf<Int?>(null) }
	var error by remember(libraryId) { mutableStateOf<String?>(null) }
	val gridState = rememberLazyGridState()
	val backdrop = LocalBackdrop.current

	// Load the next page when the user scrolls near the end
	val loadMore by remember {
		derivedStateOf {
			val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
			val count = total
			count == null || (lastVisible >= items.size - 20 && items.size < count)
		}
	}

	LaunchedEffect(libraryId, loadMore, items.size) {
		if (!loadMore) return@LaunchedEffect
		try {
			val (page, totalCount) = repository.libraryItems(libraryId, items.size, PAGE_SIZE)
			items.addAll(page.filter { new -> items.none { it.id == new.id } })
			total = if (page.isEmpty()) items.size else totalCount
		} catch (err: Exception) {
			logger.warn("Unable to load library $libraryId", err)
			error = "Unable to load $name: ${err.message}"
		}
	}

	Column(Modifier.fillMaxSize()) {
		Text(name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = ScreenPadding, top = 40.dp, bottom = 8.dp))
		total?.let {
			Text("$it items", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ScreenPadding))
		}
		when {
			error != null && items.isEmpty() -> ErrorBox(error!!)
			total == null -> LoadingBox()
			else -> LazyVerticalGrid(
				columns = GridCells.Adaptive(200.dp),
				state = gridState,
				contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 32.dp),
				horizontalArrangement = Arrangement.spacedBy(28.dp),
				verticalArrangement = Arrangement.spacedBy(36.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				items(items, key = { it.id }) { item ->
					PosterCard(
						item,
						repository.posterUrl(item),
						onClick = { onOpenItem(item) },
						width = 200.dp,
						onHighlight = { repository.backdropUrl(item)?.let { backdrop.url = it } },
					)
				}
			}
		}
	}
}

private const val PAGE_SIZE = 100
