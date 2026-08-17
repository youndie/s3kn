package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a `<ListBucketResult>`.
 *
 * Shape: docs/spec/s3-service-2.json, `shapes.ListObjectsV2Output`. Contract:
 * docs/api/protocol-s3.md, section 4.5.
 */
class ListBucketResultTest {
    @Test
    fun `reads the objects of a page`() {
        val page = parseListBucketResult(PAGE)

        assertEquals(listOf("a/one.txt", "a/two.txt"), page.objects.map { it.key })
        assertEquals(listOf(11L, 22L), page.objects.map { it.size })
        assertEquals("\"5eb63bbbe01eeed093cb22bb8f5acdc3\"", page.objects[0].eTag)
        assertEquals("2015-08-30T12:36:00.000Z", page.objects[0].lastModified)
        assertEquals("STANDARD", page.objects[0].storageClass)
    }

    @Test
    fun `decodes the keys because every listing asks for them encoded`() {
        val page =
            parseListBucketResult(
                """
                <ListBucketResult>
                  <EncodingType>url</EncodingType>
                  <Contents><Key>a/my%20dir/%D1%84%D0%B0%D0%B9%D0%BB.txt</Key><Size>1</Size></Contents>
                  <Contents><Key>a/%F0%9F%99%82</Key><Size>2</Size></Contents>
                </ListBucketResult>
                """.trimIndent(),
            )

        assertEquals(listOf("a/my dir/файл.txt", "a/🙂"), page.objects.map { it.key })
    }

    @Test
    fun `does not confuse the top-level prefix with the one inside a rolled-up prefix`() {
        // `<Prefix>` appears twice with different meanings. Reading "the" prefix without cutting
        // the repeated blocks out first finds whichever the document happens to put earlier.
        val page = parseListBucketResult(PAGE)

        assertEquals(listOf("a/nested/", "a/other/"), page.commonPrefixes)
    }

    @Test
    fun `keeps rolled-up prefixes apart from objects`() {
        // They are not objects. A caller that mixed them in would go and ask for a key that has
        // never existed.
        val page = parseListBucketResult(PAGE)

        assertEquals(2, page.objects.size)
        assertEquals(2, page.commonPrefixes.size)
        assertTrue(page.objects.none { it.key in page.commonPrefixes })
    }

    @Test
    fun `reads the key count of this page rather than of the bucket`() {
        val page = parseListBucketResult(PAGE)

        assertEquals(2, page.keyCount)
    }

    @Test
    fun `carries the continuation token when there is more to come`() {
        val page = parseListBucketResult(PAGE)

        assertTrue(page.isTruncated)
        assertEquals("1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J", page.nextContinuationToken)
    }

    @Test
    fun `leaves the continuation token undecoded`() {
        // The reference implementation's comment lists ContinuationToken among the encoded
        // elements and its code does not decode it (botocore/handlers.py, decode_list_object_v2).
        // The code is right: the token is opaque and goes back verbatim, so decoding it would send
        // S3 something it never issued.
        val page =
            parseListBucketResult(
                "<ListBucketResult><IsTruncated>true</IsTruncated>" +
                    "<NextContinuationToken>abc%2Fdef</NextContinuationToken></ListBucketResult>",
            )

        assertEquals("abc%2Fdef", page.nextContinuationToken)
    }

    @Test
    fun `reads an empty listing`() {
        val page =
            parseListBucketResult(
                "<ListBucketResult><Name>photos</Name><KeyCount>0</KeyCount>" +
                    "<IsTruncated>false</IsTruncated></ListBucketResult>",
            )

        assertEquals(emptyList(), page.objects)
        assertEquals(emptyList(), page.commonPrefixes)
        assertEquals(0, page.keyCount)
        assertTrue(!page.isTruncated)
        assertNull(page.nextContinuationToken)
    }

    @Test
    fun `treats a missing truncation flag as the end of the listing`() {
        // Erring the other way would loop forever on a server that leaves the element out.
        val page = parseListBucketResult("<ListBucketResult><KeyCount>0</KeyCount></ListBucketResult>")

        assertTrue(!page.isTruncated)
    }

    private companion object {
        val PAGE =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult>
              <Name>photos</Name>
              <Prefix>a/</Prefix>
              <Delimiter>/</Delimiter>
              <KeyCount>2</KeyCount>
              <MaxKeys>1000</MaxKeys>
              <IsTruncated>true</IsTruncated>
              <EncodingType>url</EncodingType>
              <NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J</NextContinuationToken>
              <Contents>
                <Key>a/one.txt</Key>
                <LastModified>2015-08-30T12:36:00.000Z</LastModified>
                <ETag>"5eb63bbbe01eeed093cb22bb8f5acdc3"</ETag>
                <Size>11</Size>
                <StorageClass>STANDARD</StorageClass>
              </Contents>
              <Contents>
                <Key>a/two.txt</Key>
                <LastModified>2015-08-30T12:37:00.000Z</LastModified>
                <ETag>"9a0364b9e99bb480dd25e1f0284c8555"</ETag>
                <Size>22</Size>
                <StorageClass>STANDARD</StorageClass>
              </Contents>
              <CommonPrefixes><Prefix>a/nested/</Prefix></CommonPrefixes>
              <CommonPrefixes><Prefix>a/other/</Prefix></CommonPrefixes>
            </ListBucketResult>
            """.trimIndent()
    }
}
