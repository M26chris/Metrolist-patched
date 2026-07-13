package com.metrolist.innertube.utils

import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ParserDebugger {
    private val logFile = File("/data/data/com.metrolist.music.debug/cache/parser_failure_dump.log")
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Bridges the 3-argument calls from SearchPage and SearchSummaryPage
     * directly into the debugger logger.
     */
    fun traceSkippedItem(contextTag: String, message: String, renderer: Any?) {
        val rendererName = renderer?.javaClass?.simpleName ?: "UnknownRenderer"
        val reason = "$message (Type: $rendererName)"
        
        // 1. Log warning to Logcat via Timber
        Timber.w("InnerTube Parser [$contextTag]: $reason")
        
        // 2. Dump the raw data block to your diagnostics file
        dumpFailure(
            contextTag = contextTag,
            exactReason = reason,
            rawContent = renderer?.toString()
        )
    }
    
    fun log(message: String) {
        try {
            Timber.d(message)

            if (!logFile.parentFile.exists()) {
                logFile.parentFile.mkdirs()
            }

            FileWriter(logFile, true).use { writer ->
                val now = timeFormat.format(Date())
                writer.write("[$now] [DEBUG]\n")
                writer.write(message)
                writer.write("\n------------------------------------------------------\n\n")
            }
        } catch (t: Throwable) {
            Log.e("ParserDebugger", "Failed writing debug log", t)
        }
    }

    @Synchronized
    fun dumpFailure(contextTag: String, exactReason: String, rawContent: String?) {
        try {
            if (!logFile.parentFile.exists()) {
                logFile.parentFile.mkdirs()
            }

            FileWriter(logFile, true).use { writer ->
                val now = timeFormat.format(Date())
                writer.write("[$now] [$contextTag] FAILURE: $exactReason\n")
                if (!rawContent.isNullOrBlank()) {
                    writer.write("RAW RENDERER SNAPSHOT:\n$rawContent\n")
                }
                writer.write("-----------------------------------------------------------------\n\n")
            }
            Log.d("ParserDebugger", "Successfully saved diagnostic trace for $contextTag")
        } catch (t: Throwable) {
            Log.e("ParserDebugger", "Failed writing diagnostic snapshot", t)
        }
    }
}