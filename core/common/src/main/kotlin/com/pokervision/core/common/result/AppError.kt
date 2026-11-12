package com.pokervision.core.common.result

/** Error taxonomy shared by all layers. Messages are for logs; UI maps the type to localized text. */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class Io(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class NotFound(override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class PermissionDenied(override val message: String) : AppError(message)
    data class Unsupported(override val message: String) : AppError(message)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
}
