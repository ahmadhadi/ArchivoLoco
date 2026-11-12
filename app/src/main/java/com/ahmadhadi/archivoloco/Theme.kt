package com.ahmadhadi.archivoloco

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightBrand = lightColorScheme(
    primary = Color(0xFF7A3FB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DBFF),
    onPrimaryContainer = Color(0xFF2E004E),
    secondary = Color(0xFFB0306C),
    secondaryContainer = Color(0xFFFFD9E3),
    onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Color(0xFFB05A1E),
    tertiaryContainer = Color(0xFFFFDBC8),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFDFB7FF),
    onPrimary = Color(0xFF4A1677),
    primaryContainer = Color(0xFF622C90),
    onPrimaryContainer = Color(0xFFF1DBFF),
    secondary = Color(0xFFFFB0CA),
    secondaryContainer = Color(0xFF8E1553),
    onSecondaryContainer = Color(0xFFFFD9E3),
    tertiary = Color(0xFFFFB68A),
    tertiaryContainer = Color(0xFF8B4206),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Material You: wallpaper-based colours on Android 12+, brand colours otherwise. */
@Composable
fun ArchivoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkBrand
        else -> LightBrand
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
