package com.shai.riven.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shai.riven.R

val LatoFamily = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
)

val Typography = Typography(
    displaySmall = Typography().displaySmall.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
    ),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = LatoFamily),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = LatoFamily),
    bodySmall = Typography().bodySmall.copy(fontFamily = LatoFamily),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontFamily = LatoFamily,
        fontWeight = FontWeight.Bold,
    ),
    labelSmall = Typography().labelSmall.copy(fontFamily = LatoFamily),
)
