package com.mnnkit.app.speech

/**
 * 语音模块统一异常。
 *
 * 所有 native / 文件 / 权限失败都会转换成它，`message` 一定是可直接展示给用户的中文描述
 * （例如"原生库 libsherpa-mnn-jni.so 未加载"、"语音识别模型文件不完整，缺少 encoder*.mnn"）。
 */
class SpeechException(message: String, cause: Throwable? = null) : Exception(message, cause)
