package com.bingbaihanji.flowplayer.feature.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bingbaihanji.flowplayer.core.common.permissions.hasStorageAccess
import com.bingbaihanji.flowplayer.core.data.file.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * 文件库 ViewModel：负责权限状态、位置导航、加载/搜索/排序以及重命名/删除。
 *
 * 不使用 Hilt；通过 [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory]
 * 使用 (Application) 构造函数创建。
 */
class LibraryViewModel(
    application: Application,
    private val repository: FileRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        LibraryUiState(
            permissionGranted = hasStorageAccess(application),
            storageRootPath = repository.storageRootPath,
        )
    )
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val backStack = ArrayDeque<LibraryLocation>()
    private var loadJob: Job? = null

    init {
        if (_state.value.permissionGranted) {
            reload()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 过滤 / 排序 / 视图
    // ---------------------------------------------------------------------------------------------

    fun setKind(kind: MediaKind) {
        if (kind == _state.value.kind && _state.value.location == LibraryLocation.Root) return
        backStack.clear()
        _state.update {
            it.copy(
                kind = kind,
                location = LibraryLocation.Root,
                crumbs = emptyList(),
                buckets = emptyList(),
                folders = emptyList(),
                files = emptyList(),
                summary = LibrarySummary(),
                searchQuery = "",
                error = null,
                loading = false,
            )
        }
        reload()
    }

    fun toggleViewMode() {
        _state.update {
            it.copy(
                viewMode = if (it.viewMode == LibraryViewMode.LIST) {
                    LibraryViewMode.GRID
                } else {
                    LibraryViewMode.LIST
                },
            )
        }
    }

    fun setSortBy(by: FileSortBy) {
        if (by == _state.value.sort.by) return
        _state.update { it.copy(sort = it.sort.copy(by = by)) }
        reload()
    }

    fun setSortAscending(ascending: Boolean) {
        if (ascending == _state.value.sort.ascending) return
        _state.update { it.copy(sort = it.sort.copy(ascending = ascending)) }
        reload()
    }

    fun toggleShowHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        reload()
    }

    // ---------------------------------------------------------------------------------------------
    // 搜索
    // ---------------------------------------------------------------------------------------------

    fun updateSearchQuery(query: String) {
        if (query == _state.value.searchQuery) return
        _state.update { it.copy(searchQuery = query) }
        reload()
    }

    fun clearSearch() {
        if (_state.value.searchQuery.isEmpty()) return
        _state.update { it.copy(searchQuery = "") }
        reload()
    }

    // ---------------------------------------------------------------------------------------------
    // 导航
    // ---------------------------------------------------------------------------------------------

    fun openBucket(bucket: MediaBucket) {
        backStack.addLast(_state.value.location)
        navigateTo(LibraryLocation.Bucket(bucket.id, bucket.name))
    }

    fun openDirectory(path: String) {
        backStack.addLast(_state.value.location)
        navigateTo(LibraryLocation.Directory(path))
    }

    fun goRoot() {
        backStack.clear()
        navigateTo(LibraryLocation.Root)
    }

    fun navigateUp() {
        if (backStack.isEmpty()) {
            goRoot()
        } else {
            navigateTo(backStack.removeLastOrNull() ?: LibraryLocation.Root)
        }
    }

    fun navigateCrumb(index: Int) {
        val crumbs = _state.value.crumbs
        if (index !in crumbs.indices || index == crumbs.lastIndex) return
        val crumb = crumbs[index]
        if (crumb.isRoot) {
            goRoot()
            return
        }
        val path = crumb.directoryPath ?: return
        jumpToDirectory(path)
    }

    private fun jumpToDirectory(path: String) {
        backStack.clear()
        backStack.addLast(LibraryLocation.Root)
        val rootPath = repository.storageRootPath
        val normalizedRoot = rootPath.trimEnd('/')
        if (path != rootPath && path.startsWith("$normalizedRoot/")) {
            backStack.addLast(LibraryLocation.Directory(rootPath))
            var ancestor = normalizedRoot
            path.removePrefix("$normalizedRoot/")
                .split('/')
                .filter { it.isNotEmpty() }
                .dropLast(1)
                .forEach { segment ->
                    ancestor = "$ancestor/$segment"
                    backStack.addLast(LibraryLocation.Directory(ancestor))
                }
        }
        navigateTo(LibraryLocation.Directory(path))
    }

    private fun navigateTo(location: LibraryLocation) {
        val crumbs = when (location) {
            is LibraryLocation.Root -> emptyList()
            is LibraryLocation.Bucket -> bucketCrumbs(location)
            is LibraryLocation.Directory -> directoryCrumbs(location.path)
        }
        _state.update {
            it.copy(
                location = location,
                crumbs = crumbs,
                buckets = emptyList(),
                folders = emptyList(),
                files = emptyList(),
                summary = LibrarySummary(),
                searchQuery = "",
                error = null,
            )
        }
        reload()
    }

    private fun bucketCrumbs(location: LibraryLocation.Bucket): List<LibraryCrumb> = listOf(
        LibraryCrumb(label = "本地媒体", isRoot = true),
        LibraryCrumb(label = location.bucketName),
    )

    private fun directoryCrumbs(path: String): List<LibraryCrumb> {
        val rootPath = repository.storageRootPath
        val normalizedRoot = rootPath.trimEnd('/')
        val crumbs = mutableListOf(LibraryCrumb(label = "本地媒体", isRoot = true))
        if (path != rootPath && !path.startsWith("$normalizedRoot/")) {
            crumbs += LibraryCrumb(label = path)
            return crumbs
        }
        crumbs += LibraryCrumb(label = "内部存储", directoryPath = rootPath)
        val relative = path.removePrefix(normalizedRoot).trimStart('/')
        if (relative.isNotEmpty()) {
            var accumulated = normalizedRoot
            relative.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                accumulated = "$accumulated/$segment"
                crumbs += LibraryCrumb(label = segment, directoryPath = accumulated)
            }
        }
        return crumbs
    }

    // ---------------------------------------------------------------------------------------------
    // 权限 / 刷新
    // ---------------------------------------------------------------------------------------------

    /**
     * 在页面 resume 时调用：权限从无到有时自动加载；权限被回收时清空数据并停止加载。
     */
    fun refreshPermission() {
        val granted = hasStorageAccess(getApplication<Application>())
        val previous = _state.value.permissionGranted
        if (granted == previous) return
        if (!granted) {
            loadJob?.cancel()
            _state.update {
                it.copy(
                    permissionGranted = false,
                    loading = false,
                    error = null,
                    buckets = emptyList(),
                    folders = emptyList(),
                    files = emptyList(),
                    crumbs = emptyList(),
                    summary = LibrarySummary(),
                )
            }
            return
        }
        _state.update { it.copy(permissionGranted = true) }
        reload()
    }

    fun refresh() {
        if (!_state.value.permissionGranted) {
            refreshPermission()
            if (!_state.value.permissionGranted) return
        }
        reload()
    }

    // ---------------------------------------------------------------------------------------------
    // 文件操作
    // ---------------------------------------------------------------------------------------------

    fun rename(file: MediaFile, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _messages.trySend("文件名不能为空")
            return
        }
        viewModelScope.launch {
            val result = repository.rename(file, trimmed)
            if (result.isSuccess) {
                _messages.trySend("已重命名")
                reload()
            } else {
                _messages.trySend(result.exceptionOrNull()?.message ?: "重命名失败")
            }
        }
    }

    fun delete(file: MediaFile) {
        viewModelScope.launch {
            val result = repository.delete(file)
            if (result.isSuccess) {
                _messages.trySend("已删除")
                reload()
            } else {
                _messages.trySend(result.exceptionOrNull()?.message ?: "删除失败")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 加载
    // ---------------------------------------------------------------------------------------------

    private fun reload() {
        loadJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.permissionGranted) {
            _state.update { it.copy(loading = false) }
            return
        }
        val input = LoadInput(
            kind = snapshot.kind,
            location = snapshot.location,
            sort = snapshot.sort,
            showHidden = snapshot.showHidden,
            query = snapshot.searchQuery.trim(),
        )
        loadJob = viewModelScope.launch {
            if (input.query.isNotEmpty()) {
                // 输入搜索词时做轻微防抖；按键更新会取消上一个任务。
                delay(200L)
            }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val result = load(input)
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        buckets = result.buckets,
                        folders = result.folders,
                        files = result.files,
                        crumbs = result.crumbs,
                        summary = result.summary,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "加载失败，请重试",
                    )
                }
            }
        }
    }

    private suspend fun load(input: LoadInput): LoadResult {
        if (input.query.isNotEmpty()) {
            val files = repository.searchMedia(input.kind, input.query, input.sort)
            return LoadResult(
                files = files,
                crumbs = listOf(LibraryCrumb(label = "搜索结果")),
                summary = summarizeFiles(files),
            )
        }
        return when (val location = input.location) {
            is LibraryLocation.Root -> {
                val buckets = repository.loadBuckets(input.kind, input.sort)
                val folders = repository.loadRootFolders(input.kind, input.sort, input.showHidden)
                LoadResult(
                    buckets = buckets,
                    folders = folders,
                    summary = summarizeRoot(buckets, folders),
                )
            }

            is LibraryLocation.Bucket -> {
                val files = repository.loadBucketFiles(
                    kind = input.kind,
                    bucketId = location.bucketId,
                    sort = input.sort,
                    includeHidden = input.showHidden,
                )
                LoadResult(
                    files = files,
                    crumbs = bucketCrumbs(location),
                    summary = summarizeFiles(files),
                )
            }

            is LibraryLocation.Directory -> {
                val files = repository.listDirectory(
                    path = location.path,
                    kind = input.kind,
                    sort = input.sort,
                    includeHidden = input.showHidden,
                )
                LoadResult(
                    files = files,
                    crumbs = directoryCrumbs(location.path),
                    summary = summarizeFiles(files),
                )
            }
        }
    }

    private fun summarizeFiles(files: List<MediaFile>): LibrarySummary {
        val directoryCount = files.count { it.isDirectory }
        val mediaFiles = files.filterNot { it.isDirectory }
        return LibrarySummary(
            directoryCount = directoryCount,
            fileCount = mediaFiles.size,
            totalSize = mediaFiles.sumOf { it.size },
            totalDurationMs = mediaFiles.sumOf { it.durationMs ?: 0L },
        )
    }

    private fun summarizeRoot(
        buckets: List<MediaBucket>,
        folders: List<MediaFile>,
    ): LibrarySummary = LibrarySummary(
        albumCount = buckets.size,
        folderCount = folders.size,
        fileCount = buckets.sumOf { it.count },
        totalSize = buckets.sumOf { it.totalSize },
        totalDurationMs = buckets.sumOf { it.totalDurationMs },
    )

    private data class LoadInput(
        val kind: MediaKind,
        val location: LibraryLocation,
        val sort: FileSortSpec,
        val showHidden: Boolean,
        val query: String,
    )

    private data class LoadResult(
        val buckets: List<MediaBucket> = emptyList(),
        val folders: List<MediaFile> = emptyList(),
        val files: List<MediaFile> = emptyList(),
        val crumbs: List<LibraryCrumb> = emptyList(),
        val summary: LibrarySummary = LibrarySummary(),
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return LibraryViewModel(application, FileRepositoryImpl(application)) as T
                }
            }
    }
}

/** 小工具：避免 UI 层直接依赖 java.io.File。 */
internal fun directoryDisplayName(path: String): String {
    val name = File(path).name
    return name.ifBlank { "存储目录" }
}
