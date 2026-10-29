package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// 1. Readex Pro: Crisp, modern geometric Arabic UI font for headlines & titles
val ReadexProFontFamily = FontFamily(
    Font(R.font.readex_pro, FontWeight.Normal),
    Font(R.font.readex_pro, FontWeight.Medium),
    Font(R.font.readex_pro, FontWeight.SemiBold),
    Font(R.font.readex_pro, FontWeight.Bold)
)

// 2. IBM Plex Sans Arabic: High-legibility neo-grotesque Arabic UI font for body & labels
val IbmPlexSansArabicFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Bold)
)

// 3. Tajawal: Balanced, warm modern Arabic Sans-Serif
val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal, FontWeight.Normal),
    Font(R.font.tajawal, FontWeight.Medium),
    Font(R.font.tajawal, FontWeight.SemiBold),
    Font(R.font.tajawal, FontWeight.Bold)
)

// 4. Almarai: Clean pedagogical and instructional Arabic UI font
val AlmaraiFontFamily = FontFamily(
    Font(R.font.almarai, FontWeight.Normal),
    Font(R.font.almarai, FontWeight.Medium),
    Font(R.font.almarai, FontWeight.SemiBold),
    Font(R.font.almarai, FontWeight.Bold)
)

// 5. Amiri: Classic Arabic Naskh Typeface (used for official Jadhadha document preview & classic theme)
val AmiriFontFamily = FontFamily(
    Font(R.font.amiri, FontWeight.Normal),
    Font(R.font.amiri, FontWeight.Bold)
)

// 6. Cairo: Legacy Arabic UI Font
val CairoFontFamily = FontFamily(
    Font(R.font.cairo, FontWeight.Normal),
    Font(R.font.cairo, FontWeight.Medium),
    Font(R.font.cairo, FontWeight.SemiBold),
    Font(R.font.cairo, FontWeight.Bold)
)

/**
 * Application UI font presets (affects the Android application interface only,
 * never altering the exported .docx Jadhadha files).
 */
enum class AppUiFont(
    val label: String,
    val shortLabel: String
) {
    READEX_PRO(
        label = "خط ريدكس برو + آي بي إم (الافتراضي الحديث)",
        shortLabel = "ريدكس برو"
    ),
    TAJAWAL(
        label = "خط تجوال المتزن",
        shortLabel = "تجوال"
    ),
    ALMARAI(
        label = "خط المراعي التربوي",
        shortLabel = "المراعي"
    ),
    IBM_PLEX(
        label = "خط آي بي إم العربي",
        shortLabel = "آي بي إم"
    ),
    AMIRI(
        label = "خط الأميري الكلاسيكي",
        shortLabel = "الأميري"
    ),
    CAIRO(
        label = "خط القاهرة",
        shortLabel = "القاهرة"
    );

    val headingFamily: FontFamily
        get() = when (this) {
            READEX_PRO -> ReadexProFontFamily
            TAJAWAL -> TajawalFontFamily
            ALMARAI -> AlmaraiFontFamily
            IBM_PLEX -> IbmPlexSansArabicFontFamily
            AMIRI -> AmiriFontFamily
            CAIRO -> CairoFontFamily
        }

    val bodyFamily: FontFamily
        get() = when (this) {
            READEX_PRO -> IbmPlexSansArabicFontFamily
            TAJAWAL -> TajawalFontFamily
            ALMARAI -> AlmaraiFontFamily
            IBM_PLEX -> IbmPlexSansArabicFontFamily
            AMIRI -> AmiriFontFamily
            CAIRO -> CairoFontFamily
        }
}

fun createAppTypography(appFont: AppUiFont = AppUiFont.READEX_PRO): Typography {
    val headingFont = appFont.headingFamily
    val bodyFont = appFont.bodyFamily
    return Typography(
        displayLarge = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 42.sp
        ),
        displayMedium = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 36.sp
        ),
        displaySmall = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 30.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
            lineHeight = 29.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 26.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp
        ),
        titleLarge = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 26.sp
        ),
        titleMedium = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 22.sp
        ),
        titleSmall = TextStyle(
            fontFamily = headingFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 19.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 21.sp
        ),
        bodySmall = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 18.sp
        ),
        labelMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    )
}

val AppTypography = createAppTypography(AppUiFont.READEX_PRO)
