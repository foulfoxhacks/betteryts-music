package dev.foulfoxhacks.betteryts.music.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A colour set. Both palettes share the same clean, flat look as the Mello Zone app: rounded
 * Material shapes, sentence-case headlines, tracked uppercase eyebrows, pill buttons, no frames.
 */
data class Palette(
    val id: String,
    val ink: Color,
    val surface: Color,
    val raised: Color,
    val selected: Color,
    val paper: Color,
    val muted: Color,
    /** Primary accent: buttons, active states, progress. */
    val accent: Color,
    val onAccent: Color,
    /** Eyebrow labels. */
    val eyebrow: Color,
    val warm: Color,
    val hot: Color,
    val violet: Color,
    /** Favourite hearts. */
    val love: Color,
    val line: Color,
    val lineAccent: Color,
    val title: List<Color>,
    val heading: List<Color>,
    val button: List<Color>,
    val card: List<Color>,
    /** Soft glow behind headers and the player. */
    val glow: Color,
    /** Filled container for the selected tab pill and other emphasised surfaces. */
    val container: Color,
    val onContainer: Color,
    /** A light theme: dark text, dark system-bar icons. */
    val light: Boolean = false,
)

object Palettes {
    /** The BetterYTS logo: orange play, blue and cyan strokes, violet accents on midnight navy. */
    val BetterYTS = Palette(
        id = "betteryts",
        ink = Color(0xFF0B1020), surface = Color(0xFF111A33), raised = Color(0xFF172246), selected = Color(0xFF1E2F63),
        paper = Color(0xFFF2F5FF), muted = Color(0xFFB4BDD8),
        accent = Color(0xFFFF6A00), onAccent = Color(0xFF0B1020), eyebrow = Color(0xFF5CC8F0),
        warm = Color(0xFF0A84FF), hot = Color(0xFFFF6A00), violet = Color(0xFF6D28D9), love = Color(0xFFFF8A3D),
        line = Color(0x29FFFFFF), lineAccent = Color(0x94FF6A00),
        title = listOf(Color(0xFFF2F5FF), Color(0xFF00A3E0), Color(0xFF0A84FF), Color(0xFF6D28D9), Color(0xFFFF6A00)),
        heading = listOf(Color(0xFFF2F5FF), Color(0xFF00A3E0), Color(0xFF6D28D9), Color(0xFFFF6A00)),
        button = listOf(Color(0x4D0A84FF), Color(0x386D28D9), Color(0x24FF6A00)),
        card = listOf(Color(0xF7111A33), Color(0xFC0B1020)),
        glow = Color(0x590A84FF),
        container = Color(0xFF08205B), onContainer = Color(0xFFF2F5FF),
    )

    /** True black for OLED screens, with the logo's cyan. */
    val Amoled = Palette(
        id = "amoled",
        ink = Color(0xFF000000), surface = Color(0xFF0A0A0A), raised = Color(0xFF141414), selected = Color(0xFF1F2A33),
        paper = Color(0xFFF5F5F5), muted = Color(0xFFB0B0B0),
        accent = Color(0xFF00A3E0), onAccent = Color(0xFF000000), eyebrow = Color(0xFF5CC8F0),
        warm = Color(0xFF0A84FF), hot = Color(0xFFFF6A00), violet = Color(0xFF7C3AED), love = Color(0xFFFF7AB6),
        line = Color(0x26FFFFFF), lineAccent = Color(0x9400A3E0),
        title = listOf(Color(0xFFF5F5F5), Color(0xFF00A3E0), Color(0xFF0A84FF)),
        heading = listOf(Color(0xFFF5F5F5), Color(0xFF00A3E0), Color(0xFF7C3AED), Color(0xFFFF6A00)),
        button = listOf(Color(0x3300A3E0), Color(0x220A84FF), Color(0x1AFF6A00)),
        card = listOf(Color(0xFF0A0A0A), Color(0xFF000000)),
        glow = Color(0x4000A3E0),
        container = Color(0xFF003B57), onContainer = Color.White,
    )

    /** akasammythepuppy.me: gold, ember and copper on warm ink. */
    val Ember = Palette(
        id = "ember",
        ink = Color(0xFF050302), surface = Color(0xFF120C08), raised = Color(0xFF1B120B), selected = Color(0xFF2C1B0D),
        paper = Color(0xFFFFF7E7), muted = Color(0xFFCDBDA9),
        accent = Color(0xFFFFC857), onAccent = Color(0xFF1D1005), eyebrow = Color(0xFFF0C890),
        warm = Color(0xFFB87333), hot = Color(0xFFFF681E), violet = Color(0xFF9B6CFF), love = Color(0xFFFF8DD0),
        line = Color(0x29FFFFFF), lineAccent = Color(0x94FFC857),
        title = listOf(Color(0xFFFFF7E7), Color(0xFFF0C890), Color(0xFFB87333), Color(0xFFFF8B42), Color(0xFFFFD36E)),
        heading = listOf(Color(0xFFFFF7E7), Color(0xFFF0C890), Color(0xFF9B6CFF), Color(0xFFFF681E)),
        button = listOf(Color(0x428B451D), Color(0x3DB87333), Color(0x24FF681E)),
        card = listOf(Color(0xF71E1008), Color(0xFC080402)),
        glow = Color(0x59FF681E),
        container = Color(0xFF5A3413), onContainer = Color(0xFFFFF7E7),
    )

    /** Mello Zone: violet, pink and sky blue on deep navy. */
    val Mello = Palette(
        id = "mello",
        ink = Color(0xFF070712), surface = Color(0xFF101020), raised = Color(0xFF181830), selected = Color(0xFF261C46),
        paper = Color(0xFFF7F3FF), muted = Color(0xFFC9C0DC),
        accent = Color(0xFFEF8FCB), onAccent = Color(0xFF070712), eyebrow = Color(0xFFA8C7FF),
        warm = Color(0xFF7637FF), hot = Color(0xFF3F86FF), violet = Color(0xFF7637FF), love = Color(0xFFF4B8E4),
        line = Color(0x29FFFFFF), lineAccent = Color(0x94EF8FCB),
        title = listOf(Color(0xFFF7F3FF), Color(0xFFF4B8E4), Color(0xFFEF8FCB), Color(0xFFA8C7FF)),
        heading = listOf(Color(0xFFF7F3FF), Color(0xFFA8C7FF), Color(0xFF7637FF), Color(0xFFEF8FCB)),
        button = listOf(Color(0x4D7637FF), Color(0x38135CFF), Color(0x24EF8FCB)),
        card = listOf(Color(0xF7141432), Color(0xFC08081A)),
        glow = Color(0x597637FF),
        container = Color(0xFF135CFF), onContainer = Color.White,
    )

    /** Light: navy text on near-white, with the logo's orange and blue. */
    val Daylight = Palette(
        id = "daylight",
        ink = Color(0xFFF6F7FB), surface = Color(0xFFFFFFFF), raised = Color(0xFFEDEFF6), selected = Color(0xFFDDE5FF),
        paper = Color(0xFF0B1020), muted = Color(0xFF4A5372),
        accent = Color(0xFFC2410C), onAccent = Color.White, eyebrow = Color(0xFF0A5FBF),
        warm = Color(0xFF0A84FF), hot = Color(0xFFB91C1C), violet = Color(0xFF6D28D9), love = Color(0xFFDB2777),
        line = Color(0x1F0B1020), lineAccent = Color(0x94C2410C),
        title = listOf(Color(0xFF0B1020), Color(0xFF0A5FBF), Color(0xFF6D28D9), Color(0xFFC2410C)),
        heading = listOf(Color(0xFF0B1020), Color(0xFF0A5FBF), Color(0xFF6D28D9), Color(0xFFC2410C)),
        button = listOf(Color(0x1A0A84FF), Color(0x146D28D9), Color(0x10C2410C)),
        card = listOf(Color(0xFFFFFFFF), Color(0xFFF6F7FB)),
        glow = Color(0x330A84FF),
        container = Color(0xFFDDE5FF), onContainer = Color(0xFF0B1020),
        light = true,
    )

    val all = listOf(BetterYTS, Amoled, Daylight, Ember, Mello)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: BetterYTS

    /** Swatches for Settings › Personalize › Accent. */
    val accents = listOf(0xFFFF6A00, 0xFF0A84FF, 0xFF00A3E0, 0xFF7C3AED, 0xFFFF8DD0, 0xFFFFC857, 0xFF22C55E, 0xFFEF4444)

    /** A palette with its accent swapped (keeping readable text on the accent). */
    fun withAccent(p: Palette, argb: Long?): Palette {
        if (argb == null) return p
        val c = Color(argb)
        val onAccent = if (c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f > 0.6f) Color(0xFF0B1020) else Color.White
        return p.copy(accent = c, onAccent = onAccent, lineAccent = c.copy(alpha = 0.58f), love = c)
    }
}

/**
 * The active palette, readable anywhere. Reads go through Compose snapshot state, so switching
 * the theme recomposes everything that uses a colour.
 */
object Brand {
    var palette by mutableStateOf(Palettes.BetterYTS)

    /** Layout and style choices (Settings › Customize). */
    var look by mutableStateOf(Look())

    val Ink get() = palette.ink
    val Surface get() = palette.surface
    val Raised get() = palette.raised
    val Selected get() = palette.selected
    val Paper get() = palette.paper
    val Muted get() = palette.muted
    /** The accent colour (gold in Ember, pink in Mello). */
    val Gold get() = palette.accent
    val GoldInk get() = palette.onAccent
    val Tan get() = palette.eyebrow
    val Copper get() = palette.warm
    val Ember get() = palette.hot
    val Violet get() = palette.violet
    val Pink get() = palette.love
    val Line get() = palette.line
    val LineGold get() = palette.lineAccent
    /** Flat look: the old header glow is kept as a token but drawn transparent. */
    val Glow get() = Color.Transparent
    val Container get() = palette.container
    val OnContainer get() = palette.onContainer

    // Flat surfaces and solid type, like Mello Zone. The gradient lists stay in each palette
    // (theme swatches use them) but these brushes are now solid.
    val TitleGradient: Brush get() = SolidColor(palette.paper)
    val HeadingGradient: Brush get() = SolidColor(palette.raised)
    val ButtonGradient: Brush get() = SolidColor(palette.raised)
    val CardGradient: Brush get() = SolidColor(palette.surface)
}

/** Headline type, Mello Zone style: heavy, tight, sentence case, one solid colour. */
fun displayStyle(size: Int, brush: Brush = Brand.TitleGradient) = TextStyle(
    brush = brush,
    fontSize = size.sp,
    lineHeight = (size * 1.12).sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-0.5).sp,
)

/** Uppercase, tracked section label in the eyebrow colour ("COMMUNITY PLATFORMS"). */
val EyebrowStyle get() = TextStyle(color = Brand.Tan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
val LabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
/** Button and menu text: sentence case, no tracking. */
val ButtonTextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

/** Rounded corners everywhere; cards and images use `medium`/`large`. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BetterYTSTheme(content: @Composable () -> Unit) {
    val p = Brand.palette
    val scheme = if (p.light) androidx.compose.material3.lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.container, onPrimaryContainer = p.onContainer,
        secondaryContainer = p.raised, onSecondaryContainer = p.eyebrow, secondary = p.violet, onSecondary = Color.White, tertiary = p.hot,
        background = p.ink, onBackground = p.paper, surface = p.surface, onSurface = p.paper, surfaceVariant = p.raised, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.ink, surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.selected,
        outline = p.muted.copy(alpha = 0.45f), outlineVariant = p.line, error = Color(0xFFB91C1C),
    ) else darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.container,
            onPrimaryContainer = p.onContainer,
            secondaryContainer = p.raised,
            onSecondaryContainer = p.eyebrow,
            secondary = p.violet,
            onSecondary = Color.White,
            tertiary = p.hot,
            background = p.ink,
            onBackground = p.paper,
            surface = p.surface,
            onSurface = p.paper,
            surfaceVariant = p.raised,
            onSurfaceVariant = p.muted,
            surfaceContainerLowest = p.ink,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.selected,
            outline = p.muted.copy(alpha = 0.45f),
            outlineVariant = p.line,
            error = Color(0xFFFF6B6B),
        )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography().run {
            copy(
                displaySmall = displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
                headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.3).sp),
                headlineSmall = headlineSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
                titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
                titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
                bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
                bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                labelLarge = labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                labelMedium = labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
            )
        },
        shapes = AppShapes,
    ) {
        // Settings › Customize › Text size scales every sp on top of the system's own setting.
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * Brand.look.fontScale),
            content = content,
        )
    }
}
