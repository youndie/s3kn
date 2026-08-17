# s3kn

An S3 client for Kotlin/Native. First target: `linuxX64`.

A service written in Kotlin/Native has nothing to talk to object storage with: the AWS SDK for
Kotlin publishes JVM artefacts only, and binding `aws-c-s3` through cinterop drags in five C
libraries and their build on every target. This is the protocol written out instead — SigV4 signing
and seven HTTP requests.

**Status: every v1 operation works and is checked against a live server.** Snapshots are published;
there is no release on Maven Central yet, and the API may still move.

## What it does

`put`, `get`, `delete`, `head`, listing, multipart upload, presigned URLs. Nothing else — no ACLs,
tags, versions, lifecycle or server-side encryption.

## Using it

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    implementation("io.github.youndie:s3-client:0.1.0-SNAPSHOT")
    // On Kotlin/Native this is the only engine that speaks HTTPS. It carries its own libcurl and
    // OpenSSL, so nothing has to be installed for TLS — but the image still needs ca-certificates.
    implementation("io.ktor:ktor-client-curl:3.5.2")
}
```

The code below is [examples/quickstart](examples/quickstart), so the build compiles it — a README
example nothing compiles drifts from the API within two milestones, invisibly.

```kotlin
import io.github.youndie.s3.*
import io.github.youndie.s3.sigv4.S3Signer
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

suspend fun main() {
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

    client.list("photos", prefix = "hol").collect { page ->
        page.objects.forEach { println("${it.key} (${it.size} bytes)") }
    }

    // Presigning sends nothing, so it works on targets that have no HTTP engine at all.
    println(S3Signer(client.config).presign("GET", "photos", "hello.txt"))
}
```

Against MinIO or anything else on a local network, two settings change:

```kotlin
S3Config(
    endpoint = S3Endpoint.parse("http://127.0.0.1:9000"),
    region = "us-east-1",
    credentials = S3Credentials("…", "…"),
    // 127.0.0.1 cannot carry a bucket as a DNS label.
    addressingStyle = AddressingStyle.PATH,
    // A streamed body cannot be hashed, and over plain HTTP nothing else protects it. Saying so is
    // the point; leaving it off means nobody sends one by accident.
    allowUnsignedPayloadOverHttp = true,
)
```

## Things worth knowing before you hit them

- **A key containing a `.` or `..` path segment is refused.** libcurl removes such segments from
  the path *after* the request is signed, so it could never arrive; the alternative is a
  `SignatureDoesNotMatch` that explains nothing. `.hidden` and `..trailer` are ordinary names —
  only a whole segment counts.
- **A streamed body needs its length stated.** Without it the engine falls back to chunked encoding
  and S3 answers `411 MissingContentLength`.
- **A container needs `ca-certificates`.** TLS itself is inside the engine's klib, but the root
  certificates are read from `/etc/ssl/certs` at runtime and a slim image has none. See
  [examples/tls-check](examples/tls-check), which builds the image both ways and requires opposite
  outcomes.
- **Completing a multipart upload can fail with `200 OK`.** Handled here; worth knowing if you ever
  read the raw responses.

## Building

Requires JDK 25; Gradle arrives through the wrapper.

```bash
./gradlew build
```

The protocol tests need an S3 server:

```bash
docker compose up -d --wait
S3_E2E_ENDPOINT=http://127.0.0.1:9000 ./gradlew build
```

Without `S3_E2E_ENDPOINT` they skip themselves. CI sets `S3_E2E_REQUIRED=1` so that a missing
server fails the build instead of quietly running nothing.

`linuxX64` is the primary target and its tests do not run on macOS — the Kotlin Gradle plugin
disables `linuxX64Test` on a non-Linux host. `macosArm64` is declared for the local loop, and CI
runs both.

## Where to start

| Document | What it answers |
|---|---|
| [docs/research/research-architecture.md](docs/research/research-architecture.md) | Why the architecture is what it is — verified facts, decisions, risks |
| [docs/api/protocol-s3.md](docs/api/protocol-s3.md) | The wire contract: addressing, key encoding, SigV4, the seven operations, errors |
| [docs/README.md](docs/README.md) | The documentation map: features, modules, conventions |
| [docs/spec/README.md](docs/spec/README.md) | The specification copies vendored in this repository |
| [BACKLOG.md](BACKLOG.md) | Milestones M0…M8 |
| [RELEASING.md](RELEASING.md) | What is published, where, and why one job is enough |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How work is done here — test from the specification first |

Documentation is written in Russian; code, tests and commit messages are in English.

## Licence

MIT. `docs/spec/` contains third-party material redistributed under Apache 2.0 with its own
`LICENSE` and `NOTICE` files — see [LICENSE](LICENSE).
