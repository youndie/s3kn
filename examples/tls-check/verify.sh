#!/bin/sh
# Checks that a native image can reach S3 over HTTPS, and that it cannot without root certificates.
#
# The second half is the point. "It works on my machine" proves nothing here: a developer's machine
# has certificates and a slim container does not, so the failure only ever shows up in production.
# Running both images side by side turns that into something a test can assert.
#
#     ./examples/tls-check/verify.sh
#
# Run from the repository root. Needs Docker and a Linux host or cross-compilation.
set -eu

cd "$(dirname "$0")/../.."

echo "==> building the binary"
./gradlew --quiet :examples:tls-check:linkReleaseExecutableLinuxX64

echo "==> building both images"
docker build --quiet -f examples/tls-check/Dockerfile -t s3kn-tls-check:with-ca .
docker build --quiet -f examples/tls-check/Dockerfile \
    --build-arg WITH_CA_CERTIFICATES=false -t s3kn-tls-check:without-ca .

echo "==> with ca-certificates (expected: TLS OK)"
if ! docker run --rm s3kn-tls-check:with-ca; then
    echo "FAILED: the image with ca-certificates could not reach S3 over HTTPS" >&2
    exit 1
fi

echo "==> without ca-certificates (expected: failure)"
if docker run --rm s3kn-tls-check:without-ca; then
    echo "FAILED: the image without ca-certificates reached S3 anyway." >&2
    echo "Either the base image gained certificates, or the binary stopped verifying them." >&2
    exit 1
fi

echo "==> both outcomes are as expected"
