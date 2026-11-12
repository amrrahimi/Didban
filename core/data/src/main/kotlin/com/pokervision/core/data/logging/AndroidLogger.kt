package com.pokervision.core.data.logging

import android.util.Log
import com.pokervision.core.common.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwards to Logcat. Never log image content, card values of other players, or file contents;
 * paths and counts only.
 */
@Singleton
class AndroidLogger @Inject constructor() : AppLogger {
    override fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(PREFIX + tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(PREFIX + tag, message, throwable)
    }

    private companion object {
        const val PREFIX = "PV/"
    }
}
