package com.bingbaihanji.flowplayer.feature.history

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bingbaihanji.flowplayer.core.data.db.PlaybackHistoryEntity
import com.bingbaihanji.flowplayer.core.data.repository.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HistoryRepository.from(application)

    val history: StateFlow<List<PlaybackHistoryEntity>> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { repository.clear() }
                .onFailure { Log.w(TAG, "清空播放记录失败", it) }
        }
    }

    private companion object {
        const val TAG = "HistoryViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
