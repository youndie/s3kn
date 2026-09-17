# The image `s3kn` ships in, and it carries nothing but the binary.
#
# Build the binary first, then this image — the context is the REPOSITORY ROOT:
#
#     ./gradlew :s3-cli:linkReleaseExecutableLinuxX64
#     docker build -f s3-cli/Dockerfile -t s3kn .
#     docker run --rm --network host -e S3_ENDPOINT=http://127.0.0.1:9000 \
#         -e AWS_ACCESS_KEY_ID=… -e AWS_SECRET_ACCESS_KEY=… s3kn ls s3://bucket/
#
# `s3-cli/verify-image.sh` does all of that against the MinIO from `docker-compose.yml`.
#
# COMPILED OUTSIDE, NOT IN A BUILD STAGE. Kotlin/Native would pull its whole toolchain into every
# image build; the binary is a single self-contained file, so the stage would buy nothing. What it
# costs instead is one rule: the file below has to exist before `docker build` runs.

# NO COPIES OF SYSTEM LIBRARIES BELOW, and that is a property of this pair rather than a habit.
# `readelf -d` on the real binary, not a list carried over from another project:
#
#     libm.so.6  libpthread.so.0  librt.so.1  libz.so.1  libdl.so.2  libgcc_s.so.1  libc.so.6
#     ld-linux-x86-64.so.2
#
# Every one of those is in `gcr.io/distroless/cc-debian13`, checked by unpacking the image
# (`docker create` + `docker export | tar -t`) rather than from memory. `libz.so.1` is the one that
# decides the tag: `cc-debian12` has no zlib, and an image on it would need a copy from a donor —
# and a copied system library ties the two images together by glibc, silently at build time and
# loudly at exec.
#
# Three more libraries used to be in that list — `libcrypt.so.1`, `libresolv.so.2`, `libutil.so.1` —
# and `libcrypt` is precisely the one distroless does not carry. They were never called: `readelf
# -Ws` showed no undefined symbol from any of them, which is Kotlin/Native declaring libraries it
# does not use. `-Wl,--as-needed` in the module's build file drops them, and with them the last
# reason this image would have had to copy anything.
#
# `ca-certificates` is not installed either, for a different reason: distroless/cc already carries
# `/etc/ssl/certs/ca-certificates.crt`, which is the path compiled into the OpenSSL that
# `ktor-client-curl` links statically. Without that file every HTTPS request fails before any HTTP
# status exists, with a message that mentions no certificates
# (docs/research/research-architecture.md, risk 2). `verify-image.sh` reaches a real HTTPS endpoint
# from inside this image so that the claim is tested rather than asserted.
FROM gcr.io/distroless/cc-debian13

LABEL org.opencontainers.image.source="https://github.com/youndie/s3kn"
LABEL org.opencontainers.image.description="S3 from a shell script: cp, ls, stat, rm and presign, with no configuration file"
LABEL org.opencontainers.image.licenses="Apache-2.0"

COPY s3-cli/build/bin/linuxX64/releaseExecutable/s3kn.kexe /usr/local/bin/s3kn

# No `CMD`: an image whose entry point is a tool should print its usage when run with no arguments,
# and `s3kn` does — to standard error, with exit code 2.
ENTRYPOINT ["/usr/local/bin/s3kn"]
