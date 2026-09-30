package io.github.youndie.s3.testing

// No Docker on Apple runners, and kontainer is linuxX64 only: the live tests skip here unless given an endpoint.
internal actual fun ownServerEndpoint(): String? = null
