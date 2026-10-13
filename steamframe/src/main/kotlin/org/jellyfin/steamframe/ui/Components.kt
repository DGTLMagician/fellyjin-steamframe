package org.jellyfin.steamframe.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

private val CardShape = RoundedCornerShape(12.dp)

/** The full screen backdrop behind browse screens, follows the focused item. */
class BackdropState {
	var url by mutableStateOf<String?>(null)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

/**
 * Remembers the click action of the focused element, so the controller's A button can activate it directly
 * (without relying on AWT keyboard focus, which gamescope does not always give the window).
 */
class ActivationState {
	var focused: (() -> Unit)? = null
}

val LocalActivation = staticCompositionLocalOf { ActivationState() }

/** Register [onClick] as the controller activation target while [focused] is true. */
@Composable
fun RegisterActivation(focused: Boolean, onClick: () -> Unit) {
	val activation = LocalActivation.current
	val currentOnClick by rememberUpdatedState(onClick)
	val action = remember { { currentOnClick() } }
	LaunchedEffect(focused) {
		if (focused) activation.focused = action
		else if (activation.focused === action) activation.focused = null
	}
	DisposableEffect(Unit) {
		onDispose { if (activation.focused === action) activation.focused = null }
	}
}

/**
 * Focus and hover aware card: works with the keyboard/gamepad (focus) as well as the VR laser pointer (hover).
 * Highlighted cards scale up with a spring and get a soft brand colored glow.
 */
@Composable
fun FocusCard(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	onHighlight: (() -> Unit)? = null,
	content: @Composable BoxScope.() -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val hovered by interactionSource.collectIsHoveredAsState()
	val highlighted = focused || hovered
	val scale by animateFloatAsState(
		targetValue = if (highlighted) 1.08f else 1f,
		animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
	)
	val glow by animateDpAsState(if (highlighted) 32.dp else 0.dp)

	LaunchedEffect(highlighted) {
		if (highlighted) onHighlight?.invoke()
	}
	RegisterActivation(focused, onClick)

	Box(
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.shadow(glow, CardShape, ambientColor = JellyfinColors.Blue, spotColor = JellyfinColors.Blue)
			.clip(CardShape)
			.border(3.dp, if (highlighted) Color.White else Color.Transparent, CardShape)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
	) {
		content()
	}
}

@Composable
fun PosterCard(
	item: BaseItemDto,
	imageUrl: String?,
	onClick: () -> Unit,
	width: Dp = 190.dp,
	onHighlight: (() -> Unit)? = null,
) {
	Column(Modifier.width(width)) {
		FocusCard(onClick = onClick, onHighlight = onHighlight, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
			ItemImage(imageUrl, item.name.orEmpty())
			UnplayedBadge(item, Modifier.align(Alignment.TopEnd))
		}
		Spacer(Modifier.height(8.dp))
		Text(item.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
		item.productionYear?.let {
			Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
fun LandscapeCard(
	item: BaseItemDto,
	imageUrl: String?,
	onClick: () -> Unit,
	width: Dp = 360.dp,
	title: String = item.name.orEmpty(),
	subtitle: String? = landscapeSubtitle(item),
	onHighlight: (() -> Unit)? = null,
) {
	Column(Modifier.width(width)) {
		FocusCard(onClick = onClick, onHighlight = onHighlight, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
			ItemImage(imageUrl, title)
			item.userData?.playedPercentage?.takeIf { it > 0 && it < 100 }?.let { percentage ->
				Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Color.Black.copy(alpha = 0.5f))) {
					Box(Modifier.fillMaxWidth((percentage / 100).toFloat()).height(6.dp).background(JellyfinColors.Brand))
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
		subtitle?.let {
			Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

fun landscapeSubtitle(item: BaseItemDto): String? = when (item.type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		item.seriesName,
		if (item.parentIndexNumber != null && item.indexNumber != null) "S${item.parentIndexNumber}:E${item.indexNumber}" else null,
	).joinToString(" · ").ifEmpty { null }

	else -> item.productionYear?.toString()
}

@Composable
private fun UnplayedBadge(item: BaseItemDto, modifier: Modifier) {
	val count = item.userData?.unplayedItemCount ?: return
	if (count <= 0) return
	Box(
		modifier
			.padding(8.dp)
			.clip(RoundedCornerShape(50))
			.background(JellyfinColors.Brand)
			.padding(horizontal = 10.dp, vertical = 2.dp)
	) {
		Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = Color.White)
	}
}

@Composable
fun ItemImage(url: String?, description: String, modifier: Modifier = Modifier.fillMaxSize()) {
	Box(modifier.background(JellyfinColors.SurfaceHigh), contentAlignment = Alignment.Center) {
		if (url == null) {
			Text(
				description.take(2).uppercase(),
				style = MaterialTheme.typography.headlineMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			AsyncImage(model = url, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
		}
	}
}

@Composable
fun <T> SectionRow(
	title: String,
	items: List<T>,
	key: (T) -> Any,
	content: @Composable (T) -> Unit,
) {
	if (items.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(bottom = 36.dp)) {
		Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp))
		LazyRow(
			contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(28.dp),
		) {
			items(items, key = key) { content(it) }
		}
	}
}

val ScreenPadding = 64.dp

@Composable
fun BigButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	icon: ImageVector? = null,
	primary: Boolean = true,
	enabled: Boolean = true,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f)
	RegisterActivation(focused && enabled, onClick)
	val content: @Composable () -> Unit = {
		Row(verticalAlignment = Alignment.CenterVertically) {
			icon?.let {
				Icon(it, contentDescription = null, modifier = Modifier.size(28.dp))
				Spacer(Modifier.width(10.dp))
			}
			Text(text, style = MaterialTheme.typography.labelLarge)
		}
	}
	val buttonModifier = modifier
		.graphicsLayer {
			scaleX = scale
			scaleY = scale
		}
		.height(64.dp)
		.border(3.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
	if (primary) {
		Button(
			onClick = onClick,
			enabled = enabled,
			interactionSource = interactionSource,
			modifier = buttonModifier,
			contentPadding = PaddingValues(horizontal = 32.dp),
			colors = ButtonDefaults.buttonColors(containerColor = JellyfinColors.Blue),
		) { content() }
	} else {
		OutlinedButton(
			onClick = onClick,
			enabled = enabled,
			interactionSource = interactionSource,
			modifier = buttonModifier,
			contentPadding = PaddingValues(horizontal = 32.dp),
		) { content() }
	}
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
	Box(modifier, contentAlignment = Alignment.Center) {
		CircularProgressIndicator(color = JellyfinColors.Blue, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
	}
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxSize()) {
	Box(modifier, contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
			Text(message, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
			onRetry?.let { BigButton("Try again", it) }
		}
	}
}

/** Darkening gradient drawn over backdrops so text stays readable. */
val BackdropScrim = Brush.verticalGradient(
	0f to Color.Transparent,
	0.55f to JellyfinColors.Background.copy(alpha = 0.7f),
	1f to JellyfinColors.Background,
)

val BackdropSideScrim = Brush.horizontalGradient(
	0f to JellyfinColors.Background.copy(alpha = 0.95f),
	0.6f to JellyfinColors.Background.copy(alpha = 0.4f),
	1f to Color.Transparent,
)
