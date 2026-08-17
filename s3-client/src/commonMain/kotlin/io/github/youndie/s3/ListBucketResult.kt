package io.github.youndie.s3

/** One object as a listing describes it. The body is not fetched; only what `<Contents>` carries. */
public class S3ObjectSummary(
    /** Decoded: it arrives percent-encoded, because every listing asks for `encoding-type=url`. */
    public val key: String,
    public val size: Long,
    /** Quoted, as it arrives. */
    public val eTag: String?,
    /** ISO-8601 as S3 wrote it, not reinterpreted here. */
    public val lastModified: String?,
    public val storageClass: String?,
)

/**
 * One page of a listing.
 *
 * A page, not a whole bucket: S3 answers at most `MaxKeys` keys at a time and says whether more
 * exist. [S3Client.list] walks the pages; [S3Client.listPage] hands one over.
 */
public class S3ListPage(
    public val objects: List<S3ObjectSummary>,
    /**
     * The rolled-up prefixes a `delimiter` produced — the closest thing S3 has to directories.
     *
     * A separate branch of the result rather than more entries in [objects]: these are not objects,
     * and a caller that treats them as such asks for a key that does not exist.
     */
    public val commonPrefixes: List<String>,
    /**
     * Number of keys **on this page**, not in the bucket. S3 has no cheap way to answer the latter,
     * and reading this as a total is the mistake the name invites
     * (docs/spec/s3-service-2.json, `shapes.ListObjectsV2Output.members.KeyCount`).
     */
    public val keyCount: Int,
    public val isTruncated: Boolean,
    /**
     * Token that fetches the next page, present exactly when [isTruncated] is true.
     *
     * Sent back untouched. It is opaque, and unlike the keys it is *not* URL-decoded on the way in
     * — see the note in `parseListBucketResult`.
     */
    public val nextContinuationToken: String?,
)

/**
 * Reads a `<ListBucketResult>`.
 *
 * Two things about it are easy to get wrong and are the reason this is not three lines.
 *
 * `<Prefix>` appears twice in the document with different meanings: once at the top level, echoing
 * the request, and once inside every `<CommonPrefixes>`. The repeated blocks are therefore cut out
 * before the scalars are read.
 *
 * The decoding follows the reference implementation's *code*, not its comment. `botocore/handlers.py`,
 * `decode_list_object_v2`, says in prose that `ContinuationToken` is among the encoded elements and
 * then does not decode it — and that is the right call: the token is opaque and travels back
 * verbatim, so decoding it would send S3 something it never issued.
 */
private fun decodeListValue(value: String): String = uriDecode(value, plusIsSpace = true)

internal fun parseListBucketResult(body: String): S3ListPage {
    val contents = body.elementBlocks("Contents")
    val prefixBlocks = body.elementBlocks("CommonPrefixes")
    val scalars = body.withoutElements("Contents").withoutElements("CommonPrefixes")

    return S3ListPage(
        objects =
            contents.map { block ->
                S3ObjectSummary(
                    key = decodeListValue(block.elementText("Key").orEmpty()),
                    size = block.elementText("Size")?.toLongOrNull() ?: 0L,
                    eTag = block.elementText("ETag"),
                    lastModified = block.elementText("LastModified"),
                    storageClass = block.elementText("StorageClass"),
                )
            },
        commonPrefixes = prefixBlocks.mapNotNull { block -> block.elementText("Prefix")?.let(::decodeListValue) },
        keyCount = scalars.elementText("KeyCount")?.toIntOrNull() ?: contents.size,
        isTruncated = scalars.elementText("IsTruncated") == "true",
        nextContinuationToken = scalars.elementText("NextContinuationToken"),
    )
}
