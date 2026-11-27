package dev.foulfoxhacks.betteryts.music.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Touch layer over the big player's video. Tap: play/pause. Double-tap left/right third: −10/+10 s.
 * Vertical swipe on the right half: volume; on the left half: screen brightness (this window only).
 * The video itself stays fully visible; only a short hint is drawn over it.
 */
@Composable
fun PlayerGestures(vm: AppViewModel, modifier: Modifier) {
    val activity = LocalContext.current as? Activity
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { vm.togglePlay() },
                    onDoubleTap = { at ->
                        when {
                            at.x < size.width / 3f -> vm.seekBy(-10f)
                            at.x > size.width * 2f / 3f -> vm.seekBy(10f)
                            else -> vm.togglePlay()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var rightSide = true
                detectVerticalDragGestures(
                    onDragStart = { at -> rightSide = at.x > size.width / 2f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val delta = -dy / size.height.toFloat()
                        if (rightSide) {
                            vm.setVolume(vm.volumeSlider + delta)
                            vm.showHint("Volume ${vm.volumePercent.toInt()}%")
                        } else if (activity != null) {
                            val lp = activity.window.attributes
                            val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                            lp.screenBrightness = (cur + delta).coerceIn(0.02f, 1f)
                            activity.window.attributes = lp
                            vm.showHint("Brightness ${(lp.screenBrightness * 100).toInt()}%")
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        vm.gestureHint?.let { hint ->
            Text(
                hint,
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}
