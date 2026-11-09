package app.vyaya.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours for money: what came in is green; what went out keeps the text colour (no alarm for ordinary spending). */
data class MoneyColors(val income: Color, val overspent: Color)

val LocalMoneyColors = staticCompositionLocalOf { MoneyColors(Color(0xFF1B7F3A), Color(0xFFBA1A1A)) }

private val Light = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB4E9E0), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF05201C),
    background = Color(0xFFF5FAF8), onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF5FAF8), onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainer = Color(0xFFE9EFED), surfaceContainerHigh = Color(0xFFE3EAE7),
    outline = Color(0xFF6F7977), error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF84D5C9), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFA0F2E5),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF252B2A),
    outline = Color(0xFF899390), error = Color(0xFFFFB4AB),
)

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    )
}

/** Vyaya's look on both phones: a calm teal, light and dark following the phone's setting. */
@Composable
fun VyayaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val money = if (dark) MoneyColors(Color(0xFF7FD99A), Color(0xFFFFB4AB)) else MoneyColors(Color(0xFF1B7F3A), Color(0xFFBA1A1A))
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type) {
        androidx.compose.runtime.CompositionLocalProvider(LocalMoneyColors provides money, content = content)
    }
}
