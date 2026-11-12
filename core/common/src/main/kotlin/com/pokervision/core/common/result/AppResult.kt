package com.pokervision.core.common.result

/**
 * Typed result used across layer boundaries instead of throwing exceptions.
 * Domain and data layers return [AppResult]; UI layers render [AppError] into user-facing messages.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * Runs [block] and converts any thrown exception to [AppResult.Failure] via [mapError].
 * Cancellation exceptions are rethrown so coroutine cancellation is not swallowed.
 */
inline fun <T> appResultOf(
    mapError: (Throwable) -> AppError = { AppError.Unknown(it.message ?: "Unexpected error", it) },
    block: () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (c: kotlin.coroutines.cancellation.CancellationException) {
    throw c
} catch (t: Throwable) {
    AppResult.Failure(mapError(t))
}
