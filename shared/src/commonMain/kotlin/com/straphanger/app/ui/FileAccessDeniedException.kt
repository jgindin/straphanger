package com.straphanger.app.ui

/**
 * The platform's refusal to open a file the user chose, such as Android's `SecurityException` for a
 * content URI whose grant was revoked.
 */
expect open class FileAccessDeniedException : Exception
