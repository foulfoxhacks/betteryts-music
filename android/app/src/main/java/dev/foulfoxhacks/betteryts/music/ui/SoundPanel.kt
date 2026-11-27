package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle

/**
 * Player › Sound: precise volume, captions, output device, and for songs stored on the phone the
 * equalizer, compressor and skip-silence (YouTube's player runs in its own web frame, so audio
 * effects can't reach it).
 */
@Composable
fun SoundPanel(vm: AppViewModel, player: PlayerController, onDismiss: () -> Unit) {
    val track = vm.nowPlaying
    val local = track?.isLocal == true
    val sliderColors = SliderDefaults.colors(thumbColor = Brand.Gold, activeTrackColor = Brand.Gold, inactiveTrackColor = Brand.Line)
    // Recompose the equalizer sliders after a preset is picked.
    var eqVersion by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text("Sound", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Volume", Modifier.weight(1f), color = Brand.Paper, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(vm.volumePercent)} %", color = Brand.Gold, fontWeight = FontWeight.Bold)
                    }
                    Slider(vm.volumeSlider, { vm.setVolume(it) }, colors = sliderColors)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(-0.05f to "−5", -0.01f to "−1", 0.01f to "+1", 0.05f to "+5").forEach { (d, label) ->
                            Chip(label, false) { vm.setVolume(vm.volumeSlider + d) }
                        }
                    }
                    PickRow("Exponential volume (finer control at low volume)", vm.exponentialVolume) { vm.updateExponentialVolume(it) }
                } }
                if (!local && track != null) {
                    item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Captions", color = Brand.Paper, fontWeight = FontWeight.Bold)
                        val tracks = player.captionTracks
                        if (tracks.isEmpty()) {
                            BrandButton("Find captions", { player.listCaptions() })
                            Text("Lists the subtitle tracks this video has (many songs have none).", color = Brand.Muted, fontSize = 12.sp)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item { Chip("Off", player.captionLanguage == null) { player.chooseCaptions(null) } }
                                items(tracks) { (code, name) -> Chip(name, player.captionLanguage == code) { player.chooseCaptions(code) } }
                            }
                        }
                    } }
                }
                item {
                    BrandButton("Output device…", { vm.openOutputSwitcher() }, icon = AppIcons.Headset)
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Songs on this phone", color = Brand.Paper, fontWeight = FontWeight.Bold)
                    Text("Equalizer, compressor and skip silence work on songs stored on the phone; YouTube's player can't be changed by apps.", color = Brand.Muted, fontSize = 12.sp)
                    PickRow("Skip silences", vm.skipSilence) { vm.updateSkipSilence(it) }
                    val fx = player.fx
                    if (fx == null) {
                        Text("Play a song from Library › On this phone to set up the equalizer.", color = Brand.Muted, fontSize = 12.sp)
                    } else {
                        if (fx.compressorAvailable) PickRow("Volume leveller (compressor)", vm.compressor) { vm.updateCompressor(it) }
                        if (fx.available) {
                            PickRow("Equalizer", vm.eqOn) { vm.setEq(it) }
                            if (vm.eqOn) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    itemsIndexed(fx.presets) { i, name -> Chip(name, vm.eqPreset == i) { vm.useEqPreset(i); eqVersion++ } }
                                }
                                val (lo, hi) = fx.range
                                androidx.compose.runtime.key(eqVersion) {
                                    (0 until fx.bands).forEach { b ->
                                        var level by remember(b, eqVersion) { mutableIntStateOf(fx.level(b)) }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val hz = fx.centerHz(b)
                                            Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(44.dp), color = Brand.Muted, fontSize = 12.sp)
                                            Slider(
                                                level.toFloat(),
                                                { level = it.toInt() },
                                                Modifier.weight(1f),
                                                valueRange = lo.toFloat()..hi.toFloat(),
                                                onValueChangeFinished = { vm.setEqBand(b, level) },
                                                colors = sliderColors,
                                            )
                                            Text("${"%+.0f".format(level / 100f)} dB", Modifier.width(52.dp), color = Brand.Paper, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", style = ButtonTextStyle, color = Brand.Gold) } },
    )
}
