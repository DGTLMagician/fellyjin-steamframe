package org.jellyfin.steamframe.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object JellyfinColors {
	val Purple = Color(0xFFAA5CC3)
	val Blue = Color(0xFF00A4DC)
	val Background = Color(0xFF0B0B10)
	val Surface = Color(0xFF17171F)
	val SurfaceHigh = Color(0xFF22222D)
	val TextPrimary = Color(0xFFF2F2F5)
	val TextSecondary = Color(0xFFA9A9B8)
	val Error = Color(0xFFFF6B6B)

	val Brand = Brush.linearGradient(listOf(Purple, Blue))
}

// Sized for a big virtual screen several meters away: everything is larger than a desktop app
private val typography = Typography(
	displayMedium = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp),
	headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
	headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
	titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
	titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
	bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
	bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
	labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
	labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JellyfinTheme(content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = darkColorScheme(
			primary = JellyfinColors.Blue,
			onPrimary = Color.White,
			secondary = JellyfinColors.Purple,
			background = JellyfinColors.Background,
			onBackground = JellyfinColors.TextPrimary,
			surface = JellyfinColors.Surface,
			onSurface = JellyfinColors.TextPrimary,
			surfaceVariant = JellyfinColors.SurfaceHigh,
			onSurfaceVariant = JellyfinColors.TextSecondary,
			error = JellyfinColors.Error,
		),
		typography = typography,
	) {
		// Text outside of a Surface uses LocalContentColor, which defaults to black
		CompositionLocalProvider(LocalContentColor provides JellyfinColors.TextPrimary, content = content)
	}
}
