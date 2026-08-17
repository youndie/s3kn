package io.github.youndie.s3.example

import io.github.youndie.s3.S3Client
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import io.github.youndie.s3.S3Exception
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Proves that a native binary in a container can complete a TLS handshake with S3.
 *
 * The credentials are deliberately wrong. A `403` means the connection was established, the
 * certificate verified and a signed request answered — everything this is meant to prove. What it
 * is looking for is the other outcome: with no root certificates in the image, the request fails
 * before any HTTP status exists at all, and the message says nothing about certificates.
 *
 * Exit code 0 means TLS worked. Anything else means it did not.
 */
fun main(): Unit =
    runBlocking {
        val endpoint = "https://s3.us-east-1.amazonaws.com"
        val client =
            S3Client(
                config =
                    S3Config(
                        endpoint = S3Endpoint.parse(endpoint),
                        region = "us-east-1",
                        credentials = S3Credentials("AKIDEXAMPLE", "not-a-real-secret-key"),
                    ),
                http = HttpClient(Curl),
            )

        try {
            client.head("s3kn-tls-check-no-such-bucket", "probe")
            println("TLS OK: $endpoint answered a signed request")
        } catch (failure: S3Exception) {
            // An HTTP status of any kind is the proof: it cannot exist without a finished handshake.
            println("TLS OK: $endpoint answered with ${failure.status}")
        } catch (failure: Throwable) {
            println("TLS FAILED: $endpoint could not be reached: ${failure::class.simpleName}: ${failure.message}")
            println("If this image has no /etc/ssl/certs, install ca-certificates — see the Dockerfile.")
            exitProcess(1)
        }
    }
