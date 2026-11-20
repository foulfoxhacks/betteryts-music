package dev.foulfoxhacks.betteryts.music.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.displayStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Cold-start intro: a fox fades in and swishes its tail; a last big swish sweeps the wordmark into
 * view, then everything fades into the app. About two seconds; tap to skip. Skipped when the
 * system's animations are off.
 */
@Composable
fun FoxSplash(onDone: () -> Unit) {
    val context = LocalContext.current
    val animationsOff = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val appear = remember { Animatable(0f) }
    val tail = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (animationsOff) return@LaunchedEffect onDone()
        coroutineScope {
            launch { appear.animateTo(1f, tween(420, easing = LinearOutSlowInEasing)) }
            tail.animateTo(-16f, tween(200, delayMillis = 180, easing = FastOutSlowInEasing))
            tail.animateTo(20f, tween(230, easing = FastOutSlowInEasing))
            tail.animateTo(-10f, tween(200, easing = FastOutSlowInEasing))
            // The big swish: the tail sweeps across and the wordmark follows it in.
            launch { reveal.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
            tail.animateTo(34f, tween(260, easing = FastOutSlowInEasing))
            tail.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        exit.animateTo(0f, tween(320))
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(exit.value)
            .background(Brand.Ink)
            .background(Brush.radialGradient(listOf(Brand.Glow, Color.Transparent), radius = 900f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        val r = reveal.value
        // The fox starts centred and slides up as the wordmark comes in below it.
        Fox(
            tailAngle = tail.value,
            modifier = Modifier
                .size(176.dp)
                .offset(y = (-58).dp * r)
                .graphicsLayer {
                    val s = 0.82f + 0.18f * appear.value - 0.12f * r
                    scaleX = s
                    scaleY = s
                    alpha = appear.value
                },
        )
        Column(
            Modifier
                .offset(y = 92.dp)
                .alpha(r)
                .drawWithContent { clipRect(right = size.width * r) { this@drawWithContent.drawContent() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("BETTERYTS", style = displayStyle(44, Brush.linearGradient(Brand.palette.title)))
            Text("MUSIC · BY FOULFOXHACKS", style = LabelStyle, color = Brand.Tan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** An original sitting fox in the app's palette; the tail pivots at its base. */
@Composable
private fun Fox(tailAngle: Float, modifier: Modifier = Modifier) {
    val fur = Brand.Ember
    val furDark = Brand.Copper
    val cream = Brand.Paper
    val ink = Brand.Ink
    val gold = Brand.Gold
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        fun Path.m(x: Float, y: Float) = moveTo(x * w, y * h)
        fun Path.l(x: Float, y: Float) = lineTo(x * w, y * h)
        fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * w, y1 * h, x2 * w, y2 * h, x3 * w, y3 * h)

        // Tail (behind the body), pivoting where it meets the body.
        rotate(tailAngle, pivot = p(0.60f, 0.86f)) {
            drawPath(path { m(0.56f, 0.90f); c(0.80f, 0.97f, 1.00f, 0.80f, 0.93f, 0.52f); c(0.89f, 0.38f, 0.77f, 0.42f, 0.79f, 0.57f); c(0.81f, 0.72f, 0.71f, 0.80f, 0.58f, 0.79f); close() }, Brush.linearGradient(listOf(furDark, fur), p(0.6f, 0.9f), p(0.95f, 0.5f)))
            drawPath(path { m(0.93f, 0.52f); c(0.89f, 0.38f, 0.77f, 0.42f, 0.79f, 0.57f); c(0.84f, 0.59f, 0.90f, 0.59f, 0.93f, 0.52f); close() }, cream)
        }
        // Body and chest.
        drawPath(path { m(0.41f, 0.96f); c(0.27f, 0.96f, 0.29f, 0.62f, 0.41f, 0.51f); l(0.59f, 0.51f); c(0.71f, 0.62f, 0.73f, 0.96f, 0.59f, 0.96f); close() }, Brush.verticalGradient(listOf(fur, furDark), 0.5f * h, 0.96f * h))
        drawPath(path { m(0.43f, 0.56f); c(0.45f, 0.70f, 0.47f, 0.84f, 0.50f, 0.90f); c(0.53f, 0.84f, 0.55f, 0.70f, 0.57f, 0.56f); close() }, cream)
        // Paws.
        drawOval(cream, topLeft = p(0.39f, 0.92f), size = Size(0.09f * w, 0.05f * h))
        drawOval(cream, topLeft = p(0.52f, 0.92f), size = Size(0.09f * w, 0.05f * h))
        // Head with ears.
        drawPath(path { m(0.35f, 0.31f); l(0.32f, 0.10f); l(0.45f, 0.23f); l(0.55f, 0.23f); l(0.68f, 0.10f); l(0.65f, 0.31f); c(0.67f, 0.41f, 0.59f, 0.50f, 0.50f, 0.55f); c(0.41f, 0.50f, 0.33f, 0.41f, 0.35f, 0.31f); close() }, fur)
        drawPath(path { m(0.35f, 0.15f); l(0.43f, 0.24f); l(0.37f, 0.27f); close() }, ink.copy(alpha = 0.85f))
        drawPath(path { m(0.65f, 0.15f); l(0.57f, 0.24f); l(0.63f, 0.27f); close() }, ink.copy(alpha = 0.85f))
        // Cheeks and muzzle.
        drawPath(path { m(0.37f, 0.37f); c(0.42f, 0.42f, 0.46f, 0.47f, 0.50f, 0.55f); c(0.54f, 0.47f, 0.58f, 0.42f, 0.63f, 0.37f); c(0.57f, 0.43f, 0.43f, 0.43f, 0.37f, 0.37f); close() }, cream)
        drawCircle(ink, radius = 0.016f * w, center = p(0.50f, 0.53f))
        // Eyes with a glint.
        for (x in listOf(0.43f, 0.57f)) {
            drawOval(ink, topLeft = p(x - 0.018f, 0.325f), size = Size(0.036f * w, 0.042f * h))
            drawCircle(gold, radius = 0.006f * w, center = p(x + 0.006f, 0.335f))
        }
    }
}

