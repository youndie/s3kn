package io.github.youndie.s3.testing

// kontainer has no JVM target yet: a JVM run reads S3_E2E_ENDPOINT, as before (M-129).
internal actual fun ownServerEndpoint(): String? = null
