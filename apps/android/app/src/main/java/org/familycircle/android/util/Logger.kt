package org.familycircle.android.util

import android.util.Log
import org.familycircle.android.BuildConfig

/**
 * Tiny logging wrapper.
 *
 * Rules (enforced by review, not by the compiler):
 * * Never pass coordinates, tokens, passwords, e-mail addresses or server responses to the logger.
 *   Log *what* happened (e.g. "uploaded 12 points"), never *where*.
 * * [d] is compiled in, but only emits in debug builds; release builds additionally strip all
 *   [d] calls with R8 (`-assumenosideeffects` in proguard-rules.pro).
 */
object Logger {
    private const val PREFIX = "FC/"

    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message())
    }

    fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        // Only the exception class is logged: messages of network exceptions can contain host names
        // or (for parsing errors) fragments of response bodies.
        Log.w(PREFIX + tag, if (error == null) message else "$message (${error.javaClass.simpleName})")
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(PREFIX + tag, if (error == null) message else "$message (${error.javaClass.simpleName})")
    }
}
