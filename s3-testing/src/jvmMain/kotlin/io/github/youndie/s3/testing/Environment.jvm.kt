package io.github.youndie.s3.testing

public actual fun environmentVariable(name: String): String? = System.getenv(name)?.ifEmpty { null }
