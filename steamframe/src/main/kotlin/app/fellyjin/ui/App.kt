package app.fellyjin.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import kotlinx.coroutines.delay
import app.fellyjin.input.GamepadEvent
import app.fellyjin.input.GamepadInput
import app.fellyjin.input.UiAction
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import java.nio.file.Path
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import org.jellyfin.sdk.model.api.BaseItemDto
import app.fellyjin.data.ConfigStore
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.data.PlaybackMode
import app.fellyjin.playback.PlaybackController
import app.fellyjin.playback.PlaybackState
import java.util.UUID

sealed interface Screen {
	data object ServerSelect : Screen
	data object Login : Screen
	data object Home : Screen
	data class Library(val id: UUID, val name: String) : Screen
	data class Item(val id: UUID) : Screen
	data object Settings : Screen
}

class Navigator(initial: Screen) {
	private val stack = mutableStateListOf(initial)

	val current: Screen get() = stack.last()

	fun push(screen: Screen) {
		stack.add(screen)
	}

	fun back(): Boolean {
		if (stack.size <= 1) return false
		stack.removeAt(stack.lastIndex)
		return true
	}

	/** Back to the first screen (home) without losing it. */
	fun home() {
		while (stack.size > 1) stack.removeAt(stack.lastIndex)
	}

	fun replaceAll(screen: Screen) {
		stack.clear()
		stack.add(screen)
	}
}

class AppDependencies(
	val configStore: ConfigStore,
	val repository: JellyfinRepository,
	val playback: PlaybackController,
)

/**
 * Window level key handling. The window calls [handler] for keys no focused element consumed, also when nothing in
 * the UI has focus (a modifier on the root layout only sees keys while something inside it is focused).
 */
class KeyRouter {
	var handler: ((KeyEvent) -> Boolean)? = null
}

@Composable
fun App(deps: AppDependencies, initialScreen: Screen, gamepad: GamepadInput? = null, keys: KeyRouter? = null) {
	setSingletonImageLoaderFactory { context ->
		ImageLoader.Builder(context)
			.crossfade(true)
			.memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
			// Posters and backdrops survive restarts, so browsing a big library over the network stays snappy
			.diskCache { DiskCache.Builder().directory(imageCacheDir().toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
			.build()
	}

	val navigator = remember { Navigator(initialScreen) }
	val backdrop = remember { BackdropState() }
	val activation = remember { ActivationState() }
	var controllerToast by remember { mutableStateOf<String?>(null) }
	val focusManager = LocalFocusManager.current
	val playbackState by deps.playback.state.collectAsState()

	fun openItem(item: BaseItemDto) = navigator.push(item.toScreen())

	fun back() {
		if (deps.playback.isActive) deps.playback.stop()
		else if (deps.playback.state.value is PlaybackState.Failed) deps.playback.dismissError()
		else navigator.back()
	}

	// Keyboard (and Steam Input layouts that send keys): Enter activates, Escape goes back, arrows move focus
	DisposableEffect(keys) {
		keys?.handler = handler@{ event ->
			if (event.type != KeyEventType.KeyDown) return@handler false
			val action = when (event.key) {
				Key.Enter, Key.NumPadEnter, Key.Spacebar -> UiAction.ACTIVATE
				Key.Escape, Key.Backspace -> UiAction.BACK
				Key.DirectionLeft -> UiAction.LEFT
				Key.DirectionRight -> UiAction.RIGHT
				Key.DirectionUp -> UiAction.UP
				Key.DirectionDown -> UiAction.DOWN
				else -> return@handler false
			}
			if (deps.playback.handle(action)) return@handler true
			when (action) {
				UiAction.UP -> focusManager.moveFocusOrEnter(FocusDirection.Up)
				UiAction.DOWN -> focusManager.moveFocusOrEnter(FocusDirection.Down)
				UiAction.LEFT -> focusManager.moveFocusOrEnter(FocusDirection.Left)
				UiAction.RIGHT -> focusManager.moveFocusOrEnter(FocusDirection.Right)
				UiAction.ACTIVATE -> activation.focused?.invoke() ?: return@handler false
				UiAction.BACK -> back()
			}
			true
		}
		onDispose { keys?.handler = null }
	}

	// Controller input, handled in Compose so it works even when gamescope never gives the window keyboard focus
	LaunchedEffect(gamepad) {
		gamepad?.events?.collect { event ->
			when (event) {
				is GamepadEvent.Connected -> controllerToast = "Controller connected: ${event.name}"
				GamepadEvent.Disconnected -> controllerToast = "Controller disconnected"
				// While a video plays in the window, the controller drives the player
				is GamepadEvent.Action -> if (!deps.playback.handle(event.action)) when (event.action) {
					UiAction.UP -> focusManager.moveFocusOrEnter(FocusDirection.Up)
					UiAction.DOWN -> focusManager.moveFocusOrEnter(FocusDirection.Down)
					UiAction.LEFT -> focusManager.moveFocusOrEnter(FocusDirection.Left)
					UiAction.RIGHT -> focusManager.moveFocusOrEnter(FocusDirection.Right)
					UiAction.ACTIVATE -> activation.focused?.invoke() ?: focusManager.moveFocus(FocusDirection.Next)
					UiAction.BACK -> back()
				}
			}
		}
	}
	LaunchedEffect(controllerToast) {
		if (controllerToast != null) {
			delay(3_000)
			controllerToast = null
		}
	}

	FellyjinTheme {
		Box(
			Modifier.fillMaxSize()
		) {
			AmbientBackground()

			// Browse screens share a full screen backdrop that follows the focused item
			val browsing = navigator.current is Screen.Home || navigator.current is Screen.Library
			LaunchedEffect(navigator.current) { if (!browsing) backdrop.url = null }
			if (browsing) BackdropLayer(backdrop.url)

			CompositionLocalProvider(LocalBackdrop provides backdrop, LocalActivation provides activation, LocalGamepad provides gamepad) {
			Crossfade(navigator.current) { screen ->
				when (screen) {
					Screen.ServerSelect -> ServerScreen(deps.repository, onConnected = { navigator.replaceAll(Screen.Login) })
					Screen.Login -> LoginScreen(
						repository = deps.repository,
						onLoggedIn = { navigator.replaceAll(Screen.Home) },
						onChangeServer = { navigator.replaceAll(Screen.ServerSelect) },
					)

					Screen.Home -> HomeScreen(
						repository = deps.repository,
						playback = deps.playback,
						onOpenItem = ::openItem,
						onOpenSettings = { navigator.push(Screen.Settings) },
					)

					is Screen.Library -> LibraryScreen(deps.repository, screen.id, screen.name, onOpenItem = ::openItem)
					is Screen.Item -> ItemScreen(deps.repository, deps.playback, screen.id, onOpenItem = ::openItem)
					Screen.Settings -> SettingsScreen(
						configStore = deps.configStore,
						repository = deps.repository,
						playback = deps.playback,
						onSignedOut = { navigator.replaceAll(Screen.Login) },
						onChangeServer = { navigator.replaceAll(Screen.ServerSelect) },
					)
				}
			}
			}

			// Always reachable with the laser pointer, also when the controller buttons don't work
			if (navigator.current !is Screen.Home && navigator.current !is Screen.ServerSelect && navigator.current !is Screen.Login) {
				CompositionLocalProvider(LocalActivation provides activation) {
					Row(
						Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp),
						horizontalArrangement = Arrangement.spacedBy(14.dp),
					) {
						BigButton("Back", onClick = navigator::back, icon = Icons.AutoMirrored.Filled.ArrowBack, primary = false)
						BigButton("Home", onClick = { navigator.home() }, icon = Icons.Default.Home, primary = false)
					}
				}
			}

			CompositionLocalProvider(LocalActivation provides activation) {
				PlaybackOverlay(playbackState, deps)
			}

			controllerToast?.let { message ->
				Box(
					Modifier
						.align(Alignment.BottomCenter)
						.padding(bottom = 48.dp)
						.background(FellyjinColors.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(50))
						.padding(horizontal = 32.dp, vertical = 16.dp)
				) {
					Text(message, style = MaterialTheme.typography.titleMedium)
				}
			}
		}
	}
}

@Composable
private fun BackdropLayer(url: String?) {
	Crossfade(url, animationSpec = tween(700), modifier = Modifier.fillMaxSize()) { current ->
		if (current != null) {
			Box(Modifier.fillMaxSize()) {
				AsyncImage(
					model = current,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					alignment = Alignment.TopEnd,
					modifier = Modifier.fillMaxWidth(0.78f).fillMaxSize().align(Alignment.TopEnd),
				)
				Box(Modifier.fillMaxSize().background(BackdropSideScrim))
				Box(Modifier.fillMaxSize().background(BackdropScrim))
			}
		}
	}
}

private fun imageCacheDir(): Path {
	val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
		?: Path.of(System.getProperty("user.home"), ".cache")
	return base.resolve("fellyjin-steamframe").resolve("images")
}

/** Move focus; when nothing is focused yet (first press) focus the first item instead. */
private fun androidx.compose.ui.focus.FocusManager.moveFocusOrEnter(direction: FocusDirection) {
	if (!moveFocus(direction)) moveFocus(FocusDirection.Next)
}

private fun BaseItemDto.toScreen(): Screen = when {
	// Libraries and folders are browsed as a grid, everything else gets a detail page
	isFolder == true && collectionType != null -> Screen.Library(id, name.orEmpty())
	type == org.jellyfin.sdk.model.api.BaseItemKind.COLLECTION_FOLDER ||
		type == org.jellyfin.sdk.model.api.BaseItemKind.USER_VIEW ||
		type == org.jellyfin.sdk.model.api.BaseItemKind.FOLDER -> Screen.Library(id, name.orEmpty())

	else -> Screen.Item(id)
}

@Composable
private fun PlaybackOverlay(state: PlaybackState, deps: AppDependencies) {
	val vr = deps.configStore.current.playback.mode == PlaybackMode.VR_CINEMA
	when (state) {
		PlaybackState.Idle -> Unit
		is PlaybackState.Starting -> OverlayPanel {
			LoadingBox(Modifier.padding(bottom = 16.dp))
			Text("Starting ${state.item.name}", style = MaterialTheme.typography.headlineMedium)
		}

		is PlaybackState.Playing -> OverlayPanel(backdropUrl = deps.repository.backdropUrl(state.item)) {
			Text("NOW SHOWING", style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 6.sp), color = FellyjinColors.Accent)
			val logo = deps.repository.logoUrl(state.item)
			if (logo != null) {
				AsyncImage(model = logo, contentDescription = state.item.name, contentScale = ContentScale.Fit, modifier = Modifier.height(180.dp).widthIn(max = 700.dp))
			}
			Text(state.item.name.orEmpty(), style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
			Text(
				if (state.output == "VR cinema" && vr) {
					"Watching on the cinema screen in your headset.\n" +
						"A / trigger: play or pause · B / View: stop · Menu: media info\n" +
						"D-pad or stick ←→: seek · D-pad or stick ↑↓: screen distance · Bumpers: chapters\n" +
						"X: subtitles · Y: audio track · Stick click: recenter"
				} else {
					"Playing in a separate window. Press Escape or Q to stop."
				},
				style = MaterialTheme.typography.bodyLarge,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			state.notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
			BigButton("Stop playback", onClick = deps.playback::stop, icon = Icons.Default.Stop)
		}

		is PlaybackState.Failed -> OverlayPanel {
			Text("Playback failed", style = MaterialTheme.typography.headlineLarge)
			Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
			BigButton("OK", onClick = deps.playback::dismissError)
		}
	}
}

@Composable
private fun OverlayPanel(backdropUrl: String? = null, content: @Composable () -> Unit) {
	Box(
		Modifier
			.fillMaxSize()
			.background(Color.Black)
			// Block clicks from reaching the screen underneath
			.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
		contentAlignment = Alignment.Center,
	) {
		if (backdropUrl != null) {
			AsyncImage(model = backdropUrl, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxSize())
			Box(Modifier.fillMaxSize().background(BackdropScrim))
		}
		Column(
			Modifier.widthIn(max = 1100.dp).padding(48.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(24.dp),
		) {
			content()
		}
	}
}
