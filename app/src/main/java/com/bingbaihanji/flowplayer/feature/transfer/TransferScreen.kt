package com.bingbaihanji.flowplayer.feature.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bingbaihanji.flowplayer.core.data.file.FileFormatters
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerContract
import com.bingbaihanji.flowplayer.core.player.PlayerLauncher
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.transfer.TransferFile
import com.bingbaihanji.flowplayer.core.transfer.TransferState
import kotlinx.coroutines.launch

private val WifiWarningContainer = Color(0xFFFFF6E7)
private val WifiWarningBadge = Color(0xFFFFE8C2)
private val WifiWarningAccent = Color(0xFFE6A23C)
private val WifiWarningText = Color(0xFF9A6412)

/**
 * 接收文件（WiFi 传输）页面。
 *
 * 页面只通过 [TransferViewModel] 读取 core/transfer 的状态，不直接访问服务实现。
 * 布局顺序固定为：可选的局域网提示卡 → 服务状态卡 → 接收列表标题 → 文件列表；
 * 顶栏“接收列表”据此计算滚动位置。
 */
@SuppressLint("InlinedApi")
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TransferViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val supported = viewModel.supported
    val lanConnected = rememberLanConnected()
    // 系统过旧时整个内嵌传输不可用，不再显示“请先连接 Wi-Fi”之类的误导提示。
    val showNetworkWarning = supported && !lanConnected
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var deleteTarget by remember { mutableStateOf<TransferFile?>(null) }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限，接收服务仍可使用，但不会显示前台通知",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 页面首次进入时请求一次通知权限；拒绝不阻塞页面使用。不支持内嵌传输时无需请求。
    LaunchedEffect(supported) {
        if (supported && !notificationAsked && !hasNotificationPermission(context)) {
            notificationAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val url = transferUrl(state)
    val startServer: () -> Unit = {
        // Android 13+ 启动前台服务前再请求一次通知权限；无论结果如何都继续启动。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TransferTopBar(
            fileCount = state.files.size,
            onBack = onBack,
            onOpenList = {
                val filesHeaderIndex = if (showNetworkWarning) 2 else 1
                scope.launch {
                    listState.animateScrollToItem(filesHeaderIndex)
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showNetworkWarning) {
                item(key = "lan-warning") {
                    LanWarningCard(onOpenWifiSettings = { openWifiSettings(context) })
                }
            }
            item(key = "server-status") {
                ServerStatusCard(
                    state = state,
                    url = url,
                    supported = supported,
                    onCopy = { url?.let { copyAddress(context, it) } },
                    onStart = startServer,
                    onStop = viewModel::stop,
                )
            }
            item(key = "files-header") {
                FilesHeader(fileCount = state.files.size, onRefresh = viewModel::refresh)
            }
            if (state.files.isEmpty()) {
                item(key = "files-empty") {
                    FilesEmptyState(onRefresh = viewModel::refresh)
                }
            } else {
                items(items = state.files, key = { it.path }) { file ->
                    TransferFileRow(
                        file = file,
                        onClick = { openTransferFile(context, file, state.files) },
                        onDelete = { deleteTarget = file },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件") },
            text = {
                Text(
                    text = "确定要删除「${target.name}」吗？此操作不可恢复。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.delete(target)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 顶栏
// -------------------------------------------------------------------------------------------------

@Composable
private fun TransferTopBar(
    fileCount: Int,
    onBack: () -> Unit,
    onOpenList: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "接收文件",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = onOpenList,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (fileCount > 0) "接收列表 $fileCount" else "接收列表",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Wi-Fi 提示
// -------------------------------------------------------------------------------------------------

@Composable
private fun LanWarningCard(onOpenWifiSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = WifiWarningContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(WifiWarningBadge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = WifiWarningAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "未连接局域网",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "请先连接 Wi-Fi 或以太网，电脑与手机需处于同一局域网。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenWifiSettings,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, WifiWarningAccent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WifiWarningText),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("打开 Wi-Fi 设置", fontSize = 14.sp)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 服务状态 / 地址 / 二维码
// -------------------------------------------------------------------------------------------------

@Composable
private fun ServerStatusCard(
    state: TransferState,
    url: String?,
    supported: Boolean,
    onCopy: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val error = state.error?.takeIf { it.isNotBlank() }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(serverStatusColor(state, supported), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = serverStatusTitle(state, supported),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (supported && state.starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            ErrorBanner(message = error)
        }
        when {
            !supported -> UnsupportedContent()
            state.running -> RunningContent(
                state = state,
                url = url,
                onCopy = onCopy,
                onStop = onStop,
            )

            state.starting -> StartingContent()
            else -> StoppedContent(state = state, onStart = onStart)
        }
    }
}

@Composable
private fun UnsupportedContent() {
    Spacer(Modifier.height(14.dp))
    Text(
        text = "当前系统版本不支持内嵌传输（需 Android 8.0+）",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RunningContent(
    state: TransferState,
    url: String?,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    if (url != null) {
        Text(
            text = "在电脑浏览器中打开",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制地址",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TransferQrCode(content = url)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "扫描二维码，或在电脑浏览器输入上方地址",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Text(
            text = "正在获取局域网地址，请确认手机已连接 Wi-Fi。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    Spacer(Modifier.height(10.dp))
    InfoRow(
        icon = Icons.Filled.Info,
        label = "剩余空间",
        value = if (state.freeSpaceBytes > 0L) {
            FileFormatters.formatSize(state.freeSpaceBytes)
        } else {
            "未知"
        },
    )
    if (state.storagePath.isNotBlank()) {
        InfoRow(
            icon = Icons.Filled.Folder,
            label = "保存目录",
            value = state.storagePath,
            maxLines = 2,
        )
    }

    if (url != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "电脑端操作步骤",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        StepRow(1, "确保电脑与手机连接同一个 Wi-Fi，并保持本页处于打开状态。")
        StepRow(2, "在电脑浏览器中输入上方地址，或扫描二维码打开传输网页。")
        StepRow(3, "在网页中选择文件上传，上传完成后可在下方接收列表查看。")
        StepRow(4, "也可在网页中下载或删除接收目录中的文件。")
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("停止服务", fontSize = 15.sp)
    }
}

@Composable
private fun StartingContent() {
    Spacer(Modifier.height(14.dp))
    Text(
        text = "正在启动局域网传输服务，请稍候…",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(23.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text("正在启动…", fontSize = 15.sp)
    }
}

@Composable
private fun StoppedContent(
    state: TransferState,
    onStart: () -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = "启动服务后，在电脑浏览器中打开地址，即可上传、下载和删除手机中的文件。",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(23.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (state.error?.isNotBlank() == true) "重新启动服务" else "启动接收服务",
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    maxLines: Int = 1,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StepRow(index: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 接收列表
// -------------------------------------------------------------------------------------------------

@Composable
private fun FilesHeader(fileCount: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "接收列表",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$fileCount 个文件",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onRefresh,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("刷新", fontSize = 13.sp)
        }
    }
}

@Composable
private fun FilesEmptyState(onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有接收文件",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "在电脑浏览器上传文件后，会显示在这里。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRefresh) {
                Text("刷新列表", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun serverStatusColor(state: TransferState, supported: Boolean): Color = when {
    !supported -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    state.running -> MaterialTheme.colorScheme.primary
    state.starting -> MaterialTheme.colorScheme.secondary
    state.error?.isNotBlank() == true -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
}

private fun serverStatusTitle(state: TransferState, supported: Boolean): String = when {
    !supported -> "当前系统不支持内嵌传输"
    state.running -> "接收服务运行中"
    state.starting -> "正在启动接收服务"
    state.error?.isNotBlank() == true -> "接收服务未运行"
    else -> "接收服务未启动"
}

// -------------------------------------------------------------------------------------------------
// 局域网连接状态检测
// -------------------------------------------------------------------------------------------------

/**
 * 检测是否存在可用的局域网连接。
 *
 * 与服务侧 resolveLanIpv4 的判定保持一致：不只检查默认网络，而是遍历全部网络查找
 * Wi-Fi / 以太网，避免 Wi-Fi 作为非默认网络（例如蜂窝数据或 VPN 抢占默认网络）时误显示未连接。
 */
@Composable
private fun rememberLanConnected(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var connected by remember(manager) { mutableStateOf(manager.hasLanConnection()) }

    // 从系统 Wi-Fi 设置返回时重新检测。
    LifecycleResumeEffect(manager) {
        connected = manager.hasLanConnection()
        onPauseOrDispose { }
    }

    DisposableEffect(manager) {
        val cm = manager
        if (cm == null) {
            onDispose { }
        } else {
            // 一个 NetworkRequest 同时匹配 Wi-Fi 或以太网，且不受“是否为默认网络”限制，
            // 局域网网络不是默认网络时也能及时收到连接/断开回调。
            val callback = object : ConnectivityManager.NetworkCallback() {
                private fun refresh() {
                    // 回调可能来自 Binder 线程，切主线程再写 Compose 状态。
                    mainExecutor.execute { connected = cm.hasLanConnection() }
                }

                override fun onAvailable(network: Network) {
                    refresh()
                }

                override fun onLost(network: Network) {
                    refresh()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refresh()
                }
            }
            val registered = runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build(),
                    callback,
                )
            }.isSuccess
            onDispose {
                if (registered) {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }
    }
    return connected
}

/** 遍历全部网络（API 21+；本项目 minSdk 24），任一 Wi-Fi / 以太网即视为局域网可用。 */
@Suppress("DEPRECATION")
private fun ConnectivityManager?.hasLanConnection(): Boolean = runCatching {
    val manager = this ?: return@runCatching false
    manager.allNetworks.any { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}.getOrDefault(false)

// -------------------------------------------------------------------------------------------------
// 地址 / 权限 / 打开文件
// -------------------------------------------------------------------------------------------------

private fun transferUrl(state: TransferState): String? {
    if (!state.running) return null
    if (state.address.isBlank() || state.port <= 0 || state.token.isBlank()) return null
    return "http://${state.address}:${state.port}/?t=${state.token}"
}

private fun copyAddress(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("接收地址", url))
    Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
}

private fun openWifiSettings(context: Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "无法打开 Wi-Fi 设置", Toast.LENGTH_SHORT).show()
        }
}

@SuppressLint("InlinedApi")
private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 列表项点击：视频/音频交给应用内播放器；图片等通过系统 ACTION_VIEW 打开。
 * 普通文件路径不能直接以 file:// 暴露给其它应用，先经 MediaScanner 换成 content://；
 * 换不到时提示无法打开，避免触发 FileUriExposedException。
 */
private fun openTransferFile(context: Context, file: TransferFile, files: List<TransferFile>) {
    val isVideo = transferFileIsVideo(file)
    val isAudio = transferFileIsAudio(file)
    if (isVideo || isAudio) {
        openInPlayer(context, file, files, isVideo)
        return
    }
    openWithSystem(context, file, transferFileMime(file))
}

private fun openInPlayer(
    context: Context,
    file: TransferFile,
    files: List<TransferFile>,
    isVideo: Boolean,
) {
    val queue = files.filter { candidate ->
        if (isVideo) transferFileIsVideo(candidate) else transferFileIsAudio(candidate)
    }.ifEmpty { listOf(file) }
    val startIndex = queue.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
    val items = queue.map { item ->
        PlayerQueueItem(
            uri = PlayerContract.uriFor(item.path),
            path = item.path,
            title = item.name,
            mimeType = transferFileMime(item),
            durationMs = 0L,
        )
    }
    runCatching {
        PlayerLauncher.start(
            context,
            PlaybackRequest(
                items = items,
                startIndex = startIndex,
                queueTitle = "接收文件",
            ),
        )
    }.onFailure {
        Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
    }
}

private fun openWithSystem(context: Context, file: TransferFile, mime: String) {
    val rawPath = file.path
    if (rawPath.startsWith("content://")) {
        launchSystemView(context, rawPath.toUri(), mime)
        return
    }
    val scanPath = if (rawPath.startsWith("file://")) rawPath.toUri().path else rawPath
    if (scanPath.isNullOrBlank()) {
        Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
        return
    }
    val scanMime = mime.takeIf { it.isNotBlank() && it != "application/octet-stream" }
    // MediaScannerConnection 的扫描回调可能运行在后台线程：Toast 与 startActivity 必须切回主线程。
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val started = runCatching {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(scanPath),
            scanMime?.let { arrayOf(it) },
        ) { _, uri ->
            mainExecutor.execute {
                if (uri != null) {
                    launchSystemView(context, uri, mime)
                } else {
                    Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }.isSuccess
    if (!started) {
        // 扫描启动失败时同样只在主线程提示，避免从非 UI 线程操作界面。
        mainExecutor.execute {
            Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun launchSystemView(context: Context, uri: Uri, mime: String) {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(viewIntent, "选择应用打开").apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
        }
}
