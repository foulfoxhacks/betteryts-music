package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.foulfoxhacks.betteryts.music.core.Item
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

private val FILTERS = listOf("all" to "All", "songs" to "Songs", "playlists" to "Playlists", "artists" to "Artists")

@Composable
fun SearchScreen(vm: AppViewModel, pad: PaddingValues) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val done = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    LaunchedEffect(Unit) { if (vm.submittedQuery.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Songs, artists, playlists", color = Brand.Muted) },
            leadingIcon = { Icon(AppIcons.Search, null, tint = Brand.Muted) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChange(""); focus.requestFocus() }) { Icon(AppIcons.Close, "Clear", tint = Brand.Muted) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitSearch(); done() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand.Gold,
                unfocusedBorderColor = Brand.LineGold,
                cursorColor = Brand.Gold,
                focusedTextColor = Brand.Paper,
                unfocusedTextColor = Brand.Paper,
                focusedContainerColor = Brand.Surface,
                unfocusedContainerColor = Brand.Surface,
            ),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FILTERS) { (key, label) -> Chip(label, vm.filter == key) { vm.chooseFilter(key) } }
        }

        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            val showSuggestions = vm.suggestions.isNotEmpty() && vm.query.trim() != vm.submittedQuery
            when {
                showSuggestions -> LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                    items(vm.suggestions) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.submitSearch(s); done() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Search, null, tint = Brand.Muted)
                            Spacer(Modifier.width(16.dp))
                            Text(s, color = Brand.Paper)
                        }
                    }
                }

                else -> when (val r = vm.results) {
                    null -> EmptyNote("Search without the ads", "Find songs, artists and playlists. Results come from YouTube through BetterYTS, with ads and filler stripped out.")
                    Load.Loading -> Loading()
                    is Load.Failed -> ErrorBox(r.message) { vm.submitSearch(vm.submittedQuery) }
                    is Load.Ready -> Results(vm, r, pad)
                }
            }
        }
    }
}

@Composable
private fun Results(vm: AppViewModel, r: Load.Ready<List<Item>>, pad: PaddingValues) {
    if (r.value.isEmpty()) {
        EmptyNote("No results", "Try different words, or another filter.")
        return
    }
    val tracks = r.value.filterIsInstance<Item.TrackItem>().map { it.track }
    LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
        itemsIndexed(r.value) { _, item ->
            when (item) {
                is Item.TrackItem -> TrackRow(vm, item.track, onClick = { vm.playTracks(tracks, tracks.indexOf(item.track)) })
                is Item.CollectionItem -> CollectionRow(item.collection) { vm.playCollection(item.collection) }
                is Item.ArtistItem -> ArtistRow(item.artist) { vm.openArtist(item.artist.id, item.artist.name) }
            }
        }
        item {
            // Reaching the end of the list loads the next page.
            LaunchedEffect(r.value.size) { vm.loadMoreResults() }
            if (r.loadingMore) Loading()
        }
    }
}
