package io.github.youndie.s3

/**
 * Builds the canonical query string — the third line of the canonical request, and the same string
 * that goes on the wire after `?`.
 *
 * Names and values are percent-encoded, then the pairs are sorted by encoded name and, when names
 * repeat, by encoded value (docs/spec/reference/botocore-auth.py:268).
 *
 * A parameter with no value renders as `name=`. That is how `?uploads` and `?acl` appear in the
 * canonical request, and the same rendering is sent, so the signed form and the sent form cannot
 * drift apart.
 *
 * Order is preserved as a list rather than a map on purpose: S3 accepts repeated parameter names,
 * and a map would silently drop one of them.
 */
public fun canonicalQueryString(parameters: List<Pair<String, String>>): String =
    parameters
        .map { (name, value) -> uriEncodeQueryComponent(name) to uriEncodeQueryComponent(value) }
        // Comparing Kotlin strings is comparing UTF-16 code units, and after encoding every
        // character is ASCII, so this is the byte order the specification asks for.
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (name, value) -> "$name=$value" }
