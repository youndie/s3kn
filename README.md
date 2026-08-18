# s3kn

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![platform](https://img.shields.io/badge/platform-linuxX64%20%7C%20jvm%20%7C%20apple-blue?logo=kotlin&logoColor=white)](#platform-support)
[![s3-client](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/s3-client?name=s3-client&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/s3-client)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

S3 for Kotlin/Native: SigV4 written from the specification, built so that a service on
Kotlin/Native can use object storage without a JVM anywhere in sight.

On the JVM this was solved long ago. On Kotlin/Native there is nothing: the AWS SDK for Kotlin
publishes JVM artefacts only, and binding `aws-c-s3` through cinterop drags in five C libraries and
their build onto every target — while you still have to understand SigV4 to sign a URL. This
library writes the protocol out instead: the signature, and seven HTTP requests.

## Overview

- Signature Version 4, header-based and in the query string, checked against **all 34 official AWS
  test vectors** — including the four other SDKs skip
- S3's own signing rule, where the path is signed **verbatim**: no dot segments removed, no second
  round of encoding, unlike every other AWS service
- One encoder for the object key, used by both the signer and the URL builder — two that differ by
  a single character produce a `SignatureDoesNotMatch` that names nothing
- Presigned URLs for reading and writing, up to the seven-day ceiling. Presigning sends nothing, so
  it works on every KMP target, including those with no HTTP engine at all
- `put`, `get`, `delete`, `head`, with bodies streamed in both directions, so a five-gigabyte object
  is never held whole
- Listing as a `Flow` of pages, with `encoding-type=url` always on, because an object key may hold
  characters XML 1.0 cannot represent
- Multipart upload several parts at a time, bounded memory, and an abort on every path that can
  fail — parts nobody completes are billed until somebody notices
- Anything that cannot arrive is refused rather than sent: a key with a `.` path segment, a streamed
  body with no length, a streamed body left unprotected over plain HTTP

## Platform support

**`linuxX64` is the platform this is built for and the only one it is claimed to work on.** Every
milestone is closed there: the whole suite runs, including every operation against a real S3 server.

| Target | Engine | |
|---|---|---|
| `linuxX64` | curl | supported — full suite and live tests in CI |
| `jvm` | CIO | the same code and the same live tests run in CI; not claimed yet only because nothing has been released |
| `macosArm64` | Darwin | the whole suite, live tests included, has been run against a real server — by hand, not in CI |
| `iosSimulatorArm64` | Darwin | unit tests run in CI on a simulator; no live tests |
| `iosX64` | Darwin | compiles and publishes; the CI runner is Apple Silicon, so nothing has ever run on it |
| `iosArm64` | Darwin | compiles and publishes; a device test has never been run |
| `watchos`, `tvos` | — | not declared. Every dependency publishes them, and that is not the same claim |
| `macosX64` | — | not declared: Kotlin has deprecated the target, and nothing ever ran on it here |

Two engines, because no one engine covers everything. `ktor-client-curl` is the only one that
speaks HTTPS on Linux — `ktor-network-tls` is a stub that throws at runtime — and it publishes
nothing for iOS at all. On Apple the engine is `ktor-client-darwin`, which also settles the
certificate question below: NSURLSession uses the system trust store, so an Apple target needs
nothing installed.

The library depends on neither. Pick the engine, hand over an `HttpClient`.

## Add dependencies

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "WipSnapshots"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    implementation("io.github.youndie:s3-client:0.1.0-SNAPSHOT")
    // The engine is yours, and which one exists depends on the target.
    implementation("io.ktor:ktor-client-curl:3.5.2")   // Linux, Windows, macOS
    implementation("io.ktor:ktor-client-darwin:3.5.2") // macOS, iOS, and the only choice on iOS
}
```

`s3-sigv4` can be taken on its own if all you need is signing or presigning — it has no HTTP
dependency at all.

Snapshots only so far; Maven Central is deliberately left until the API stops moving. See
[RELEASING.md](RELEASING.md).

## Usage

```kotlin
suspend fun main() {
    val client = S3Client(
        config = S3Config(
            endpoint = S3Endpoint.parse("https://s3.us-east-1.amazonaws.com"),
            region = "us-east-1",
            credentials = S3Credentials(accessKeyId = "…", secretAccessKey = "…"),
        ),
        // The client neither picks the engine nor closes it.
        http = HttpClient(Curl),
    )

    client.put("photos", "hello.txt", "hello".encodeToByteArray(), contentType = "text/plain")

    // The body is a stream, valid only inside the block — a large object is never held whole.
    val text = client.get("photos", "hello.txt") { it.body.readRemaining().readByteArray() }
    println(text.decodeToString())

    client.list("photos", prefix = "hel").collect { page ->
        page.objects.forEach { println("${it.key} (${it.size} bytes)") }
    }

    // Presigning sends nothing, so it works where there is no HTTP engine at all.
    println(S3Signer(client.config).presign("GET", "photos", "hello.txt"))
}
```

A runnable version is [examples/quickstart](examples/quickstart/src/linuxX64Main/kotlin/Quickstart.kt);
it is compiled by every build, so it cannot rot unnoticed.

Against MinIO or anything else on a local network, two settings change:

```kotlin
S3Config(
    endpoint = S3Endpoint.parse("http://127.0.0.1:9000"),
    region = "us-east-1",
    credentials = S3Credentials("…", "…"),
    // 127.0.0.1 cannot carry a bucket as a DNS label.
    addressingStyle = AddressingStyle.PATH,
    // A streamed body cannot be hashed, and over plain HTTP nothing else protects it. Stating the
    // choice is the point; leaving it off means nobody makes it by accident.
    allowUnsignedPayloadOverHttp = true,
)
```

## Gotchas

Four things that cost a day each if you meet them without warning.

- **A key containing a `.` or `..` path segment is refused, on every platform.** libcurl removes
  such segments from the path *after* the request is signed, so through that engine it could never
  arrive, and the symptom would be a bare `SignatureDoesNotMatch`. NSURLSession and the JVM engines
  deliver it intact, and S3 accepts such keys — so this forbids something that works on two engines
  of three. The signer cannot see which engine it will be handed, and a silent failure on the
  primary target is worse than a refusal everywhere. `.hidden` and `..trailer` are ordinary
  names — only a whole segment counts.
- **A streamed body needs its length stated.** Without it the engine falls back to chunked encoding
  and S3 answers `411 MissingContentLength` — a failure that never shows up with a `ByteArray`.
- **A Linux container needs `ca-certificates`.** TLS itself is inside the curl engine's klib, but
  the root certificates are read from `/etc/ssl/certs` at runtime and a slim image has none. See
  [examples/tls-check](examples/tls-check), which builds the image both ways and requires opposite
  outcomes. This does not apply on Apple: NSURLSession uses the system trust store.
- **Completing a multipart upload can fail with `200 OK`.** Handled here — worth knowing if you ever
  read the raw responses.

## Internals

| Layer | |
|---|---|
| Encoding | one function for the object key, called by the signer and the URL builder alike. The signature covers the path exactly as it goes on the wire |
| Time | `kotlin.time.Clock` plus fifteen lines of calendar arithmetic. The scope date is a substring of the timestamp, not a second calculation — computing it separately is correct 86 399 seconds a day |
| Signing | one core, two path modes: normalised for the generic SigV4 the official vectors describe, verbatim for S3 |
| Transport | a `HttpClient` you supply. The engine abstraction is already the point of substitution; a second port on top would only re-wrap it |
| XML | a hand-rolled reader for six flat documents, one of them read on the failure path — where a parser that can throw would replace one error with another |

| Module | |
|---|---|
| `s3-core` | model, key encoding, timestamps, configuration, errors. No dependencies at all |
| `s3-sigv4` | SigV4 and presigning. Pure Kotlin — no network, no engine, no cinterop |
| `s3-client` | the seven operations over `ktor-client-core`, on whatever engine you supply |
| `s3-testing` | vectors and switches for the live tests; not published |

## Testing

189 tests on `linuxX64` and on the JVM, 21 of them against a real S3 server. Each cites the line of
the specification it came from — the copies live in [docs/spec/](docs/spec/) and open offline.

The signer is checked against the **34 official AWS test vectors**, including the four botocore's
own runner skips: one because a general HTTP parser cannot read a request line containing a space,
three because parsing a query into a map loses repeated names. Neither limit belongs to the
algorithm.

AWS publishes no vectors for S3's own signing rule or for presigned URLs, so twenty more are
generated from botocore by [a script committed beside them](docs/spec/s3-signing-vectors/generate.py).
Not official, and still an independent implementation rather than a restatement of this library's
behaviour. The key-encoding table is the sharpest of them: its expectations come from Python's
`quote`, so agreeing with it checks the reading of the rule rather than its repetition.

A green vector suite proves nothing by itself, so it is mutated: switching the signer to the other
path mode fails 10 of the 34, and sorting the presigned query differently fails every presign case.

Live tests run against MinIO from `docker-compose.yml`. They found what no vector could — that
libcurl rewrites the path after signing, that a listing returns a space as `+` rather than `%20`,
and that the five-mebibyte minimum part size, which the API model declines to state, is real.

## What this is not

- **Not a full S3 client.** Seven operations. No ACLs, tags, versions, lifecycle, batch delete, copy
  or server-side encryption.
- **Not a bucket manager.** Creating and deleting buckets is out of scope; the tests use `mc` for it.
- **Not usable with keys containing `.` or `..` segments.** S3 accepts them; no HTTP client this
  library can reach will deliver them.
- **Not faster than the dispatcher you call it on.** A multipart upload signs every part, and
  hashing is real CPU work; `putMultipart` inherits the caller's coroutine context, so calling it
  from `runBlocking {}` — one event-loop thread — leaves that hashing serialised on a single thread
  no matter how many parts go at once. Handing the same code `Dispatchers.Default` moved the ceiling
  by 2.3× in a measurement, with the same engine and the same server. **Call it from a
  multi-threaded dispatcher.** The engine is not the variable here: curl and CIO produce the same
  curve and the same saturated thread. Numbers, and what they do not prove:
  [docs/measurements.md](docs/measurements.md).

## Building

Requires JDK 25; Gradle arrives through the wrapper.

```bash
./gradlew build
```

The live tests need an S3 server:

```bash
docker compose up -d --wait minio
docker compose run --rm create-buckets
S3_E2E_ENDPOINT=http://127.0.0.1:9000 ./gradlew build
```

Without `S3_E2E_ENDPOINT` they skip themselves. CI sets `S3_E2E_REQUIRED=1` so that a missing server
fails the build instead of quietly running nothing — a skipped test reads exactly like a passing one.

`linuxX64` tests do not run on macOS: the Kotlin Gradle plugin disables the task on a non-Linux
host. `macosArm64` is declared for the local loop, and CI runs both.

## Documentation

[docs/](docs/) — the architecture research with the reasoning behind each decision, the wire contract
every test is written from, and the specification kept in the repository so that a citation can be
opened offline. Written in Russian.

Read the research before changing anything: several decisions here are counter-intuitive, and the
backlog records what was measured and turned out otherwise — starting with libcurl quietly rewriting
a path that had already been signed.

## License

MIT. See [LICENSE](LICENSE). `docs/spec/` contains third-party material redistributed under
Apache 2.0 with its own `LICENSE` and `NOTICE` files.
