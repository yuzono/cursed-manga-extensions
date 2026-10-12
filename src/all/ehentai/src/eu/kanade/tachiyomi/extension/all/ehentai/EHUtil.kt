package eu.kanade.tachiyomi.extension.all.ehentai

import kotlin.math.ln
import kotlin.math.pow

// Various utility methods used in the E-Hentai source

/**
 * Return null if String is blank, otherwise returns the original String
 * @returns null if the String is blank, otherwise returns the original String
 */
fun String?.nullIfBlank(): String? = if (isNullOrBlank()) {
    null
} else {
    this
}

/**
 * Use '+' to append Strings onto a StringBuilder
 */
operator fun StringBuilder.plusAssign(other: String) {
    append(other)
}

/**
 * Converts bytes into a human readable String
 */
fun humanReadableByteCount(bytes: Long, si: Boolean): String {
    val unit = if (si) 1000 else 1024
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
    return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
}

private const val KB_FACTOR = 1000
private const val KIB_FACTOR = 1024
private const val MB_FACTOR = 1000 * KB_FACTOR
private const val MIB_FACTOR = 1024 * KIB_FACTOR
private const val GB_FACTOR = 1000 * MB_FACTOR
private const val GIB_FACTOR = 1024 * MIB_FACTOR

/**
 * Parse human readable size Strings
 */
fun parseHumanReadableByteCount(value: String): Double? {
    val amount = value.substringBefore(' ').toDoubleOrNull() ?: return null
    val factor = when (value.substringAfter(' ')) {
        "GB" -> GB_FACTOR
        "GiB" -> GIB_FACTOR
        "MB" -> MB_FACTOR
        "MiB" -> MIB_FACTOR
        "KB" -> KB_FACTOR
        "KiB" -> KIB_FACTOR
        "B" -> 1
        else -> return null
    }
    return amount * factor
}
