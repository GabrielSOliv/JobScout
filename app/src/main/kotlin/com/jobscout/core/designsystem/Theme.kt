package com.jobscout.core.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jobscout.R

object ScoutColors {
    val Blue = Color(0xFF175CD3)
    val Teal = Color(0xFF0F766E)
    val Petrol = Color(0xFF0B4850)
    val Mint = Color(0xFF95F0D2)
    val OnMint = Color(0xFF073E38)
    val OnPetrol = Color(0xFFC5E8E2)
}

// Define every role: Material defaults otherwise reintroduce purple cards, chips and dialogs.
private val Light = lightColorScheme(
    primary = ScoutColors.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDFF), onPrimaryContainer = Color(0xFF103A78),
    inversePrimary = Color(0xFFA9C7FF),
    secondary = ScoutColors.Teal, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EFE8), onSecondaryContainer = ScoutColors.Petrol,
    tertiary = Color(0xFF146C94), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDBF1FA), onTertiaryContainer = Color(0xFF104B65),
    background = Color.White, onBackground = Color(0xFF123645),
    surface = Color.White, onSurface = Color(0xFF123645),
    surfaceVariant = Color(0xFFEAF2F5), onSurfaceVariant = Color(0xFF496570),
    surfaceTint = ScoutColors.Blue, inverseSurface = Color(0xFF203B45), inverseOnSurface = Color(0xFFEAF2F5),
    outline = Color(0xFF6A8290), outlineVariant = Color(0xFFCDDEE4), scrim = Color.Black,
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceBright = Color.White, surfaceDim = Color(0xFFD7E3E8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F9FB),
    surfaceContainer = Color(0xFFF0F6F8), surfaceContainerHigh = Color(0xFFEAF2F5),
    surfaceContainerHighest = Color(0xFFE2EDF1),
    primaryFixed = Color(0xFFE3EDFF), primaryFixedDim = Color(0xFFA9C7FF),
    onPrimaryFixed = Color(0xFF103A78), onPrimaryFixedVariant = Color(0xFF174DA4),
    secondaryFixed = Color(0xFFD2EFE8), secondaryFixedDim = Color(0xFF94D9C9),
    onSecondaryFixed = ScoutColors.Petrol, onSecondaryFixedVariant = Color(0xFF115D56),
    tertiaryFixed = Color(0xFFDBF1FA), tertiaryFixedDim = Color(0xFFA0D4ED),
    onTertiaryFixed = Color(0xFF104B65), onTertiaryFixedVariant = Color(0xFF146C94),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF0B3045),
    primaryContainer = Color(0xFF164C80), onPrimaryContainer = Color(0xFFE3EDFF),
    inversePrimary = ScoutColors.Blue,
    secondary = Color(0xFF8CD9C5), onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF164C54), onSecondaryContainer = Color(0xFFD2EFE8),
    tertiary = Color(0xFFA0D4ED), onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF104B65), onTertiaryContainer = Color(0xFFDBF1FA),
    background = Color(0xFF0D1C22), onBackground = Color(0xFFDCE9EE),
    surface = Color(0xFF0D1C22), onSurface = Color(0xFFDCE9EE),
    surfaceVariant = Color(0xFF30454F), onSurfaceVariant = Color(0xFFBCD0D8),
    surfaceTint = Color(0xFFA9C7FF), inverseSurface = Color(0xFFDCE9EE), inverseOnSurface = Color(0xFF203B45),
    outline = Color(0xFF879EA8), outlineVariant = Color(0xFF3C535D), scrim = Color.Black,
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surfaceBright = Color(0xFF30454F), surfaceDim = Color(0xFF0D1C22),
    surfaceContainerLowest = Color(0xFF08161C), surfaceContainerLow = Color(0xFF13252D),
    surfaceContainer = Color(0xFF172B34), surfaceContainerHigh = Color(0xFF21363F),
    surfaceContainerHighest = Color(0xFF2C414A),
    primaryFixed = Light.primaryFixed, primaryFixedDim = Light.primaryFixedDim,
    onPrimaryFixed = Light.onPrimaryFixed, onPrimaryFixedVariant = Light.onPrimaryFixedVariant,
    secondaryFixed = Light.secondaryFixed, secondaryFixedDim = Light.secondaryFixedDim,
    onSecondaryFixed = Light.onSecondaryFixed, onSecondaryFixedVariant = Light.onSecondaryFixedVariant,
    tertiaryFixed = Light.tertiaryFixed, tertiaryFixedDim = Light.tertiaryFixedDim,
    onTertiaryFixed = Light.onTertiaryFixed, onTertiaryFixedVariant = Light.onTertiaryFixedVariant,
)
private val Brand = FontFamily(Font(R.font.poppins_regular), Font(R.font.poppins_semibold, FontWeight.SemiBold))
private val Base = Typography()
private val BrandTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = Brand), displayMedium = Base.displayMedium.copy(fontFamily = Brand), displaySmall = Base.displaySmall.copy(fontFamily = Brand),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Brand, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Brand, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Brand, fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = Brand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontFamily = Brand, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Brand, fontSize = 14.sp, lineHeight = 21.sp), bodyMedium = Base.bodyMedium.copy(fontFamily = Brand, fontSize = 13.sp, lineHeight = 19.sp), bodySmall = Base.bodySmall.copy(fontFamily = Brand, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = Base.labelLarge.copy(fontFamily = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), labelMedium = Base.labelMedium.copy(fontFamily = Brand, fontSize = 11.sp), labelSmall = Base.labelSmall.copy(fontFamily = Brand, fontSize = 10.sp),
)

@Composable
fun JobScoutTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = BrandTypography, content = content)
}
