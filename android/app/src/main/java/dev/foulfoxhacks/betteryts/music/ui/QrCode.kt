package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.foulfoxhacks.betteryts.music.core.Core

/** Holds whether the room card's QR is open, so it survives the card's recompositions. */
object RoomQrHolder {
    var show by mutableStateOf(false)
}

/** A QR code for [text], black on white with a quiet zone (encoded by the core). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    var rows by remember(text) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(text) { rows = runCatching { Core.qr(text) }.getOrDefault(emptyList()) }
    Canvas(modifier.background(Color.White)) {
        if (rows.isEmpty()) return@Canvas
        val n = rows.size + 8 // 4-module quiet zone each side
        val cell = minOf(size.width, size.height) / n
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
