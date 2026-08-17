package io.github.youndie.s3.testing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()?.ifEmpty { null }
