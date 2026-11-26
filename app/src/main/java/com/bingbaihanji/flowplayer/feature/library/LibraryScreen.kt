package com.bingbaihanji.flowplayer.feature.library

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import com.bingbaihanji.flowplayer.core.common.permissions.hasStorageAccess
import com.bingbaihanji.flowplayer.core.common.permissions.openAllFilesAccessSettings
import com.bingbaihanji.flowplayer.core.data.file.*
import com.bingbaihanji.flowplayer.feature.viewer.image.ImageViewerActivity
import java.io.File
import androidx.compose.foundation.lazy.grid.items as gridItems


@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(application))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val imageLoader = remember(context) { LibraryImageLoader.get(context) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameTarget by remember { mutableStateOf<MediaFile?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaFile?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermission()
    }

    // 从系统设置返回后重新检测“所有文件访问权限”。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermission()
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val showSearchField = searchOpen || state.isSearchActive
    BackHandler(enabled = showSearchField || state.location != LibraryLocation.Root) {
        if (showSearchField) {
            searchOpen = false
            viewModel.clearSearch()
        } else {
            viewModel.navigateUp()
        }
    }

    val requestPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesAccessSettings(context)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    val viewerTitle = viewerTitle(state)
    val onFileClick: (MediaFile) -> Unit = { file ->
        when {
            file.isDirectory -> {
                searchOpen = false
                viewModel.openDirectory(file.path)
            }

            file.kind == MediaKind.IMAGE -> {
                val siblings = state.files.filter { it.kind == MediaKind.IMAGE && !it.isDirectory }
                launchImageViewer(context, file, siblings, viewerTitle)
            }

            else -> Toast.makeText(context, "播放器将在 M3 实现", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LibraryTopBar(
            state = state,
            searchActive = showSearchField,
            onBack = viewModel::navigateUp,
            onSearchOpen = { searchOpen = true },
            onSearchClose = {
                searchOpen = false
                viewModel.clearSearch()
            },
            onSearchQueryChange = viewModel::updateSearchQuery,
            onClearQuery = { viewModel.updateSearchQuery("") },
            onKindSelected = { kind ->
                searchOpen = false
                viewModel.setKind(kind)
            },
            onToggleViewMode = viewModel::toggleViewMode,
            onSortBy = viewModel::setSortBy,
            onSortAscending = viewModel::setSortAscending,
            onToggleHidden = viewModel::toggleShowHidden,
            onRefresh = viewModel::refresh,
            onGoRoot = {
                searchOpen = false
                viewModel.goRoot()
            },
        )

        if (!state.permissionGranted) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                PermissionGate(
                    onGrant = requestPermission,
                    onRecheck = {
                        viewModel.refreshPermission()
                        if (!hasStorageAccess(context)) {
                            Toast.makeText(context, "尚未获得存储权限", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        } else {
            if (!state.isSearchActive && state.crumbs.isNotEmpty()) {
                BreadcrumbRow(crumbs = state.crumbs, onCrumbClick = viewModel::navigateCrumb)
            }
            val summary = summaryText(state)
            if (!state.loading && state.error == null && summary.isNotEmpty()) {
                SummaryRow(summary)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LibraryContent(
                    state = state,
                    imageLoader = imageLoader,
                    onRetry = viewModel::refresh,
                    onExitSearch = {
                        searchOpen = false
                        viewModel.clearSearch()
                    },
                    onFileClick = onFileClick,
                    onFileActions = { file -> actionTarget = file },
                    onRenameFile = { file -> renameTarget = file },
                    onDeleteFile = { file -> deleteTarget = file },
                    onBucketClick = { bucket ->
                        searchOpen = false
                        viewModel.openBucket(bucket)
                    },
                )
            }
            if (state.location != LibraryLocation.Root && !state.isSearchActive) {
                BottomPathBar(location = state.location)
            }
        }
    }

    val actionFile = actionTarget
    if (actionFile != null) {
        FileActionDialog(
            file = actionFile,
            onDismiss = { actionTarget = null },
            onRename = {
                actionTarget = null
                renameTarget = actionFile
            },
            onDelete = {
                actionTarget = null
                deleteTarget = actionFile
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            file = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                viewModel.rename(target, newName)
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteDialog(
            file = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                viewModel.delete(target)
            },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 顶部栏
// -------------------------------------------------------------------------------------------------

@Composable
private fun LibraryTopBar(
    state: LibraryUiState,
    searchActive: Boolean,
    onBack: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onKindSelected: (MediaKind) -> Unit,
    onToggleViewMode: () -> Unit,
    onSortBy: (FileSortBy) -> Unit,
    onSortAscending: (Boolean) -> Unit,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit,
    onGoRoot: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchActive) {
                IconButton(
                    onClick = onSearchClose,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出搜索",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                SearchField(
                    query = state.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = onClearQuery,
                    placeholder = "搜索${kindLabel(state.kind)}名称",
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
            } else {
                if (state.location != LibraryLocation.Root) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回上一级",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                var kindMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (state.location == LibraryLocation.Root) {
                                    Modifier.clickable { kindMenuExpanded = true }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = topBarTitle(state),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.location == LibraryLocation.Root) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "选择媒体类型",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = kindMenuExpanded,
                        onDismissRequest = { kindMenuExpanded = false },
                    ) {
                        listOf(MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.AUDIO).forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kindLabel(kind)) },
                                onClick = {
                                    kindMenuExpanded = false
                                    onKindSelected(kind)
                                },
                                trailingIcon = {
                                    if (state.kind == kind) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }

                var sortMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { sortMenuExpanded = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "排序",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        FileSortBy.entries.forEach { sortBy ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(sortBy)) },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortBy(sortBy)
                                },
                                trailingIcon = {
                                    if (state.sort.by == sortBy) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("升序") },
                            onClick = {
                                sortMenuExpanded = false
                                onSortAscending(true)
                            },
                            trailingIcon = {
                                if (state.sort.ascending) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("降序") },
                            onClick = {
                                sortMenuExpanded = false
                                onSortAscending(false)
                            },
                            trailingIcon = {
                                if (!state.sort.ascending) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                        )
                    }
                }

                IconButton(
                    onClick = onSearchOpen,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (state.viewMode == LibraryViewMode.LIST) {
                            Icons.Filled.GridView
                        } else {
                            Icons.AutoMirrored.Filled.ViewList
                        },
                        contentDescription = if (state.viewMode == LibraryViewMode.LIST) {
                            "切换为网格"
                        } else {
                            "切换为列表"
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                var moreMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("显示隐藏文件") },
                            onClick = {
                                moreMenuExpanded = false
                                onToggleHidden()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (state.showHidden) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (state.showHidden) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            onClick = {
                                moreMenuExpanded = false
                                onRefresh()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                            },
                        )
                        if (state.location != LibraryLocation.Root) {
                            DropdownMenuItem(
                                text = { Text("返回根目录") },
                                onClick = {
                                    moreMenuExpanded = false
                                    onGoRoot()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Home, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 权限引导
// -------------------------------------------------------------------------------------------------

@Composable
private fun PermissionGate(
    onGrant: () -> Unit,
    onRecheck: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "需要存储权限",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "授予访问设备文件的权限后，才能扫描并展示视频、图片和音频。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Android 11 及以上需在系统设置中开启「所有文件访问权限」；旧版本需授予读写存储权限。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onGrant,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 10.dp),
        ) {
            Text("去授权")
        }
        TextButton(onClick = onRecheck) {
            Text("重新检测权限", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 内容区域
// -------------------------------------------------------------------------------------------------

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    imageLoader: ImageLoader,
    onRetry: () -> Unit,
    onExitSearch: () -> Unit,
    onFileClick: (MediaFile) -> Unit,
    onFileActions: (MediaFile) -> Unit,
    onRenameFile: (MediaFile) -> Unit,
    onDeleteFile: (MediaFile) -> Unit,
    onBucketClick: (MediaBucket) -> Unit,
) {
    val error = state.error
    when {
        state.loading -> LoadingState()
        error != null -> ErrorState(message = error, onRetry = onRetry)
        state.isSearchActive || state.location != LibraryLocation.Root -> FilesContent(
            state = state,
            imageLoader = imageLoader,
            onExitSearch = onExitSearch,
            onFileClick = onFileClick,
            onFileActions = onFileActions,
            onRenameFile = onRenameFile,
            onDeleteFile = onDeleteFile,
        )

        else -> RootContent(
            state = state,
            imageLoader = imageLoader,
            onFileClick = onFileClick,
            onBucketClick = onBucketClick,
        )
    }
}

@Composable
private fun RootContent(
    state: LibraryUiState,
    imageLoader: ImageLoader,
    onFileClick: (MediaFile) -> Unit,
    onBucketClick: (MediaBucket) -> Unit,
) {
    if (state.buckets.isEmpty() && state.folders.isEmpty()) {
        EmptyState(
            kind = state.kind,
            title = "暂无${kindLabel(state.kind)}",
            subtitle = "未找到媒体相册或文件夹，可尝试刷新、切换媒体类型或检查存储权限。",
        )
        return
    }

    when (state.viewMode) {
        LibraryViewMode.LIST -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
        ) {
            if (state.buckets.isNotEmpty()) {
                item(key = "bucket-header") {
                    SectionHeader(title = "媒体专辑", count = state.buckets.size)
                }
                items(
                    items = state.buckets,
                    key = { "bucket-${it.id}-${it.kind}" },
                ) { bucket ->
                    BucketRow(
                        bucket = bucket,
                        imageLoader = imageLoader,
                        onClick = { onBucketClick(bucket) },
                    )
                }
            }
            if (state.folders.isNotEmpty()) {
                item(key = "folder-header") {
                    SectionHeader(title = "文件夹", count = state.folders.size)
                }
                items(
                    items = state.folders,
                    key = { "root-folder-${it.path}" },
                ) { folder ->
                    FolderRow(
                        file = folder,
                        imageLoader = imageLoader,
                        onClick = { onFileClick(folder) },
                    )
                }
            }
        }

        LibraryViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.buckets.isNotEmpty()) {
                item(
                    key = "bucket-header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    GridSectionHeader(title = "媒体专辑", count = state.buckets.size)
                }
                gridItems(
                    items = state.buckets,
                    key = { "bucket-${it.id}-${it.kind}" },
                ) { bucket ->
                    BucketGridCell(
                        bucket = bucket,
                        imageLoader = imageLoader,
                        onClick = { onBucketClick(bucket) },
                    )
                }
            }
            if (state.folders.isNotEmpty()) {
                item(
                    key = "folder-header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    GridSectionHeader(title = "文件夹", count = state.folders.size)
                }
                gridItems(
                    items = state.folders,
                    key = { "root-folder-${it.path}" },
                ) { folder ->
                    FolderGridCell(
                        file = folder,
                        imageLoader = imageLoader,
                        onClick = { onFileClick(folder) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesContent(
    state: LibraryUiState,
    imageLoader: ImageLoader,
    onExitSearch: () -> Unit,
    onFileClick: (MediaFile) -> Unit,
    onFileActions: (MediaFile) -> Unit,
    onRenameFile: (MediaFile) -> Unit,
    onDeleteFile: (MediaFile) -> Unit,
) {
    if (state.files.isEmpty()) {
        val title = when {
            state.isSearchActive -> "未找到相关内容"
            state.location is LibraryLocation.Bucket -> "这个相册里还没有${kindLabel(state.kind)}"
            else -> "当前文件夹暂无${kindLabel(state.kind)}文件"
        }
        val subtitle = when {
            state.isSearchActive -> "换个关键词试试，或清空搜索继续浏览。"
            state.location is LibraryLocation.Bucket -> "系统媒体库中该相册暂无${kindLabel(state.kind)}。"
            else -> "当前目录下没有匹配的${kindLabel(state.kind)}文件，可返回上级或切换媒体类型。"
        }
        EmptyState(
            kind = state.kind,
            title = title,
            subtitle = subtitle,
            actionLabel = if (state.isSearchActive) "清空搜索" else null,
            onAction = if (state.isSearchActive) onExitSearch else null,
        )
        return
    }

    when (state.viewMode) {
        LibraryViewMode.LIST -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
        ) {
            items(
                items = state.files,
                key = { "file-${it.contentUri ?: it.path}" },
            ) { file ->
                FileRow(
                    file = file,
                    imageLoader = imageLoader,
                    onClick = { onFileClick(file) },
                    onLongPress = { onFileActions(file) },
                    onRename = { onRenameFile(file) },
                    onDelete = { onDeleteFile(file) },
                )
            }
        }

        LibraryViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(
                items = state.files,
                key = { "file-${it.contentUri ?: it.path}" },
            ) { file ->
                FileGridCell(
                    file = file,
                    imageLoader = imageLoader,
                    onClick = { onFileClick(file) },
                    onLongPress = {
                        if (!file.isDirectory) onFileActions(file)
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 列表项 / 网格项
// -------------------------------------------------------------------------------------------------

@Composable
private fun BucketRow(
    bucket: MediaBucket,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BucketThumbnail(
            bucket = bucket,
            imageLoader = imageLoader,
            modifier = Modifier.size(width = 104.dp, height = 64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bucket.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = bucketMeta(bucket),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FolderRow(
    file: MediaFile,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailFrame(
            modifier = Modifier.size(width = 104.dp, height = 64.dp),
            icon = Icons.Filled.Folder,
            imageLoader = imageLoader,
            model = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = fileMeta(file),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FileRow(
    file: MediaFile,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(file.path) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = {
                        if (!file.isDirectory) onLongPress()
                    },
                )
            }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnail(
            file = file,
            imageLoader = imageLoader,
            modifier = Modifier.size(width = 104.dp, height = 64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = fileMeta(file),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (file.isDirectory) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "文件操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketGridCell(
    bucket: MediaBucket,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        BucketThumbnail(
            bucket = bucket,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = bucket.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${bucket.count} ${kindUnitLabel(bucket.kind)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderGridCell(
    file: MediaFile,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        ThumbnailFrame(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            icon = Icons.Filled.Folder,
            imageLoader = imageLoader,
            model = null,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = file.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = fileMeta(file),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileGridCell(
    file: MediaFile,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(file.path) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(vertical = 2.dp),
    ) {
        FileThumbnail(
            file = file,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = file.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = gridMeta(file),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 缩略图
// -------------------------------------------------------------------------------------------------

@Composable
private fun FileThumbnail(
    file: MediaFile,
    imageLoader: ImageLoader,
    modifier: Modifier,
) {
    ThumbnailFrame(
        modifier = modifier,
        icon = iconForKind(file.kind, file.isDirectory),
        imageLoader = imageLoader,
        model = thumbnailModel(file),
        durationMs = file.durationMs,
    )
}

@Composable
private fun BucketThumbnail(
    bucket: MediaBucket,
    imageLoader: ImageLoader,
    modifier: Modifier,
) {
    ThumbnailFrame(
        modifier = modifier,
        icon = iconForKind(bucket.kind, isDirectory = false),
        imageLoader = imageLoader,
        model = bucket.cover?.let { thumbnailModel(it) },
        durationMs = null,
    )
}

@Composable
private fun ThumbnailFrame(
    modifier: Modifier,
    icon: ImageVector,
    imageLoader: ImageLoader,
    model: Any?,
    durationMs: Long? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (durationMs != null && durationMs > 0L) {
            Text(
                text = FileFormatters.formatDuration(durationMs),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

private fun thumbnailModel(file: MediaFile): Any? {
    if (file.isDirectory) return null
    // 音频/其他文件没有可用的缩略图，直接展示图标，避免无效的图片请求。
    if (file.kind == MediaKind.AUDIO || file.kind == MediaKind.OTHER) return null
    val contentUri = file.contentUri
    if (!contentUri.isNullOrBlank()) return contentUri.toUri()
    if (file.path.startsWith("content://") || file.path.startsWith("file://")) {
        return file.path.toUri()
    }
    return File(file.path)
}

// -------------------------------------------------------------------------------------------------
// 面包屑 / 统计 / 路径栏
// -------------------------------------------------------------------------------------------------

@Composable
private fun BreadcrumbRow(
    crumbs: List<LibraryCrumb>,
    onCrumbClick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            val isCurrent = index == crumbs.lastIndex
            val itemModifier = if (!isCurrent && crumb.hasTarget) {
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onCrumbClick(index) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            } else {
                Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            }
            Text(
                text = crumb.label,
                modifier = itemModifier,
                fontSize = 12.sp,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SummaryRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomPathBar(location: LibraryLocation) {
    val text = when (location) {
        is LibraryLocation.Root -> ""
        is LibraryLocation.Bucket -> "相册：${location.bucketName}"
        is LibraryLocation.Directory -> location.path
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun GridSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 空态 / 加载 / 错误
// -------------------------------------------------------------------------------------------------

@Composable
private fun EmptyState(
    kind: MediaKind,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconForKind(kind, isDirectory = false),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "加载失败",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 文件操作对话框
// -------------------------------------------------------------------------------------------------

@Composable
private fun FileActionDialog(
    file: MediaFile,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件操作") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = file.title,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRename,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重命名")
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun RenameDialog(
    file: MediaFile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(file.path) { mutableStateOf(file.title) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    text = file.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("文件名") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && trimmed != file.title,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    file: MediaFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件") },
        text = {
            Text(
                text = "确定要删除「${file.title}」吗？此操作不可恢复。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

// -------------------------------------------------------------------------------------------------
// 文案 / 数据格式化 / 启动图片查看
// -------------------------------------------------------------------------------------------------

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图片"
    MediaKind.AUDIO -> "音频"
    MediaKind.OTHER -> "文件"
}

private fun kindUnitLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "个视频"
    MediaKind.IMAGE -> "张图片"
    MediaKind.AUDIO -> "首音频"
    MediaKind.OTHER -> "个文件"
}

private fun iconForKind(kind: MediaKind, isDirectory: Boolean): ImageVector = when {
    isDirectory -> Icons.Filled.Folder
    kind == MediaKind.VIDEO -> Icons.Filled.VideoFile
    kind == MediaKind.IMAGE -> Icons.Filled.Image
    kind == MediaKind.AUDIO -> Icons.Filled.MusicNote
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun topBarTitle(state: LibraryUiState): String = when (val location = state.location) {
    is LibraryLocation.Root -> kindLabel(state.kind)
    is LibraryLocation.Bucket -> location.bucketName
    is LibraryLocation.Directory -> {
        if (state.storageRootPath.isNotBlank() && location.path == state.storageRootPath) {
            "内部存储"
        } else {
            directoryDisplayName(location.path)
        }
    }
}

private fun sortLabel(sortBy: FileSortBy): String = when (sortBy) {
    FileSortBy.NAME -> "名称"
    FileSortBy.TIME -> "时间"
    FileSortBy.SIZE -> "大小"
    FileSortBy.TYPE -> "类型"
}

private fun bucketMeta(bucket: MediaBucket): String {
    val parts = mutableListOf("${bucket.count} ${kindUnitLabel(bucket.kind)}")
    if (bucket.totalSize > 0L) parts += FileFormatters.formatSize(bucket.totalSize)
    if (bucket.totalDurationMs > 0L) parts += FileFormatters.formatDuration(bucket.totalDurationMs)
    return parts.joinToString(" · ")
}

private fun fileMeta(file: MediaFile): String {
    if (file.isDirectory) {
        val count = file.childCount
        return if (count != null) "$count 项" else "文件夹"
    }
    val parts = mutableListOf<String>()
    if (file.size > 0L) parts += FileFormatters.formatSize(file.size)
    if (file.lastModified > 0L) parts += FileFormatters.formatDate(file.lastModified)
    if (parts.isEmpty()) parts += kindLabel(file.kind)
    return parts.joinToString(" | ")
}

private fun gridMeta(file: MediaFile): String {
    if (file.isDirectory) {
        val count = file.childCount
        return if (count != null) "$count 项" else "文件夹"
    }
    return if (file.size > 0L) FileFormatters.formatSize(file.size) else kindLabel(file.kind)
}

private fun summaryText(state: LibraryUiState): String {
    val summary = state.summary
    val kind = state.kind
    val parts = mutableListOf<String>()
    when {
        state.isSearchActive -> {
            parts += "找到 ${summary.fileCount} ${kindUnitLabel(kind)}"
            if (summary.totalSize > 0L) parts += FileFormatters.formatSize(summary.totalSize)
        }

        state.location == LibraryLocation.Root -> {
            if (summary.albumCount > 0) parts += "${summary.albumCount} 个专辑"
            if (summary.folderCount > 0) parts += "${summary.folderCount} 个文件夹"
            if (summary.fileCount > 0) parts += "${summary.fileCount} ${kindUnitLabel(kind)}"
            if (summary.totalSize > 0L) parts += FileFormatters.formatSize(summary.totalSize)
            if (summary.totalDurationMs > 0L) {
                parts += FileFormatters.formatDuration(summary.totalDurationMs)
            }
        }

        state.location is LibraryLocation.Bucket -> {
            parts += "${summary.fileCount} ${kindUnitLabel(kind)}"
            if (summary.totalSize > 0L) parts += FileFormatters.formatSize(summary.totalSize)
            if (summary.totalDurationMs > 0L) {
                parts += FileFormatters.formatDuration(summary.totalDurationMs)
            }
        }

        else -> {
            if (summary.directoryCount > 0) parts += "${summary.directoryCount} 个文件夹"
            parts += "${summary.fileCount} ${kindUnitLabel(kind)}"
            if (summary.totalSize > 0L) parts += FileFormatters.formatSize(summary.totalSize)
            if (summary.totalDurationMs > 0L) {
                parts += FileFormatters.formatDuration(summary.totalDurationMs)
            }
        }
    }
    return parts.joinToString(" · ")
}

private fun viewerTitle(state: LibraryUiState): String = when (val location = state.location) {
    is LibraryLocation.Root -> "图片"
    is LibraryLocation.Bucket -> location.bucketName
    is LibraryLocation.Directory -> {
        if (state.storageRootPath.isNotBlank() && location.path == state.storageRootPath) {
            "内部存储"
        } else {
            directoryDisplayName(location.path)
        }
    }
}

private fun launchImageViewer(
    context: Context,
    clicked: MediaFile,
    images: List<MediaFile>,
    title: String,
) {
    val paths = ArrayList<String>(images.size)
    images.forEach { paths.add(it.path) }
    val index = images.indexOfFirst { it.path == clicked.path }.coerceAtLeast(0)
    val intent = Intent(context, ImageViewerActivity::class.java).apply {
        putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, paths)
        putExtra(ImageViewerActivity.EXTRA_INDEX, index)
        putExtra(ImageViewerActivity.EXTRA_TITLE, title)
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(intent)
}
