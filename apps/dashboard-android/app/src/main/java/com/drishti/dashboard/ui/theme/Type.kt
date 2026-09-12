package com.drishti.dashboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.drishti.dashboard.R

/**
 * Three families, bundled rather than downloaded.
 *
 * **Fraunces** for anything that names a thing — a person, a section, a count.
 * It is a soft humanist serif with real optical-size drawing, and it makes the
 * screen read like a printed duty sheet rather than a console.
 *
 * **Plus Jakarta Sans** for everything else. It is a warm humanist sans that
 * stays legible at the sizes this app uses and, unlike the platform default,
 * has a true 800 weight for the numbers that matter.
 *
 * **Bricolage Grotesque** for exactly one string: the wordmark in the
 * masthead. A single ExtraBold instance at its 96pt optical size — a face
 * with more personality than the two workhorses, used once, so it reads as a
 * mark rather than as a third text style competing with them.
 *
 * All are OFL and shipped as static instances in `res/font`; nothing here
 * needs Play Services, a font provider, or a network, so the type looks the
 * same on a field phone with no SIM as it does on a demo bench.
 *
 * The scale starts where most apps end. Body text is 18sp, not 14sp, because
 * this is read at arm's length, often by someone standing up, often by someone
 * whose own sight is not perfect either.
 */

val Fraunces = FontFamily(
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)

val Bricolage = FontFamily(
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

val DashboardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.015).em,
    ),
    displayMedium = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.012).em,
    ),
    displaySmall = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 27.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.06.em,
    ),
    labelSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.09.em,
    ),
)

/** Big tabular-ish counts for the summary tiles. */
val NumeralStyle = TextStyle(
    fontFamily = Jakarta,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = (-0.02).em,
    textAlign = TextAlign.Start,
)

/** The masthead wordmark. Black, and sized to keep the bar exactly as tall as before. */
val WordmarkStyle = TextStyle(
    fontFamily = Bricolage,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 25.sp,
    lineHeight = 29.sp,
    letterSpacing = (-0.03).em,
)
