package com.monopoly.android.ui.theme

import androidx.compose.ui.graphics.Color

// The board's own colours. These are fixed rather than theme-derived: a
// physical Monopoly board does not change colour with the light, and the
// printed group colours have to stay recognisable to anyone who has played.
val BoardFace = Color(0xFFC6DFC8)
val BoardEdge = Color(0xFF14281D)
val SpaceFace = Color(0xFFF8F6EE)
val ChanceOrange = Color(0xFFF4842A)
val ChestBlue = Color(0xFF6EC6E8)

val MonopolyRed = Color(0xFFC8102E)
val MonopolyGreen = Color(0xFF1B5E3F)
val MonopolyCream = Color(0xFFFBF7EC)

// App chrome, which does follow the system theme.
val GreenPrimary = Color(0xFF1B5E3F)
val GreenPrimaryDark = Color(0xFF7ED2A6)
val RedAccent = Color(0xFFC8102E)
val RedAccentDark = Color(0xFFFF8A93)
val SurfaceLight = Color(0xFFFBF7EC)
val SurfaceDark = Color(0xFF141815)
val OnSurfaceLight = Color(0xFF1A1C1A)
val OnSurfaceDark = Color(0xFFE3E3DE)

// Solid, pre-mixed faces for the two card squares. Mixing these by hand rather
// than laying a translucent tint over the board matters: the squares sit on
// green felt, and see-through orange over green comes out olive.
val ChanceFace = Color(0xFFFBE3CC)
val ChestFace = Color(0xFFD6ECF7)
