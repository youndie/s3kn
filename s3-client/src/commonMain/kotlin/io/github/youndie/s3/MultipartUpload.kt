package io.github.youndie.s3

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * A multipart upload that has been started and not yet finished.
 *
 * Until it is completed or aborted the parts already uploaded occupy storage and are billed, which
 * is why every path that can fail ends in one or the other.
 */
public class S3MultipartUpload internal constructor(
    public val bucket: String,
    public val key: String,
    /** Opaque identifier S3 issued; every later request carries it. */
    public val uploadId: String,
)

/** A part that has been stored, and the `ETag` that proves which bytes it holds. */
public class S3CompletedPart(
    public val partNumber: Int,
    /** Quoted, exactly as the `ETag` header carried it. Completing with a changed value fails. */
    public val eTag: String,
)

/** Limits S3 puts on a multipart upload (`docs/spec/s3-service-2.json:1604`). */
public object S3MultipartLimits {
    public const val MIN_PART_NUMBER: Int = 1
    public const val MAX_PART_NUMBER: Int = 10_000

    /**
     * Smallest size for every part but the last, in bytes.
     *
     * The one limit the API model does not state — it points at the user guide instead — so this
     * is checked against a live server rather than quoted (`S3MultipartE2eTest`).
     */
    public const val MIN_PART_SIZE: Long = 5L * 1024 * 1024
}

internal fun requirePartNumber(partNumber: Int) {
    require(partNumber in S3MultipartLimits.MIN_PART_NUMBER..S3MultipartLimits.MAX_PART_NUMBER) {
        "Part number must be between ${S3MultipartLimits.MIN_PART_NUMBER} and " +
            "${S3MultipartLimits.MAX_PART_NUMBER}, got $partNumber"
    }
}

/** Reads `<InitiateMultipartUploadResult>`. */
internal fun parseUploadId(body: String): String =
    body.elementText("UploadId")
        ?: throw S3Exception(
            status = 200,
            errorMessage = "The response to CreateMultipartUpload carried no <UploadId>: $body",
        )

/**
 * The body of `CompleteMultipartUpload`.
 *
 * Parts have to be listed in ascending order or S3 answers `InvalidPartOrder`
 * (`docs/spec/s3-service-2.json:32`), so they are sorted here rather than trusted to arrive sorted.
 */
internal fun completeMultipartUploadBody(parts: List<S3CompletedPart>): String =
    buildString {
        append("<CompleteMultipartUpload>")
        parts.sortedBy { it.partNumber }.forEach { part ->
            append("<Part><PartNumber>")
            append(part.partNumber)
            append("</PartNumber><ETag>")
            append(part.eTag.escapeXml())
            append("</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }

/**
 * The outcome of `CompleteMultipartUpload`, which cannot be read from the status code.
 *
 * S3 answers `200 OK` and only then finds out whether it worked, so the body may hold either a
 * result or an error: "a 200 OK response can contain either a success or an error"
 * (`docs/spec/s3-service-2.json:32`). Anything that decides by status alone reports a failed upload
 * as a successful one.
 */
internal fun parseCompleteMultipartUpload(body: String): String? {
    if ("<Error" in body) {
        val error = parseErrorBody(body)
        throw S3Exception(
            // The status really was 200. Reporting it as such is the honest thing: the caller
            // needs to know that the transport succeeded and the operation did not.
            status = 200,
            code = error?.code,
            errorMessage = error?.message,
            requestId = error?.requestId,
            extendedRequestId = error?.hostId,
        )
    }
    return body.elementText("ETag")
}

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Reads exactly [size] bytes, or fewer when the channel ends first.
 *
 * `readAvailable` returns whatever has arrived, which for a network stream is rarely a whole part;
 * a part built from it would be short, and a short part that is not the last one is rejected as
 * `EntityTooSmall` — after the whole upload, not during it.
 */
internal suspend fun ByteReadChannel.readPart(size: Long): ByteArray {
    val buffer = ByteArray(size.toInt())
    var filled = 0
    while (filled < buffer.size) {
        val read = readAvailable(buffer, filled, buffer.size - filled)
        if (read <= 0) break
        filled += read
    }
    return if (filled == buffer.size) buffer else buffer.copyOf(filled)
}
