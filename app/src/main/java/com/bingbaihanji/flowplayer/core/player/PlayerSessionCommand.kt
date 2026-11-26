package com.bingbaihanji.flowplayer.core.player

/**
 * 播放会话自定义命令约定。
 *
 * 服务端在 onConnect 中暴露 [ACTION_SET_EXTERNAL_SUBTITLE]，控制器通过
 * MediaController.sendCustomCommand 发送；参数统一放在 Bundle 中。
 */
object PlayerSessionCommand {
    const val ACTION_SET_EXTERNAL_SUBTITLE = "com.bingbaihanji.flowplayer.SET_EXTERNAL_SUBTITLE"

    const val KEY_SUBTITLE_URI = "subtitle.uri"
    const val KEY_SUBTITLE_MIME = "subtitle.mime"
}
