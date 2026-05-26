package launcher.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── Animations ──────────────────────────────────────────────────────────────
val IosAppLaunchCurve = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
val IosAppLaunchDuration = 550

// ── Neutral dark tones (shared across all palettes) ─────────────────────────
private val darkBackground = Color(0xFF111113)
private val darkOnBackground = Color(0xFFE3E2E6)
private val darkSurface = Color(0xFF1B1B1F)
private val darkOnSurface = Color(0xFFE3E2E6)
private val darkSurfaceVariant = Color(0xFF44464F)
private val darkOnSurfaceVariant = Color(0xFFC5C6D0)
private val darkOutline = Color(0xFF8F9099)
private val darkOutlineVariant = Color(0xFF44464F)
private val darkInverseSurface = Color(0xFFE3E2E6)
private val darkInverseOnSurface = Color(0xFF303034)
private val darkSurfaceTint = Color(0xFFBEC2FF)
private val darkSurfaceContainerLowest = Color(0xFF0E0E11)
private val darkSurfaceContainerLow = Color(0xFF1B1B1F)
private val darkSurfaceContainer = Color(0xFF1F1F23)
private val darkSurfaceContainerHigh = Color(0xFF2A2A2E)
private val darkSurfaceContainerHighest = Color(0xFF353539)

private val darkError = Color(0xFFFFB4AB)
private val darkOnError = Color(0xFF690005)
private val darkErrorContainer = Color(0xFF93000A)
private val darkOnErrorContainer = Color(0xFFFFDAD6)

// ── Dynamic accent palettes ─────────────────────────────────────────────────
data class AccentPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val inversePrimary: Color,
)

// 莫奈紫 (Monet Purple) ──────────────────────────────────────────────────────
val AccentMonetPurple = AccentPalette(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    inversePrimary = Color(0xFF6750A4),
)

// 极客蓝 (Geek Blue) ─────────────────────────────────────────────────────────
val AccentGeekBlue = AccentPalette(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF233240),
    secondaryContainer = Color(0xFF394857),
    onSecondaryContainer = Color(0xFFD4E4F6),
    tertiary = Color(0xFFD5BDE5),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F60),
    onTertiaryContainer = Color(0xFFF1DAFF),
    inversePrimary = Color(0xFF1A6EB0),
)

// 薄荷绿 (Mint Green) ────────────────────────────────────────────────────────
val AccentMintGreen = AccentPalette(
    primary = Color(0xFF7EDBA2),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF005230),
    onPrimaryContainer = Color(0xFF9AF7BC),
    secondary = Color(0xFFB4CCBA),
    onSecondary = Color(0xFF203528),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),
    tertiary = Color(0xFFA1CDD8),
    onTertiary = Color(0xFF01363E),
    tertiaryContainer = Color(0xFF1F4D56),
    onTertiaryContainer = Color(0xFFBDE9F4),
    inversePrimary = Color(0xFF006D40),
)

// 琥珀橙 (Amber Orange) ───────────────────────────────────────────────────────
val AccentAmberOrange = AccentPalette(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4F2500),
    primaryContainer = Color(0xFF703800),
    onPrimaryContainer = Color(0xFFFFDCC0),
    secondary = Color(0xFFE2BFA2),
    onSecondary = Color(0xFF422B1A),
    secondaryContainer = Color(0xFF5B412E),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFFFB59E),
    onTertiary = Color(0xFF5C1F10),
    tertiaryContainer = Color(0xFF7B3524),
    onTertiaryContainer = Color(0xFFFFDBD0),
    inversePrimary = Color(0xFF9A4D00),
)

// 珊瑚红 (Coral Red) ─────────────────────────────────────────────────────────
val AccentCoralRed = AccentPalette(
    primary = Color(0xFFFFB3AE),
    onPrimary = Color(0xFF5A1114),
    primaryContainer = Color(0xFF7A2729),
    onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = Color(0xFFE4BCB9),
    onSecondary = Color(0xFF432927),
    secondaryContainer = Color(0xFF5C3F3D),
    onSecondaryContainer = Color(0xFFFFDAD7),
    tertiary = Color(0xFFEAC07B),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5F4100),
    onTertiaryContainer = Color(0xFFFFDEAE),
    inversePrimary = Color(0xFFB63A3F),
)

// 青瓷青 (Celadon Cyan) ──────────────────────────────────────────────────────
val AccentCeladonCyan = AccentPalette(
    primary = Color(0xFF7DD5D1),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504D),
    onPrimaryContainer = Color(0xFF9FF1ED),
    secondary = Color(0xFFB2CCCA),
    onSecondary = Color(0xFF1E3533),
    secondaryContainer = Color(0xFF354B49),
    onSecondaryContainer = Color(0xFFCDE8E5),
    tertiary = Color(0xFFA9C8F2),
    onTertiary = Color(0xFF0A3157),
    tertiaryContainer = Color(0xFF23486F),
    onTertiaryContainer = Color(0xFFD4E4FF),
    inversePrimary = Color(0xFF006A66),
)

// 石墨蓝 (Graphite Blue) ─────────────────────────────────────────────────────
val AccentGraphiteBlue = AccentPalette(
    primary = Color(0xFFB7C5FF),
    onPrimary = Color(0xFF1D2A61),
    primaryContainer = Color(0xFF34417A),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFFC2C6DD),
    onSecondary = Color(0xFF2C3148),
    secondaryContainer = Color(0xFF43475F),
    onSecondaryContainer = Color(0xFFDEE1FA),
    tertiary = Color(0xFFCDBEE7),
    onTertiary = Color(0xFF352B4A),
    tertiaryContainer = Color(0xFF4C4162),
    onTertiaryContainer = Color(0xFFE9DEFF),
    inversePrimary = Color(0xFF4A5893),
)

val AllAccents = listOf(
    AccentMonetPurple,
    AccentGeekBlue,
    AccentMintGreen,
    AccentAmberOrange,
    AccentCoralRed,
    AccentCeladonCyan,
    AccentGraphiteBlue,
)
val AccentNames = listOf(
    "莫奈紫",
    "极客蓝",
    "薄荷绿",
    "琥珀橙",
    "珊瑚红",
    "青瓷青",
    "石墨蓝",
)

// ── Global accent state ─────────────────────────────────────────────────────
object ThemeState {
    var accent by mutableStateOf(AccentMonetPurple)
    var isDark by mutableStateOf(true)
    var backgroundImagePath by mutableStateOf("")
    var backgroundBlurRadius by mutableStateOf(20)
    var backgroundBrightness by mutableStateOf(0.75f)  // 0=全黑, 1=原色
    var uiPanelOpacity by mutableStateOf(0.75f)       // 0=全透明, 1=完全不透明
    // 预加载的壁纸 bitmap（在 Main 启动时同步加载，消除首帧空白）
    var cachedBgBitmap by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var cachedBgKey by mutableStateOf("")  // 仅 bgPath，模糊由 GPU layer 实时处理
}

// ── Build dark color scheme from accent ─────────────────────────────────────
private fun buildDarkScheme(accent: AccentPalette): ColorScheme = darkColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary,
    onSecondary = accent.onSecondary,
    secondaryContainer = accent.secondaryContainer,
    onSecondaryContainer = accent.onSecondaryContainer,
    tertiary = accent.tertiary,
    onTertiary = accent.onTertiary,
    tertiaryContainer = accent.tertiaryContainer,
    onTertiaryContainer = accent.onTertiaryContainer,
    error = darkError,
    onError = darkOnError,
    errorContainer = darkErrorContainer,
    onErrorContainer = darkOnErrorContainer,
    background = darkBackground,
    onBackground = darkOnBackground,
    surface = darkSurface,
    onSurface = darkOnSurface,
    surfaceVariant = darkSurfaceVariant,
    onSurfaceVariant = darkOnSurfaceVariant,
    outline = darkOutline,
    outlineVariant = darkOutlineVariant,
    inverseSurface = darkInverseSurface,
    inverseOnSurface = darkInverseOnSurface,
    inversePrimary = accent.inversePrimary,
    surfaceTint = darkSurfaceTint,
)

// ── Build light color scheme from accent ────────────────────────────────────
private fun buildLightScheme(accent: AccentPalette): ColorScheme = lightColorScheme(
    primary = accent.inversePrimary, // Use darker inversePrimary for light mode
    onPrimary = Color.White,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondaryContainer,
    onSecondary = accent.onSecondaryContainer,
    secondaryContainer = accent.secondary,
    onSecondaryContainer = accent.onSecondary,
    tertiary = accent.tertiaryContainer,
    onTertiary = accent.onTertiaryContainer,
    tertiaryContainer = accent.tertiary,
    onTertiaryContainer = accent.onTertiary,
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E2E6),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    inversePrimary = accent.primary,
)

// ── Smooth animated transitions ─────────────────────────────────────────────
@Composable
fun animateColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 500)
    return target.copy(
        primary = animateColorAsState(target.primary, spec).value,
        onPrimary = animateColorAsState(target.onPrimary, spec).value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec).value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec).value,
        secondary = animateColorAsState(target.secondary, spec).value,
        onSecondary = animateColorAsState(target.onSecondary, spec).value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, spec).value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec).value,
        tertiary = animateColorAsState(target.tertiary, spec).value,
        onTertiary = animateColorAsState(target.onTertiary, spec).value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec).value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec).value,
        error = animateColorAsState(target.error, spec).value,
        surface = animateColorAsState(target.surface, spec).value,
        onSurface = animateColorAsState(target.onSurface, spec).value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, spec).value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec).value,
        outline = animateColorAsState(target.outline, spec).value,
        background = animateColorAsState(target.background, spec).value,
        onBackground = animateColorAsState(target.onBackground, spec).value,
    )
}

// ── Main theme composable ───────────────────────────────────────────────────
@Composable
fun MD3LTheme(content: @Composable () -> Unit) {
    val baseScheme = if (ThemeState.isDark) buildDarkScheme(ThemeState.accent) else buildLightScheme(ThemeState.accent)
    val animatedScheme = animateColorScheme(baseScheme)

    MaterialTheme(
        colorScheme = animatedScheme,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
