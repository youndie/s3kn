package io.github.youndie.s3

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The moment a request is signed at, in the one form SigV4 accepts.
 *
 * The credential scope needs the date and `X-Amz-Date` needs the full timestamp, and the two must
 * agree. Computing the date separately looks harmless and is wrong once a second: a request signed
 * at `20150830T235959Z` whose scope says `20150831` is rejected, and it happens on roughly one
 * request in 86 400. Here the date is a substring rather than a second calculation, so the two
 * cannot disagree.
 *
 * Format: `%Y%m%dT%H%M%SZ`, always UTC (docs/spec/reference/botocore-auth.py:63).
 */
@JvmInline
public value class SigningTimestamp internal constructor(
    /** `20150830T123600Z` — the value of the `X-Amz-Date` header or query parameter. */
    public val amzDate: String,
) {
    /** `20150830` — the first field of the credential scope. */
    public val scopeDate: String get() = amzDate.substring(0, DATE_LENGTH)

    private companion object {
        const val DATE_LENGTH = 8
    }
}

/**
 * Formats this instant for signing. Anything finer than a second is dropped, not rounded: the
 * signature covers whole seconds, so rounding up would name a moment the request was not sent at.
 */
public fun Instant.toSigningTimestamp(): SigningTimestamp {
    val days = epochSeconds.floorDiv(SECONDS_PER_DAY)
    val secondsOfDay = epochSeconds.mod(SECONDS_PER_DAY)
    val (year, month, day) = civilFromDays(days)

    return SigningTimestamp(
        buildString(16) {
            append(year.toString().padStart(4, '0'))
            append(pad2(month))
            append(pad2(day))
            append('T')
            append(pad2((secondsOfDay / SECONDS_PER_HOUR).toInt()))
            append(pad2((secondsOfDay / SECONDS_PER_MINUTE % MINUTES_PER_HOUR).toInt()))
            append(pad2((secondsOfDay % SECONDS_PER_MINUTE).toInt()))
            append('Z')
        },
    )
}

/**
 * Days since 1970-01-01 to a proleptic Gregorian year, month and day.
 *
 * Howard Hinnant's `civil_from_days`, the standard shift-the-era-to-March trick: with the year
 * starting in March the leap day falls at the end, and the day-of-year becomes a straight line in
 * the month number. There is no calendar in the Kotlin standard library and pulling in
 * kotlinx-datetime for one format string is not worth a dependency in a library.
 */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val shifted = days + DAYS_FROM_0000_03_01_TO_EPOCH
    val era = (if (shifted >= 0) shifted else shifted - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
    val dayOfEra = shifted - era * DAYS_PER_ERA
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthFromMarch = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthFromMarch + 2) / 5 + 1
    val month = if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0

    return Triple(year.toInt(), month.toInt(), day.toInt())
}

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

/** Days between 0000-03-01 of the shifted calendar and 1970-01-01. */
private const val DAYS_FROM_0000_03_01_TO_EPOCH = 719_468L

/** A Gregorian era is 400 years, which is exactly this many days. */
private const val DAYS_PER_ERA = 146_097L
