package com.pokervision.core.common.logging

/**
 * Platform-independent logging abstraction. The Android implementation lives in :core:data
 * and forwards to android.util.Log; pure-Kotlin modules and tests can use [NoOpLogger].
 */
interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpLogger : AppLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
