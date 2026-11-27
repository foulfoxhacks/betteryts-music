package dev.foulfoxhacks.betteryts.music.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The logo's own background, so the splash and the artwork meet without a seam. */
private val LOGO_INK = Color(0xFF0B1020)

/**
 * Cold-start splash: the BetterYTS logo scales and fades in, holds, then fades into the app, with
 * the developer credit underneath. About 1.6 seconds; tap to skip. Skipped when the system's
 * animations are off.
 */
@Composable
fun LogoSplash(onDone: () -> Unit) {
    val context = LocalContext.current
    val animationsOff = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val appear = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (animationsOff) return@LaunchedEffect onDone()
        launch { appear.animateTo(1f, tween(650, easing = LinearOutSlowInEasing)) }
        delay(1150)
        exit.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(exit.value)
            .background(LOGO_INK)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.brand_logo_dark),
            contentDescription = "BetterYTS: Open, Watch, Build",
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .graphicsLayer {
                    val s = 0.86f + 0.14f * appear.value
                    scaleX = s
                    scaleY = s
                    alpha = appear.value
                },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).alpha(appear.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("BetterYTS Music", color = Color(0xFFF2F5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("by FoulFoxHacks", color = Color(0xFF8A93B5), fontSize = 12.sp)
        }
    }
}
