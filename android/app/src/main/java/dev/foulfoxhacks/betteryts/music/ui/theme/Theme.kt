package dev.foulfoxhacks.betteryts.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** akasammythepuppy.me palette (same tokens as the BetterYTS site). */
object Brand {
    val Ink = Color(0xFF050302)
    val Surface = Color(0xFF120C08)
    val Raised = Color(0xFF1B120B)
    val Selected = Color(0xFF2C1B0D)
    val Paper = Color(0xFFFFF7E7)
    val Muted = Color(0xFFCDBDA9)
    val Gold = Color(0xFFFFC857)
    val GoldInk = Color(0xFF1D1005)
    val Tan = Color(0xFFF0C890)
    val Copper = Color(0xFFB87333)
    val Orange = Color(0xFFFF994A)
    val Ember = Color(0xFFFF681E)
    val Violet = Color(0xFF9B6CFF)
    val Pink = Color(0xFFFF8DD0)
    val Line = Color(0x29FFFFFF)
    val LineGold = Color(0x94FFC857)

    val TitleGradient = Brush.linearGradient(listOf(Color(0xFFFFF7E7), Tan, Copper, Color(0xFFFF8B42), Color(0xFFFFD36E)))
    val HeadingGradient = Brush.linearGradient(listOf(Color(0xFFFFF7E7), Tan, Violet, Ember))
    val ButtonGradient = Brush.linearGradient(listOf(Color(0x428B451D), Color(0x3DB87333), Color(0x24FF681E)))
    val CardGradient = Brush.linearGradient(listOf(Color(0xF71E1008), Color(0xFC080402)))
}

/** Big uppercase display type, like the headings on akasammythepuppy.me. */
fun displayStyle(size: Int, brush: Brush = Brand.TitleGradient) = TextStyle(
    brush = brush,
    fontSize = size.sp,
    lineHeight = (size * 0.95).sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-0.06).em,
)

val EyebrowStyle = TextStyle(color = Brand.Tan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.12.em)
val LabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em)

private val Square = RoundedCornerShape(0.dp)

@Composable
fun BetterYTSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Gold,
            onPrimary = Brand.GoldInk,
            secondary = Brand.Violet,
            onSecondary = Color.White,
            tertiary = Brand.Ember,
            background = Brand.Ink,
            onBackground = Brand.Paper,
            surface = Brand.Surface,
            onSurface = Brand.Paper,
            surfaceVariant = Brand.Raised,
            onSurfaceVariant = Brand.Muted,
            surfaceContainer = Brand.Surface,
            surfaceContainerHigh = Brand.Raised,
            surfaceContainerHighest = Brand.Selected,
            outline = Brand.Line,
            outlineVariant = Brand.Line,
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
