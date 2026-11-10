package com.deskpet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A softer, more legible hierarchy for dense settings on narrow phones. */
val Typography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 35.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)
