package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_LIST
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.*
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.viewmodels.LocalFilter
import com.metrolist.music.viewmodels.LocalSearchViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

// Ho spostato la sealed class e le sue implementazioni qui, fuori dalla funzione Composable
sealed class LazySearchItem {
    data class Header(val filter: LocalFilter) : LazySearchItem()
    data class Content(val item: Any) : LazySearchItem() // Item può essere Song, Album, ecc.
    object NoResultPlaceholder : LazySearchItem()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalSearchScreen(
    query: String,
    navController: NavController,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
    pureBlack: Boolean,
    viewModel: LocalSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val searchFilter by viewModel.filter.collectAsState()
    val result by viewModel.result.collectAsState()

    val lazyListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        viewModel.query.value = query
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // Prepara la lista "appiattita" di elementi da visualizzare nella LazyColumn
    val lazyListContent: List<LazySearchItem> by remember(result.map, searchFilter, query) {
        derivedStateOf {
            val content = mutableListOf<LazySearchItem>()

            if (searchFilter == LocalFilter.ALL) {
                LocalFilter.values().filter { it != LocalFilter.ALL }.forEach { filter ->
                    val itemsForFilter = result.map.getOrDefault(filter, emptyList())
                    if (itemsForFilter.isNotEmpty()) {
                        content.add(LazySearchItem.Header(filter))
                        itemsForFilter.distinctBy { it.id }.forEach { item ->
                            content.add(LazySearchItem.Content(item))
                        }
                    }
                }
            } else {
                val itemsForSelectedFilter = result.map.getOrDefault(searchFilter, emptyList())
                itemsForSelectedFilter.distinctBy { it.id }.forEach { item ->
                    content.add(LazySearchItem.Content(item))
                }
            }

            // Aggiungi il segnaposto "nessun risultato" solo se la query non è vuota e non ci sono risultati di contenuto
            if (query.isNotEmpty() && content.filterIsInstance<LazySearchItem.Content>().isEmpty()) {
                content.add(LazySearchItem.NoResultPlaceholder)
            }
            content
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background)
            .let { base ->
                if (isLandscape) {
                    base.windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                    )
                } else base
            }
    ) {
        ChipsRow(
            chips = listOf(
                LocalFilter.ALL to stringResource(R.string.filter_all),
                LocalFilter.SONG to stringResource(R.string.filter_songs),
                LocalFilter.ALBUM to stringResource(R.string.filter_albums),
                LocalFilter.ARTIST to stringResource(R.string.filter_artists),
                LocalFilter.PLAYLIST to stringResource(R.string.filter_playlists),
            ),
            currentValue = searchFilter,
            onValueUpdate = { viewModel.filter.value = it },
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f),
            contentPadding = WindowInsets.systemBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues(),
        ) {
            items(
                items = lazyListContent,
                key = { lazyItem ->
                    when (lazyItem) {
                        is LazySearchItem.Header -> "header_${lazyItem.filter.name}"
                        is LazySearchItem.Content -> {
                            when (val item = lazyItem.item) {
                                is Song -> "song_${item.id}"
                                is Album -> "album_${item.id}"
                                is Artist -> "artist_${item.id}"
                                is Playlist -> "playlist_${item.id}"
                                else -> "unknown_${item.hashCode()}" // Fallback, should not happen
                            }
                        }
                        LazySearchItem.NoResultPlaceholder -> "no_result_placeholder"
                    }
                },
                // contentType può essere utile se hai bisogno di differenziare il riutilizzo degli elementi
                // in base al tipo, ma per ora il default va bene.
                // contentType = { lazyItem ->
                //     when (lazyItem) {
                //         is LazySearchItem.Header -> "header"
                //         is LazySearchItem.Content -> {
                //             when (lazyItem.item) {
                //                 is Song -> "song"
                //                 is Album -> "album"
                //                 is Artist -> "artist"
                //                 is Playlist -> "playlist"
                //                 else -> "content_item"
                //             }
                //         }
                //         LazySearchItem.NoResultPlaceholder -> "no_result"
                //     }
                // }
            ) { lazyItem ->
                when (lazyItem) {
                    is LazySearchItem.Header -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight)
                                .clickable { viewModel.filter.value = lazyItem.filter }
                                .padding(start = 12.dp, end = 18.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    when (lazyItem.filter) {
                                        LocalFilter.SONG -> R.string.filter_songs
                                        LocalFilter.ALBUM -> R.string.filter_albums
                                        LocalFilter.ARTIST -> R.string.filter_artists
                                        LocalFilter.PLAYLIST -> R.string.filter_playlists
                                        LocalFilter.ALL -> error("Should not happen for ALL filter here")
                                    }
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painter = painterResource(R.drawable.navigate_next),
                                contentDescription = null,
                            )
                        }
                    }
                    is LazySearchItem.Content -> {
                        when (val item = lazyItem.item) {
                            is Song -> SongListItem(
                                song = item,
                                showInLibraryIcon = true,
                                isActive = item.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        onDismiss()
                                                        menuState.dismiss()
                                                    },
                                                    isFromCache = isFromCache
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = {
                                            if (item.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                val songs = result.map
                                                    .getOrDefault(LocalFilter.SONG, emptyList())
                                                    .filterIsInstance<Song>()
                                                    .map { it.toMediaItem() }
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = context.getString(R.string.queue_searched_songs),
                                                        items = songs,
                                                        startIndex = songs.indexOfFirst { it.mediaId == item.id },
                                                    )
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        onDismiss()
                                                        menuState.dismiss()
                                                    },
                                                    isFromCache = isFromCache
                                                )
                                            }
                                        }
                                    )
                                // Rimosso .animateItem() per ottimizzazione performance scrolling
                            )

                            is Album -> AlbumListItem(
                                album = item,
                                isActive = item.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                modifier = Modifier
                                    .clickable {
                                        onDismiss()
                                        navController.navigate("album/${item.id}")
                                    }
                                // Rimosso .animateItem() per ottimizzazione performance scrolling
                            )

                            is Artist -> ArtistListItem(
                                artist = item,
                                modifier = Modifier
                                    .clickable {
                                        onDismiss()
                                        navController.navigate("artist/${item.id}")
                                    }
                                // Rimosso .animateItem() per ottimizzazione performance scrolling
                            )

                            is Playlist -> PlaylistListItem(
                                playlist = item,
                                modifier = Modifier
                                    .clickable {
                                        onDismiss()
                                        navController.navigate("local_playlist/${item.id}")
                                    }
                                // Rimosso .animateItem() per ottimizzazione performance scrolling
                            )
                            else -> { /* Gestisci altri tipi se necessario */ }
                        }
                    }
                    LazySearchItem.NoResultPlaceholder -> {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                        )
                    }
                }
            }
        }
    }
}
