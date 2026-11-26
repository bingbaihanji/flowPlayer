package com.bingbaihanji.flowplayer.feature.playlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bingbaihanji.flowplayer.core.data.db.PlaylistEntity
import com.bingbaihanji.flowplayer.core.data.db.PlaylistItemEntity
import com.bingbaihanji.flowplayer.core.data.db.PlaylistSummary
import com.bingbaihanji.flowplayer.core.data.repository.PlaylistRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlaylistRepository.from(application)

    val playlists: StateFlow<List<PlaylistSummary>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId.asStateFlow()

    val selectedPlaylist: StateFlow<PlaylistEntity?> = _selectedPlaylistId
        .flatMapLatest { playlistId ->
            if (playlistId == null) {
                flowOf<PlaylistEntity?>(null)
            } else {
                repository.observePlaylist(playlistId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val selectedItems: StateFlow<List<PlaylistItemEntity>> = _selectedPlaylistId
        .flatMapLatest { playlistId ->
            if (playlistId == null) {
                flowOf<List<PlaylistItemEntity>>(emptyList())
            } else {
                repository.observeItems(playlistId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun openPlaylist(playlistId: Long) {
        _selectedPlaylistId.value = playlistId
    }

    fun closePlaylist() {
        _selectedPlaylistId.value = null
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            runCatching { repository.createPlaylist(name) }
                .onFailure { Log.w(TAG, "创建播放列表失败", it) }
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            runCatching { repository.renamePlaylist(playlistId, name) }
                .onFailure { Log.w(TAG, "重命名播放列表失败", it) }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            runCatching { repository.deletePlaylist(playlistId) }
                .onSuccess {
                    if (_selectedPlaylistId.value == playlistId) {
                        _selectedPlaylistId.value = null
                    }
                }
                .onFailure { Log.w(TAG, "删除播放列表失败", it) }
        }
    }

    fun removeItem(itemId: Long) {
        viewModelScope.launch {
            runCatching { repository.removeItem(itemId) }
                .onFailure { Log.w(TAG, "移除播放列表条目失败", it) }
        }
    }

    private companion object {
        const val TAG = "PlaylistViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
