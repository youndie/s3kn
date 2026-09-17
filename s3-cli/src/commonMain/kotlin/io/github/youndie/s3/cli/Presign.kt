package io.github.youndie.s3.cli

import io.github.youndie.s3.S3Config
import io.github.youndie.s3.sigv4.S3Signer

/**
 * Builds the URL `presign` prints.
 *
 * In `commonMain`, and not next to the other commands, because it is the one command that sends
 * nothing: signing is a pure function of the configuration, the operation and the clock. That is
 * also why it can be tested without a server, an engine, or a network — the same property that
 * lets the library presign on targets that have no HTTP engine at all.
 */
internal fun presignUrl(
    config: S3Config,
    command: Command.Presign,
): String =
    S3Signer(config).presign(
        method = command.method,
        bucket = command.bucket,
        key = command.key,
        expires = command.expires,
    )
