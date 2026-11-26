package com.bingbaihanji.flowplayer.feature.transfer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 二维码始终按 512px 生成，界面再按 dp 缩放展示。 */
private const val QR_SIZE_PX = 512

private sealed interface QrState {
    data object Loading : QrState
    data object Failed : QrState
    data class Ready(val bitmap: Bitmap) : QrState
}

/**
 * 用 ZXing core 生成 512×512 的黑白二维码，失败或内容为空时返回 null。
 * 编码在后台线程执行，避免阻塞主线程。
 */
internal fun generateQrBitmap(content: String, sizePx: Int = QR_SIZE_PX): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

/**
 * 展示接收地址二维码。白底用于保证扫码对比度，不随深色主题变化。
 */
@Composable
internal fun TransferQrCode(content: String) {
    val qrState by produceState<QrState>(initialValue = QrState.Loading, key1 = content) {
        value = if (content.isBlank()) {
            QrState.Failed
        } else {
            withContext(Dispatchers.Default) {
                generateQrBitmap(content)?.let { QrState.Ready(it) } ?: QrState.Failed
            }
        }
    }
    Box(
        modifier = Modifier
            .size(216.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = qrState) {
            is QrState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "接收地址二维码",
                modifier = Modifier.size(196.dp),
                contentScale = ContentScale.Fit,
            )

            is QrState.Failed -> Text(
                text = "二维码生成失败",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = androidx.compose.ui.graphics.Color(0xFF8A8A8A),
            )

            QrState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
