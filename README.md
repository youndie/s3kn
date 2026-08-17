# s3kn

An S3 client for Kotlin/Native. First target: `linuxX64`.

**Status: M0 done — build skeleton and the vendored AWS test vectors. No S3 code yet.**

Scope of v1: `put`, `get`, `delete`, `head`, `list`, multipart upload, presigned URLs.

## Building

Requires JDK 25; Gradle arrives through the wrapper.

```bash
./gradlew build
```

`linuxX64` is the primary target and its tests do not run on macOS — the Kotlin Gradle plugin
disables `linuxX64Test` on a non-Linux host. `macosArm64` is declared for the local loop, and CI
runs both.

## Where to start

| Document | What it answers |
|---|---|
| [docs/research/research-architecture.md](docs/research/research-architecture.md) | Why the architecture is what it is — verified facts, decisions, risks |
| [docs/api/protocol-s3.md](docs/api/protocol-s3.md) | The wire contract: addressing, key encoding, SigV4, the seven operations, errors |
| [docs/spec/README.md](docs/spec/README.md) | The specification copies vendored in this repository |
| [BACKLOG.md](BACKLOG.md) | Milestones M0…M8 |
| [CLAUDE.md](CLAUDE.md) | Working rules for this repository |

Documentation is written in Russian; code, tests and commit messages are in English.
