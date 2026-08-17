package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

actual fun realHttpClient(): HttpClient = HttpClient(Curl)
