package dev.foulfoxhacks.betteryts.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * A colour set. Both palettes share the same shapes and type (square panels, heavy uppercase
 * display, tracked labels): the akasammythepuppy.me / Mello Zone family look.
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
    val Glow get() = palette.glow

    val TitleGradient get() = Brush.linearGradient(palette.title)
    val HeadingGradient get() = Brush.linearGradient(palette.heading)
    val ButtonGradient get() = Brush.linearGradient(palette.button)
    val CardGradient get() = Brush.linearGradient(palette.card)
}

/** Big uppercase display type, like the headings on akasammythepuppy.me and Mello Zone. */
fun displayStyle(size: Int, brush: Brush = Brand.TitleGradient) = TextStyle(
    brush = brush,
    fontSize = size.sp,
    lineHeight = (size * 0.95).sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-0.06).em,
)

val EyebrowStyle get() = TextStyle(color = Brand.Tan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.12.em)
val LabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em)

private val Square = RoundedCornerShape(0.dp)

@Composable
fun BetterYTSTheme(content: @Composable () -> Unit) {
    val p = Brand.palette
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            secondary = p.violet,
            onSecondary = Color.White,
            tertiary = p.hot,
            background = p.ink,
            onBackground = p.paper,
            surface = p.surface,
            onSurface = p.paper,
            surfaceVariant = p.raised,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.selected,
            outline = p.line,
            outlineVariant = p.line,
            error = Color(0xFFFF6B6B),
        ),
        typography = Typography(
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = LabelStyle,
        ),
        shapes = Shapes(extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square),
        content = content,
    )
}
