package com.winter.wplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winter.wplayer.MusicListViewModel
import com.winter.wplayer.R
import com.winter.wplayer.room.Song
import com.winter.wplayer.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    musicListViewModel: MusicListViewModel,
    onBack: () -> Unit
) {
    val playlists by musicListViewModel.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.playlistId == playlistId }

    val playlistSongsFlow = remember(playlistId) {
        musicListViewModel.getPlaylistSongs(playlistId)
    }
    val songs by playlistSongsFlow.collectAsStateWithLifecycle(initialValue = emptyList<Song>())
    val allSongs by musicListViewModel.songLists.collectAsStateWithLifecycle()

    // Songs of the library that are not in this playlist yet.
    val availableSongs = remember(allSongs, songs) {
        val inPlaylist = songs.map { it.songId }.toSet()
        allSongs.filter { it.songId !in inPlaylist }
    }

    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: stringResource(R.string.playlists)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddSheet = true },
                        enabled = availableSongs.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add_songs)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (songs.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.empty_playlist_songs),
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = Dimens.screenHPadding,
                    end = Dimens.screenHPadding,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.listGap)
            ) {
                items(songs, key = { it.songId }) { song ->
                    SongRow(
                        song = song,
                        onRemove = {
                            musicListViewModel.removeFromPlaylist(playlistId, song.songId)
                        }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.add_songs_to, playlist?.name ?: ""),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Dimens.screenHPadding)
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxHeight(0.6f)) {
                    if (availableSongs.isEmpty()) {
                        EmptyState(text = stringResource(R.string.all_songs_added))
                    } else {
                        SongItems(
                            songs = availableSongs,
                            onSongClick = { song ->
                                musicListViewModel.addSongToPlaylist(playlistId, song)
                            }
                        )
                    }
                }
            }
        }
    }
}
