package dev.dejalango.app.ui

import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.sp
import dev.dejalango.app.R

@OptIn(ExperimentalTextApi::class)
private val Nunito = FontFamily(listOf(400, 600, 700, 800).map { weight ->
    Font(R.font.nunito, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})
@OptIn(ExperimentalTextApi::class)
private val Fredoka = FontFamily(Font(R.font.fredoka, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))))
private val Light = lightColorScheme(
    primary = Color(0xFF245795), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECF8), onPrimaryContainer = Color(0xFF245795),
    tertiary = Color(0xFF1C416F), onTertiary = Color.White,
    secondary = Color(0xFF365642), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBD7), onSecondaryContainer = Color(0xFF294D39),
    background = Color(0xFFEAF6FF), onBackground = Color(0xFF213D50),
    surface = Color(0xFFEAF6FF), onSurface = Color(0xFF213D50),
    surfaceContainer = Color(0xFFF6FCFF), surfaceContainerLow = Color(0xFFF6FCFF),
    surfaceContainerHigh = Color(0xFFDCECF8), onSurfaceVariant = Color(0xFF446176),
    outline = Color(0xFF7198AB), outlineVariant = Color(0xFFBAD4E3),
    scrim = Color(0xFF213D50),
    error = Color(0xFF9F352D), errorContainer = Color(0xFFFFE8DE), onErrorContainer = Color(0xFF75251E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9CEFF), onPrimary = Color(0xFF12365E),
    primaryContainer = Color(0xFF244E7B), onPrimaryContainer = Color(0xFFD7EAFF),
    tertiary = Color(0xFF6E99C5), onTertiary = Color(0xFF102E4B),
    secondary = Color(0xFFB3DBC0), onSecondary = Color(0xFF153524),
    secondaryContainer = Color(0xFF284B39), onSecondaryContainer = Color(0xFFD4EEDD),
    background = Color(0xFF142936), onBackground = Color(0xFFE1F0F8),
    surface = Color(0xFF142936), onSurface = Color(0xFFE1F0F8),
    surfaceContainer = Color(0xFF1C3443), surfaceContainerLow = Color(0xFF1C3443),
    surfaceContainerHigh = Color(0xFF294657), onSurfaceVariant = Color(0xFFB7CDDA),
    outline = Color(0xFF8CA9BA), outlineVariant = Color(0xFF436275),
    scrim = Color(0xFF0C1D28),
    error = Color(0xFFFFB4A8), errorContainer = Color(0xFF602821), onErrorContainer = Color(0xFFFFDAD3),
)
private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Nunito, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
private fun display(size: Int, line: Int) =
    TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = line.sp)
private val CompanionType = Typography(
    displaySmall = display(36, 42), headlineLarge = display(32, 37), headlineMedium = display(27, 32),
    headlineSmall = display(24, 29), titleLarge = display(22, 28),
    titleMedium = body(18, 24, FontWeight.Bold), titleSmall = body(16, 22, FontWeight.Bold),
    bodyLarge = body(16, 24), bodyMedium = body(14, 21), bodySmall = body(12, 18),
    labelLarge = body(14, 20, FontWeight.ExtraBold), labelMedium = body(12, 17, FontWeight.Bold),
    labelSmall = body(11, 16, FontWeight.Bold),
)

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val activity = LocalActivity.current
    val buttonNavigation = WindowInsets.tappableElement.getBottom(LocalDensity.current) > 0
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark && !buttonNavigation
            }
            window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = CompanionType, content = content)
}

/** Three-button navigation gets an ink protection surface; gesture navigation stays transparent. */
@Composable
internal fun Modifier.protectButtonNavigation(inset: Int = WindowInsets.tappableElement.getBottom(LocalDensity.current)): Modifier {
    val bottom = inset.toFloat()
    val ink = MaterialTheme.colorScheme.scrim
    return drawBehind { if (bottom > 0) drawRect(ink, Offset(0f, size.height - bottom), Size(size.width, bottom)) }
}

@Composable
internal fun SheetSystemBars() {
    val view = LocalView.current
    SideEffect {
        val provider = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()
        provider?.window?.isNavigationBarContrastEnforced = false
    }
}
