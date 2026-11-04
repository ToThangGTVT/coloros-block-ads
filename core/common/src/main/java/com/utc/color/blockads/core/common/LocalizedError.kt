package com.utc.color.blockads.core.common

import android.content.res.Resources

/** Defers validation error translation until the message is shown by the UI. */
class LocalizedException(
    val messageRes: Int,
    val formatArgs: Array<out Any>,
) : IllegalArgumentException("Validation failed (resource $messageRes)")

fun localizedError(messageRes: Int, vararg formatArgs: Any): Nothing =
    throw LocalizedException(messageRes, formatArgs)

fun Throwable.userMessage(resources: Resources): String? =
    if (this is LocalizedException) resources.getString(messageRes, *formatArgs) else message
