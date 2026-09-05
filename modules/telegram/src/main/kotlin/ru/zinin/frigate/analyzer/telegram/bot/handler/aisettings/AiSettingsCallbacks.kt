package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

/** Payload-ы экрана `/ai` в одном месте, чтобы рендер и диспетчер коллбэков не разошлись. */
object AiSettingsCallbacks {
    const val PREFIX = "aip:"
    const val CLOSE = PREFIX + "close"
    const val ON = PREFIX + "on"
    const val OFF = PREFIX + "off"
    const val SET_PREFIX = PREFIX + "set:"
}
