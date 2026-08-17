package io.github.youndie.s3

// The little XML reading this library does.
//
// A hand-rolled reader rather than a library: S3 answers with six flat documents whose elements
// never nest more than two deep, and one of them is read on the failure path, where a parser that
// can itself throw would replace one error with another (docs/research/research-architecture.md,
// decision R8).
//
// The moment that stops being true — versions, tagging, ACLs, batch delete — this is the wrong tool
// and should be replaced rather than extended.

/**
 * Text of the first `<name>…</name>`, entities resolved, or `null` when there is no such element.
 *
 * Only the first: for a document with repeated elements, take [elementBlocks] first and read within
 * each block. `<ListBucketResult>` has a top-level `<Prefix>` *and* one inside every
 * `<CommonPrefixes>`, so reading "the" prefix without splitting first finds whichever came earlier.
 */
internal fun String.elementText(name: String): String? {
    val open = indexOf("<$name>")
    if (open < 0) return null
    val start = open + name.length + 2
    val end = indexOf("</$name>", startIndex = start)
    if (end < 0) return null

    return substring(start, end).unescapeXml()
}

/** Inner text of every `<name>…</name>` in document order. Entities are left alone. */
internal fun String.elementBlocks(name: String): List<String> {
    val open = "<$name>"
    val close = "</$name>"
    val blocks = mutableListOf<String>()
    var index = 0
    while (true) {
        val start = indexOf(open, startIndex = index)
        if (start < 0) return blocks
        val end = indexOf(close, startIndex = start + open.length)
        if (end < 0) return blocks
        blocks += substring(start + open.length, end)
        index = end + close.length
    }
}

/** The document with every `<name>…</name>` removed, so the scalars around them can be read. */
internal fun String.withoutElements(name: String): String {
    val open = "<$name>"
    val close = "</$name>"
    return buildString {
        var index = 0
        while (true) {
            val start = this@withoutElements.indexOf(open, startIndex = index)
            if (start < 0) {
                append(this@withoutElements, index, this@withoutElements.length)
                return@buildString
            }
            append(this@withoutElements, index, start)
            val end = this@withoutElements.indexOf(close, startIndex = start + open.length)
            if (end < 0) return@buildString
            index = end + close.length
        }
    }
}

/**
 * The five predefined XML entities, plus the numeric newline S3 uses inside `<CanonicalRequest>`.
 *
 * The ampersand is resolved last so that `&amp;#10;` — the literal text `&#10;` — does not turn
 * into a newline on the way through.
 */
internal fun String.unescapeXml(): String =
    replace("&#10;", "\n")
        .replace("&#xA;", "\n")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
