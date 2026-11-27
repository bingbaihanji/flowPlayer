package com.bingbaihanji.flowplayer.feature.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bingbaihanji.flowplayer.R
import com.bingbaihanji.flowplayer.core.ui.components.SecondaryScreenScaffold

private val OPEN_SOURCE_COMPONENTS = listOf(
    "AndroidX / Jetpack Compose" to "Apache-2.0",
    "AndroidX Media3" to "Apache-2.0",
    "Ktor" to "Apache-2.0",
    "Room" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "ZXing" to "Apache-2.0",
    "LibVLC" to "LGPL-2.1",
)

/**
 * 关于页：版本信息、开源组件许可与本机隐私说明。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "未知"}（内部版本 $code）"
        }.getOrDefault("未知")
    }

    SecondaryScreenScaffold(title = "关于", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.about_logo),
                contentDescription = "FlowPlayer 图标",
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = "FlowPlayer",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "版本 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "本地视频、音频与图片播放管理，支持 Media3 与 LibVLC 双引擎播放，以及 WiFi 局域网文件传输。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "开源组件",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OPEN_SOURCE_COMPONENTS.forEach { (name, license) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = license,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "隐私说明",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "媒体扫描、播放记录、播放列表与设置全部保存在本机；应用不含账号、云同步、广告或统计上报。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "WiFi 传输仅在本机局域网内开启，访问需携带启动服务时生成的 token，不经过第三方服务器。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            AuthorInfoCard()
            Text(
                text = "仅供个人学习与本地使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 底部作者信息与开源地址；邮箱与 GitHub 地址可点击跳转。
 */
@Composable
private fun AuthorInfoCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "作者信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "作者：bingbaihanji",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "邮箱：bingbaihanji@gmail.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openUri(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:bingbaihanji@gmail.com")))
                },
            )
            Text(
                text = "QQ：2761868857",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "开源地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "https://github.com/bingbaihanji/flowPlayer.git",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openUri(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bingbaihanji/flowPlayer.git")),
                    )
                },
            )
        }
    }
}

private fun openUri(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
}
