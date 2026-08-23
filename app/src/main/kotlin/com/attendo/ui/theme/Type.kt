package com.attendo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3's defaults, with the numeric styles tightened.
 *
 * Percentages are the thing being read on almost every screen, so [displayLarge] and
 * [headlineMedium] are set a little heavier and with tighter letter spacing than the
 * default, and the tabular figures below are what stop "73.5%" from jittering to "100%"
 * mid-animation.
 */
val AttendoTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
        ),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** For figures that change in place — a running percentage, a unit count. */
val NumericStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)
