package app.fellyjin.ui

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

/**
 * Fellyjin palette: "neon sunset". Near-black with a cool undertone, a coral → violet → mint brand gradient, violet
 * as the action color and mint for small accents.
 */
object FellyjinColors {
	val Coral = Color(0xFFFF5C7A)
	val Violet = Color(0xFF8B5CF6)
	val Mint = Color(0xFF2EE6C5)

	val Primary = Violet
	val Accent = Mint

	val Background = Color(0xFF07070B)
	val Surface = Color(0xFF111118)
	val SurfaceHigh = Color(0xFF1B1B25)
	val Outline = Color(0xFF2A2A38)
	val TextPrimary = Color(0xFFF5F3FF)
	val TextSecondary = Color(0xFFA5A3B8)
	val Error = Color(0xFFFF6B81)

	/** Wordmark, progress bars and badges. */
	val Brand = Brush.linearGradient(listOf(Coral, Violet, Mint))

	/** Filled buttons: a shorter, punchier sweep. */
	val ButtonBrush = Brush.horizontalGradient(listOf(Coral, Violet))
}

// Sized for a big virtual screen several meters away. Headlines are heavy with tight tracking for a modern look.
private val typography = Typography(
	displayMedium = TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Black, lineHeight = 60.sp, letterSpacing = (-1.2).sp),
	headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp, letterSpacing = (-0.8).sp),
	headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
	titleLarge = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
	titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
	bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
	bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
	labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
	labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FellyjinTheme(content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = darkColorScheme(
			primary = FellyjinColors.Primary,
			onPrimary = Color.White,
			secondary = FellyjinColors.Accent,
			tertiary = FellyjinColors.Coral,
			background = FellyjinColors.Background,
			onBackground = FellyjinColors.TextPrimary,
			surface = FellyjinColors.Surface,
			onSurface = FellyjinColors.TextPrimary,
			surfaceVariant = FellyjinColors.SurfaceHigh,
			onSurfaceVariant = FellyjinColors.TextSecondary,
			outline = FellyjinColors.Outline,
			error = FellyjinColors.Error,
		),
		typography = typography,
	) {
		// Text outside of a Surface uses LocalContentColor, which defaults to black
		CompositionLocalProvider(LocalContentColor provides FellyjinColors.TextPrimary, content = content)
	}
}
