package com.bingbaihanji.flowplayer.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import com.bingbaihanji.flowplayer.R

enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Library("library", R.string.tab_library, Icons.Filled.Home),
    Playlist("playlist", R.string.tab_playlist, Icons.Filled.PlayArrow),
    History("history", R.string.tab_history, Icons.Filled.List),
    More("more", R.string.tab_more, Icons.Filled.MoreVert),
}
