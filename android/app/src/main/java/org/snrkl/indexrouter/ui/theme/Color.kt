package org.snrkl.indexrouter.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The fallback palette, used only where dynamic colour is unavailable.
 *
 * Seeded from the app's own launcher icon rather than invented: its background
 * is `#00446E` out to the edges with a `#8BC1CC` gradient mid-stop, so the two
 * tones here are the ones already on the home screen. An app whose fallback
 * palette had nothing to do with its icon would look like two different apps
 * depending on the phone it landed on.
 *
 * Roles follow the MD3 scheme — a container is the quiet, filled surface behind
 * a tonal element, and each `on*` is what stays legible against it.
 */

// Deep blue, from the icon background.
internal val LightPrimary = Color(0xFF00446E)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFCDE5FF)
internal val LightOnPrimaryContainer = Color(0xFF001D33)

// Pale teal, from the icon's gradient mid-stop.
internal val LightSecondary = Color(0xFF3F6472)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFC3E8F9)
internal val LightOnSecondaryContainer = Color(0xFF001F28)

internal val LightTertiary = Color(0xFF55617C)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFDBE1FF)
internal val LightOnTertiaryContainer = Color(0xFF121C36)

// Error is not themed to the icon: it should read as a warning in any palette.
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)

internal val LightBackground = Color(0xFFFCFCFF)
internal val LightOnBackground = Color(0xFF1A1C1E)
internal val LightSurface = Color(0xFFFCFCFF)
internal val LightOnSurface = Color(0xFF1A1C1E)
internal val LightSurfaceVariant = Color(0xFFDEE3EB)
internal val LightOnSurfaceVariant = Color(0xFF42474E)
internal val LightOutline = Color(0xFF72777F)
internal val LightOutlineVariant = Color(0xFFC2C7CF)
internal val LightScrim = Color(0xFF000000)
internal val LightInverseSurface = Color(0xFF2F3033)
internal val LightInverseOnSurface = Color(0xFFF0F0F4)
internal val LightInversePrimary = Color(0xFF95CCFF)

internal val DarkPrimary = Color(0xFF95CCFF)
internal val DarkOnPrimary = Color(0xFF003354)
internal val DarkPrimaryContainer = Color(0xFF004A77)
internal val DarkOnPrimaryContainer = Color(0xFFCDE5FF)

internal val DarkSecondary = Color(0xFFA7CCDC)
internal val DarkOnSecondary = Color(0xFF0B3542)
internal val DarkSecondaryContainer = Color(0xFF264C59)
internal val DarkOnSecondaryContainer = Color(0xFFC3E8F9)

internal val DarkTertiary = Color(0xFFBDC5E9)
internal val DarkOnTertiary = Color(0xFF27304C)
internal val DarkTertiaryContainer = Color(0xFF3E4664)
internal val DarkOnTertiaryContainer = Color(0xFFDBE1FF)

internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

internal val DarkBackground = Color(0xFF1A1C1E)
internal val DarkOnBackground = Color(0xFFE2E2E6)
internal val DarkSurface = Color(0xFF1A1C1E)
internal val DarkOnSurface = Color(0xFFE2E2E6)
internal val DarkSurfaceVariant = Color(0xFF42474E)
internal val DarkOnSurfaceVariant = Color(0xFFC2C7CF)
internal val DarkOutline = Color(0xFF8C9199)
internal val DarkOutlineVariant = Color(0xFF42474E)
internal val DarkScrim = Color(0xFF000000)
internal val DarkInverseSurface = Color(0xFFE2E2E6)
internal val DarkInverseOnSurface = Color(0xFF2F3033)
internal val DarkInversePrimary = Color(0xFF00639B)
