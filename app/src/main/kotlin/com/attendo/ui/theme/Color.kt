package com.attendo.ui.theme

import androidx.compose.ui.graphics.Color

// A plum/maroon primary, taken from the launcher icon. Deliberately not Compose's
// default purple, and deliberately not dynamic colour: the attendance bands below have to
// stay legible against the surfaces, and a wallpaper-derived palette cannot promise that.

internal val Plum40 = Color(0xFF8E4A57)
internal val Plum80 = Color(0xFFFFB1C0)
internal val Plum90 = Color(0xFFFFD9DF)
internal val Plum30 = Color(0xFF712B39)
internal val Plum20 = Color(0xFF55111F)
internal val Plum10 = Color(0xFF3B0716)

internal val Clay40 = Color(0xFF75565B)
internal val Clay80 = Color(0xFFE4BDC3)
internal val Clay90 = Color(0xFFFFD9DF)
internal val Clay30 = Color(0xFF5C3F44)
internal val Clay20 = Color(0xFF442A2F)

internal val Ochre40 = Color(0xFF7A5732)
internal val Ochre80 = Color(0xFFEDBE8D)
internal val Ochre90 = Color(0xFFFFDDBB)
internal val Ochre30 = Color(0xFF5F401D)
internal val Ochre20 = Color(0xFF442B08)

internal val LightBackground = Color(0xFFFCF8F8)
internal val LightSurfaceVariant = Color(0xFFF3DDE0)
internal val LightOnSurface = Color(0xFF22191B)
internal val LightOnSurfaceVariant = Color(0xFF524345)
internal val LightOutline = Color(0xFF857376)
internal val LightOutlineVariant = Color(0xFFD7C1C4)

internal val DarkBackground = Color(0xFF141112)
internal val DarkSurfaceVariant = Color(0xFF524345)
internal val DarkOnSurface = Color(0xFFF0DEE0)
internal val DarkOnSurfaceVariant = Color(0xFFD6C1C4)
internal val DarkOutline = Color(0xFF9F8C8F)
internal val DarkOutlineVariant = Color(0xFF524345)

internal val ErrorLight = Color(0xFFBA1A1A)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)
internal val ErrorDark = Color(0xFFFFB4AB)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

// ---- attendance bands ------------------------------------------------------
//
// One colour per DayMark. These are semantic, not decorative: green means every held
// unit was attended, amber means *some* were — the half-attended two-hour block that the
// whole app exists to represent honestly — and red means none were. Cancelled and
// unreviewed days get their own muted bands so neither can be mistaken for an absence.

internal val FullLight = Color(0xFF1E6B3A)
internal val FullContainerLight = Color(0xFFC3EDCF)
internal val FullDark = Color(0xFF7BDC99)
internal val FullContainerDark = Color(0xFF20472C)

internal val PartialLight = Color(0xFF8A5300)
internal val PartialContainerLight = Color(0xFFFFDFB4)
internal val PartialDark = Color(0xFFFFC46B)
internal val PartialContainerDark = Color(0xFF4B3200)

internal val AbsentLight = Color(0xFFB3261E)
internal val AbsentContainerLight = Color(0xFFFFDAD6)
internal val AbsentDark = Color(0xFFFFB4AB)
internal val AbsentContainerDark = Color(0xFF5F1512)

internal val PendingLight = Color(0xFF2F5EA8)
internal val PendingContainerLight = Color(0xFFD9E2FF)
internal val PendingDark = Color(0xFFAFC6FF)
internal val PendingContainerDark = Color(0xFF1E3C6D)

// A day that happened but is outside the window attendance is counted from: before the
// student joined, or in a semester that is no longer the current one. Neutral rather than
// muted-plum, so it cannot be read as a cancellation, and filled rather than transparent, so
// it cannot be read as a day with no classes. Both of those days exist on the same calendar
// and mean different things.

internal val NotCountedLight = Color(0xFF6B6467)
internal val NotCountedContainerLight = Color(0xFFE8E2E3)
internal val NotCountedDark = Color(0xFFB4ACAE)
internal val NotCountedContainerDark = Color(0xFF302C2D)
