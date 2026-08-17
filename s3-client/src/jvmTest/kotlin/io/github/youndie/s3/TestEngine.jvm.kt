package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun realHttpClient(): HttpClient = HttpClient(CIO)
