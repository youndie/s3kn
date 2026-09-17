#!/bin/sh
# Builds both images and drives them the way a backup job would.
#
#     ./s3-cli/verify-image.sh
#
# Run from anywhere; needs Docker, a Linux host, and the MinIO from docker-compose.yml:
#
#     docker compose up -d --wait minio
#     docker compose run --rm create-buckets
#
# This exists because a built image is not a working one. The library's suite never opens a file,
# never reads the environment and never runs inside a container without a shell — every failure this
# script can catch lives in exactly that gap: a missing shared library, missing root certificates, a
# mount that is not writable, an entry point that is not there.
#
# TWO IMAGES, AND THE SECOND ONE IS CHECKED DIFFERENTLY. The default image is driven through its
# entry point, one command per `docker run`, which is how a chart that spells `args:` uses it. The
# `-shell` image is driven through `sh -c` with a script that finds the file, checks it, uploads it
# and asks the storage whether it arrived — because that is the whole reason that image exists, and
# a check that only ran `--version` in it would pass on an image whose shell is missing the moment
# a real job needs one.
#
# Set S3KN_IMAGE_TLS_CHECK=1 to also reach real AWS over HTTPS from inside the image. Off by
# default: it depends on a service nobody here controls, and a pull request should not go red
# because AWS had a bad minute.
set -eu

cd "$(dirname "$0")/.."

ENDPOINT=${S3_E2E_ENDPOINT:-http://127.0.0.1:9000}
ACCESS_KEY=${S3_E2E_ACCESS_KEY:-s3kn-test-access-key}
SECRET_KEY=${S3_E2E_SECRET_KEY:-s3kn-test-secret-key}
BUCKET=${S3_E2E_BUCKET:-s3kn-test}
IMAGE=${S3KN_IMAGE:-s3kn:dev}
# The tag always ends the reference, so the suffix lands where it belongs whether the image is
# `s3kn:dev` or `ghcr.io/youndie/s3kn:sha-abc1234`.
SHELL_IMAGE="${IMAGE}-shell"
SHELL_BASE=gcr.io/distroless/cc-debian13:debug
KEY="image-check/$(date +%s).bin"

work=$(mktemp -d)
cleanup() {
    rm -rf "$work"
}
trap cleanup EXIT

echo "==> building the binary"
./gradlew --quiet :s3-cli:linkReleaseExecutableLinuxX64

echo "==> building $IMAGE"
docker build --quiet -f s3-cli/Dockerfile -t "$IMAGE" . >/dev/null

echo "==> building $SHELL_IMAGE"
docker build --quiet -f s3-cli/Dockerfile --build-arg "BASE=$SHELL_BASE" -t "$SHELL_IMAGE" . >/dev/null

# `--network host` so that 127.0.0.1 means the same thing inside the container as it does in
# docker-compose.yml. Linux only, which is where this runs.
environment() {
    echo "--rm --network host -e S3_ENDPOINT=$ENDPOINT -e AWS_ACCESS_KEY_ID=$ACCESS_KEY"
    echo "-e AWS_SECRET_ACCESS_KEY=$SECRET_KEY -e BUCKET=$BUCKET -v $work:/work"
}

# The default image, through its entry point: one command per `docker run`, the way a chart that
# spells `args:` uses it.
s3kn() {
    # shellcheck disable=SC2046 # word splitting is the point: these are separate docker arguments
    docker run $(environment) "$IMAGE" "$@"
}

# The `-shell` image, through a script, the way a backup job uses it.
shell_job() {
    # shellcheck disable=SC2046 # as above
    docker run $(environment) --entrypoint sh "$SHELL_IMAGE" -c "$1"
}

echo "==> the entry point answers"
s3kn --version

echo "==> no arguments is a usage failure"
# Not `|| true`: the exit code IS the assertion, and a pipeline that swallowed it would make this
# check pass for an image that does not start at all.
if s3kn >/dev/null 2>&1; then
    echo "FAILED: running with no arguments should have exited 2" >&2
    exit 1
fi

echo "==> a round trip through the storage"
head -c 3000000 /dev/urandom >"$work/source.bin"
before=$(md5sum <"$work/source.bin" | cut -d' ' -f1)

s3kn cp /work/source.bin "s3://$BUCKET/$KEY"
s3kn stat "s3://$BUCKET/$KEY"
s3kn ls -r "s3://$BUCKET/image-check/"
s3kn cp "s3://$BUCKET/$KEY" /work/target.bin

after=$(md5sum <"$work/target.bin" | cut -d' ' -f1)
if [ "$before" != "$after" ]; then
    echo "FAILED: what came back is not what went up ($before vs $after)" >&2
    exit 1
fi
echo "    $after, byte for byte"

echo "==> a missing object is a failure and not a usage error"
if s3kn stat "s3://$BUCKET/image-check/never-existed" >/dev/null 2>&1; then
    echo "FAILED: stat of a missing object should have failed" >&2
    exit 1
fi

echo "==> and it can be removed"
s3kn rm "s3://$BUCKET/$KEY"

echo "==> the -shell image runs a backup job's script"
# Deliberately the shape a chart uses: `sh` resolved through PATH rather than spelled as
# /busybox/sh, a dump found rather than named, a check that it is not empty, and a `stat` afterwards
# — because `cp` returning zero is not yet delivery. If busybox ever moves or leaves PATH, this is
# what says so.
mkdir -p "$work/dump"
head -c 1000000 /dev/urandom >"$work/dump/$(date +%s).dump"
shell_job '
    set -eu
    FILE=$(ls -1 /work/dump | head -n 1)
    test -n "$FILE"
    test -s "/work/dump/$FILE"
    echo "uploading $FILE"
    s3kn cp "/work/dump/$FILE" "s3://$BUCKET/image-check/shell/$FILE"
    s3kn stat "s3://$BUCKET/image-check/shell/$FILE" >/dev/null
    s3kn rm "s3://$BUCKET/image-check/shell/$FILE"
    echo "    the shell image does what a job asks of it"
'

if [ "${S3KN_IMAGE_TLS_CHECK:-0}" = "1" ]; then
    echo "==> HTTPS to a real endpoint, from inside the image"
    # Deliberately wrong credentials: any HTTP status at all proves the name resolved, the
    # handshake finished and the certificate verified. Without root certificates there is no status
    # to print, and the message says nothing about certificates
    # (docs/research/research-architecture.md, risk 2). Dropping libresolv.so.2 from the binary is
    # the other thing this proves harmless — the name has to resolve for any of it to happen.
    #
    # Both images, because the two bases are not the same image and the certificates are the base's.
    for image in "$IMAGE" "$SHELL_IMAGE"; do
        output="$work/tls.txt"
        docker run --rm \
            -e S3_ENDPOINT="https://s3.us-east-1.amazonaws.com" \
            -e AWS_ACCESS_KEY_ID="AKIDEXAMPLE" \
            -e AWS_SECRET_ACCESS_KEY="not-a-real-secret-key" \
            -e S3_ADDRESSING="virtual" \
            "$image" stat "s3://s3kn-image-check-no-such-bucket/probe" >"$output" 2>&1 || true

        if grep -q "S3 request failed with" "$output"; then
            echo "    $image answered: $(cat "$output")"
        else
            echo "FAILED: $image got no HTTP status back, so the handshake never finished:" >&2
            cat "$output" >&2
            exit 1
        fi
    done
fi

echo "==> both images do what a backup job needs"
