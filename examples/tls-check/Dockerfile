# The runtime image for a Kotlin/Native binary that talks to S3 over HTTPS.
#
# Build the binary first, then this image:
#
#     ./gradlew :examples:tls-check:linkReleaseExecutableLinuxX64
#     docker build -f examples/tls-check/Dockerfile -t s3kn-tls-check .
#     docker run --rm s3kn-tls-check
#
# `verify.sh` next to this file builds it both ways and shows the difference.
FROM debian:bookworm-slim

# The one thing that has to be installed.
#
# TLS itself is already inside the binary: ktor-client-curl ships libcurl and OpenSSL as static
# libraries inside its cinterop klib, so there is no libssl to install and no version to match.
# The root certificates are not in there — libcurl reads them from
# /etc/ssl/certs/ca-certificates.crt at runtime — and a slim image has none. Without them every
# HTTPS request fails before any HTTP status exists, with a message that says nothing about
# certificates (docs/research/research-architecture.md, risk 2).
#
# Set WITH_CA_CERTIFICATES=false to build the image that demonstrates exactly that.
ARG WITH_CA_CERTIFICATES=true
RUN if [ "$WITH_CA_CERTIFICATES" = "true" ]; then \
        apt-get update && \
        apt-get install -y --no-install-recommends ca-certificates && \
        rm -rf /var/lib/apt/lists/*; \
    fi

COPY examples/tls-check/build/bin/linuxX64/releaseExecutable/tls-check.kexe /usr/local/bin/tls-check

ENTRYPOINT ["/usr/local/bin/tls-check"]
