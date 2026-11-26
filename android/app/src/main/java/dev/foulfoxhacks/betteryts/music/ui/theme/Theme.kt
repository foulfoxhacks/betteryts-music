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
)

object Palettes {
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

    val all = listOf(Ember, Mello)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Ember
}

/**
 * The active palette, readable anywhere. Reads go through Compose snapshot state, so switching
 * the theme recomposes everything that uses a colour.
 */
object Brand {
    var palette by mutableStateOf(Palettes.Ember)

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
    MaterialTheme(
        colorScheme = darkColorScheme(
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
        ),
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
        content = content,
    )
}
