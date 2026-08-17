package io.github.youndie.s3.sigv4

import io.github.youndie.s3.uriEncodeKey

/**
 * How the second line of the canonical request is produced from the request path.
 *
 * The only place the generic algorithm and S3 disagree, and the disagreement is silent: both
 * modes produce a well-formed signature, and the wrong one is rejected with
 * `SignatureDoesNotMatch` and no further detail.
 */
public enum class PathMode {
    /**
     * The generic SigV4: remove dot segments and repeated slashes, then percent-encode
     * (docs/spec/reference/botocore-auth.py:385). What every AWS service except S3 expects.
     */
    NORMALIZED,

    /**
     * The path exactly as it will appear on the wire — no normalisation, no second encoding
     * (docs/spec/reference/botocore-auth.py:538). What S3 expects, and the reason the caller has
     * to hand over a path that is already encoded.
     */
    VERBATIM,
}

internal fun canonicalPath(
    path: String,
    mode: PathMode,
): String =
    when (mode) {
        PathMode.VERBATIM -> path.ifEmpty { "/" }
        PathMode.NORMALIZED -> uriEncodeKey(removeDotSegments(path))
    }

/**
 * RFC 3986 section 5.2.4, plus the extra rule AWS adds: consecutive slashes collapse too.
 *
 * Ported from botocore's `remove_dot_segments`, and the collapsing is why `//` becomes `/` rather
 * than staying as it is — that is not in RFC 3986.
 */
private fun removeDotSegments(path: String): String {
    if (path.isEmpty()) return "/"

    val segments = mutableListOf<String>()
    for (segment in path.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> segments.removeLastOrNull()
            else -> segments += segment
        }
    }

    val leading = if (path.first() == '/') "/" else ""
    val trailing = if (path.last() == '/' && segments.isNotEmpty()) "/" else ""
    return leading + segments.joinToString("/") + trailing
}
