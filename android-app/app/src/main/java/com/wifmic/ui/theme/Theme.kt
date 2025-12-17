package com.wifmic.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Catpuccin Mocha Palette
object Catpuccin {
    val Base = Color(0xFF1E1E2E)
    val Mantle = Color(0xFF181825)
    val Crust = Color(0xFF11111B)
    val Surface0 = Color(0xFF313244)
    val Surface1 = Color(0xFF45475A)
    val Surface2 = Color(0xFF585B70)
    val Overlay0 = Color(0xFF6C7086)
    val Overlay1 = Color(0xFF7F849C)
    val Text = Color(0xFFCDD6F4)
    val Subtext1 = Color(0xFFBAC2DE)
    val Subtext0 = Color(0xFFA6ADC8)
    val Pink = Color(0xFFF5C2E7)
    val Mauve = Color(0xFFCBA6F7)
    val Red = Color(0xFFF38BA8)
    val Maroon = Color(0xFFEBA0AC)
    val Peach = Color(0xFFFAB387)
    val Yellow = Color(0xFFF9E2AF)
    val Green = Color(0xFFA6E3A1)
    val Teal = Color(0xFF94E2D5)
    val Sky = Color(0xFF89DCEB)
    val Sapphire = Color(0xFF74C7EC)
    val Blue = Color(0xFF89B4FA)
    val Lavender = Color(0xFFB4BEFE)
}

// Status colors using Catpuccin
val ConnectedGreen = Catpuccin.Green
val DisconnectedRed = Catpuccin.Red
val SearchingOrange = Catpuccin.Peach
val MutedGray = Catpuccin.Overlay0

private val CatpuccinColorScheme = darkColorScheme(
    primary = Catpuccin.Mauve,
    onPrimary = Catpuccin.Crust,
    primaryContainer = Catpuccin.Surface0,
    onPrimaryContainer = Catpuccin.Text,
    secondary = Catpuccin.Pink,
    onSecondary = Catpuccin.Crust,
    secondaryContainer = Catpuccin.Surface1,
    onSecondaryContainer = Catpuccin.Text,
    tertiary = Catpuccin.Teal,
    onTertiary = Catpuccin.Crust,
    tertiaryContainer = Catpuccin.Surface0,
    onTertiaryContainer = Catpuccin.Text,
    background = Catpuccin.Base,
    onBackground = Catpuccin.Text,
    surface = Catpuccin.Base,
    onSurface = Catpuccin.Text,
    surfaceVariant = Catpuccin.Surface0,
    onSurfaceVariant = Catpuccin.Subtext1,
    outline = Catpuccin.Overlay0,
    outlineVariant = Catpuccin.Surface2,
    inverseSurface = Catpuccin.Text,
    inverseOnSurface = Catpuccin.Base,
    error = Catpuccin.Red,
    onError = Catpuccin.Crust,
    errorContainer = Catpuccin.Maroon,
    onErrorContainer = Catpuccin.Crust
)

@Composable
fun MeoMicTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = CatpuccinColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Catpuccin.Mantle.toArgb()
            window.navigationBarColor = Catpuccin.Mantle.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

// Keep old name for compatibility during transition
@Composable
fun WifiMicTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    MeoMicTheme(content = content)
}
