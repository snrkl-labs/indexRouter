package org.snrkl.indexrouter.ui.theme

import androidx.compose.material3.Typography

/**
 * The MD3 type scale, unmodified.
 *
 * Compose's default [Typography] already carries the fifteen specified roles at
 * their specified sizes and weights. This app reads as dense status text and
 * lists, which the scale handles as-is; overriding it would mean maintaining
 * magic numbers for no gain.
 */
internal val AppTypography = Typography()
