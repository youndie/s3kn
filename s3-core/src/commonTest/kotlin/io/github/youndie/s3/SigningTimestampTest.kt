package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The timestamp that appears twice in every signature: in full as `X-Amz-Date`, and truncated to
 * eight characters inside the credential scope.
 *
 * Format `%Y%m%dT%H%M%SZ`, always UTC: docs/spec/reference/botocore-auth.py:63. The scope date is
 * the first eight characters of that same string, not a separately computed date
 * (docs/spec/reference/botocore-auth.py:389).
 */
class SigningTimestampTest {
    @Test
    fun `formats the instant the official vectors are signed at`() {
        // Every case in docs/spec/aws-sig-v4-test-suite is signed at this moment.
        val timestamp = signingTimestampAt(1440938160)

        assertEquals("20150830T123600Z", timestamp.amzDate)
        assertEquals("20150830", timestamp.scopeDate)
    }

    @Test
    fun `pads every field to its width`() {
        assertEquals("20060102T030405Z", signingTimestampAt(1136171045).amzDate)
    }

    @Test
    fun `formats the epoch itself`() {
        assertEquals("19700101T000000Z", signingTimestampAt(0).amzDate)
    }

    @Test
    fun `formats an instant before the epoch`() {
        // SigV4 will never sign 1969, but a `/` and `%` where `floorDiv` and `mod` belong break
        // exactly here and nowhere else.
        assertEquals("19691231T235959Z", signingTimestampAt(-1).amzDate)
    }

    @Test
    fun `formats the extra day of a leap year`() {
        assertEquals("20160229T121314Z", signingTimestampAt(1456747994).amzDate)
    }

    @Test
    fun `moves the scope date together with the timestamp across midnight`() {
        val lastSecond = signingTimestampAt(1440979199)
        val firstSecond = signingTimestampAt(1440979200)

        assertEquals("20150830T235959Z", lastSecond.amzDate)
        assertEquals("20150830", lastSecond.scopeDate)
        assertEquals("20150831T000000Z", firstSecond.amzDate)
        assertEquals("20150831", firstSecond.scopeDate)
    }

    @Test
    fun `truncates sub-second precision instead of rounding it up`() {
        val instant = Instant.fromEpochSeconds(1440938160, nanosecondAdjustment = 999_999_999)

        assertEquals("20150830T123600Z", instant.toSigningTimestamp().amzDate)
    }

    private fun signingTimestampAt(epochSeconds: Long): SigningTimestamp =
        Instant.fromEpochSeconds(epochSeconds).toSigningTimestamp()
}
