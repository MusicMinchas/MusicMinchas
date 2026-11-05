package com.kike.musicplayer.util

import android.content.Context
import android.util.Log
import com.kike.musicplayer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        logFile = File(context.filesDir, "debug_stream.log")
        log("AppLog", "=== App started ===")
    }

    fun log(tag: String, msg: String, err: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val time = dateFormat.format(Date())
        val errStr = err?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        val line = "$time [$tag] $msg$errStr"
        Log.e(tag, msg, err)
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }
}
