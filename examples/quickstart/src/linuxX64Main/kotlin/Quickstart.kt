package io.github.youndie.s3.example

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Client
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import io.github.youndie.s3.sigv4.S3Signer
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

/**
 * Everything the README shows, in a form the build compiles.
 *
 * It is not run: it would need real credentials. Compiling is the point — a README example that
 * nothing compiles drifts from the API within two milestones, and the drift is invisible until
 * somebody tries it.
 */
fun main(): Unit =
    runBlocking {
        val client =
            S3Client(
                config =
                    S3Config(
                        endpoint = S3Endpoint.parse("https://s3.us-east-1.amazonaws.com"),
                        region = "us-east-1",
                        credentials = S3Credentials(accessKeyId = "…", secretAccessKey = "…"),
                    ),
                // The engine is yours: the client neither picks one nor closes it.
                http = HttpClient(Curl),
            )

        client.put("photos", "hello.txt", "hello".encodeToByteArray(), contentType = "text/plain")

        // The body is a stream, valid only inside the block — a large object is never held whole.
        val text = client.get("photos", "hello.txt") { it.body.readRemaining().readByteArray() }
        println(text.decodeToString())

        client.list("photos", prefix = "hel").collect { page ->
            page.objects.forEach { println("${it.key} (${it.size} bytes)") }
        }

        // Presigning sends nothing, so it works on targets that have no HTTP engine at all.
        println(S3Signer(client.config).presign("GET", "photos", "hello.txt"))

        // Against MinIO or anything else on a local network, two settings change.
        S3Config(
            endpoint = S3Endpoint.parse("http://127.0.0.1:9000"),
            region = "us-east-1",
            credentials = S3Credentials("…", "…"),
            // 127.0.0.1 cannot carry a bucket as a DNS label.
            addressingStyle = AddressingStyle.PATH,
            // A streamed body cannot be hashed, and over plain HTTP nothing else protects it.
            // Saying so is the point; leaving it off means nobody sends one by accident.
            allowUnsignedPayloadOverHttp = true,
        )
    }
