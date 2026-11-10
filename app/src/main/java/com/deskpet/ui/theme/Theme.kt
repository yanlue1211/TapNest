package com.deskpet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Tap Nest's calm mint identity. Dynamic wallpaper colors remain opt-in so the
 * editor, companion rooms and floating outfit menus look like one application.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF32684C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF3E4), onPrimaryContainer = Color(0xFF17442C),
    secondary = Color(0xFF547567), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2F2E9), onSecondaryContainer = Color(0xFF234538),
    tertiary = Color(0xFF4C768C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8EEF6), onTertiaryContainer = Color(0xFF1A475D),
    background = Color(0xFFF5FAF7), onBackground = Color(0xFF20352B),
    surface = Color(0xFFFCFEFC), onSurface = Color(0xFF20352B),
    surfaceVariant = Color(0xFFE5F0E9), onSurfaceVariant = Color(0xFF586D60),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F8F2),
    surfaceContainer = Color(0xFFE9F4EC),
    surfaceContainerHigh = Color(0xFFE0EEE5),
    outline = Color(0xFF819A8B), outlineVariant = Color(0xFFD4E5D8),
    error = Color(0xFFAC343E), onError = Color.White,
    errorContainer = Color(0xFFFFDADC), onErrorContainer = Color(0xFF6A1A24)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3DDB6), onPrimary = Color(0xFF153C28),
    primaryContainer = Color(0xFF245A40), onPrimaryContainer = Color(0xFFDBF6E4),
    secondary = Color(0xFFB1D4C1), onSecondary = Color(0xFF203A2E),
    secondaryContainer = Color(0xFF334F40), onSecondaryContainer = Color(0xFFE5F7EC),
    tertiary = Color(0xFFAAD4E9), onTertiary = Color(0xFF163C50),
    tertiaryContainer = Color(0xFF31566B), onTertiaryContainer = Color(0xFFD9EDF7),
    background = Color(0xFF111F19), onBackground = Color(0xFFE0EDE4),
    surface = Color(0xFF172820), onSurface = Color(0xFFE0EDE4),
    surfaceVariant = Color(0xFF344B3D), onSurfaceVariant = Color(0xFFB8D0BE),
    surfaceContainerLowest = Color(0xFF102019),
    surfaceContainerLow = Color(0xFF1D3025),
    surfaceContainer = Color(0xFF22382B),
    surfaceContainerHigh = Color(0xFF2C4334),
    outline = Color(0xFF88A793), outlineVariant = Color(0xFF395546),
    error = Color(0xFFFFB4B8), onError = Color(0xFF690007),
    errorContainer = Color(0xFF85232D), onErrorContainer = Color(0xFFFFDADD)
)

private val NestShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun DeskPetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = NestShapes,
        typography = Typography,
        content = content
    )
}
