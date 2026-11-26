package com.bingbaihanji.flowplayer.feature.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bingbaihanji.flowplayer.core.common.permissions.hasStorageAccess
import com.bingbaihanji.flowplayer.core.common.permissions.openAllFilesAccessSettings
import com.bingbaihanji.flowplayer.core.ui.components.ScreenScaffold

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasStorageAccess(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasAccess = hasStorageAccess(context)
    }

    LifecycleResumeEffect(Unit) {
        hasAccess = hasStorageAccess(context)
        onPauseOrDispose { }
    }

    ScreenScaffold(title = "本地媒体") {
        if (!hasAccess) {
            Text(
                text = "需要存储权限才能浏览设备文件。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Android 11 及以上需要“所有文件访问权限”；旧系统需要读写存储权限。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
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
                },
            ) {
                Text("去授权")
            }
        } else {
            Text(
                text = "文件列表将在 M2 实现。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
