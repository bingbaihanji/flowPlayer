package com.bingbaihanji.flowplayer.feature.library

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.imageLoader

/**
 * 文件库专用 Coil ImageLoader：在默认解码器基础上注册视频帧解码器，
 * 以便视频/相册封面显示视频首帧。
 *
 * 使用独立实例 + 显式 imageLoader 参数，避免依赖 Application 配置的全局单例
 * （Application 由协调者维护，本 feature 不修改共享文件）。
 */
internal object LibraryImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: context.applicationContext.imageLoader.newBuilder()
                .components {
                    add(VideoFrameDecoder.Factory())
                }
                .crossfade(true)
                .build()
                .also { instance = it }
        }
    }
}
