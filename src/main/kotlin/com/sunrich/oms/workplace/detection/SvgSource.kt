package com.sunrich.oms.workplace.detection

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Signals that a plan looked readable but could not actually be parsed.
 *
 * Distinct from an empty result on purpose: "your SVG is malformed" and "your
 * plan contains nothing we recognise" call for completely different actions
 * from the user, and collapsing the first into the second sends them off to
 * redraw a whole floor by hand over a broken export.
 */
class UnreadablePlanException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Turns plan bytes into XML text a parser will accept.
 *
 * Real-world SVG exports lie about their encoding: CAD and Office tools emit a
 * `encoding="UTF-8"` declaration over Windows-1252 bytes, or prepend a BOM. The
 * byte-level XML parser then dies on the first accented character with
 * "Invalid byte 1 of 1-byte UTF-8 sequence" and the whole scan reports nothing.
 *
 * Decoding here rather than in the parser removes the failure mode entirely:
 * the bytes are decoded leniently, the declared encoding is dropped (it no
 * longer applies once the content is a String), and control characters XML 1.0
 * forbids are stripped.
 */
object SvgSource {

    /** Cheap sniff over the head of the file — no full decode of a large plan. */
    fun looksLikeSvg(bytes: ByteArray): Boolean =
        decode(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES))).contains("<svg", ignoreCase = true)

    /**
     * Decodes to XML text, never throwing on bad bytes. Undecodable bytes
     * become U+FFFD, which the parser tolerates, instead of aborting the scan.
     */
    fun toXml(bytes: ByteArray): String {
        val text = decode(bytes)
        // The declaration's encoding describes the *bytes*, which no longer
        // exist by the time the parser sees a String. Left in place, a parser
        // reading from a Reader either ignores it or objects; dropping it is
        // unambiguous.
        val cleaned = ILLEGAL_XML_CHARS.replace(XML_DECLARATION.replace(text, ""), "").trim()
        // Some exporters emit stray bytes ahead of the root element. Anything
        // before the first tag is not markup, so drop it.
        val firstTag = cleaned.indexOf('<')
        return if (firstTag > 0) cleaned.substring(firstTag) else cleaned
    }

    /**
     * Picks a charset from the BOM, then the XML declaration, then falls back
     * to UTF-8. Decoding always replaces rather than reports, so any byte
     * sequence yields text.
     */
    private fun decode(bytes: ByteArray): String {
        val bom = bomCharset(bytes)
        if (bom != null) return decodeWith(bytes, bom.first, bom.second)
        // Read the declaration through ISO-8859-1: every byte maps to a
        // character, so the declaration is always legible even when the body is
        // in some other encoding.
        val header = String(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)), StandardCharsets.ISO_8859_1)
        val declared = DECLARED_ENCODING.find(header)?.groupValues?.get(1)?.trim()
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
        return decodeWith(bytes, charset, 0)
    }

    private fun decodeWith(bytes: ByteArray, charset: Charset, skip: Int): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = java.nio.ByteBuffer.wrap(bytes, skip, bytes.size - skip)
        return decoder.decode(buffer).toString()
    }

    /** Returns the BOM's charset and the number of bytes to skip past it. */
    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            StandardCharsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            StandardCharsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            StandardCharsets.UTF_16BE to 2
        else -> null
    }

    private const val SNIFF_BYTES = 4096
    private val DECLARED_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val XML_DECLARATION = Regex("""^\s*<\?xml.*?\?>""", RegexOption.DOT_MATCHES_ALL)
    /** Control characters XML 1.0 forbids outright, plus the replacement char. */
    private val ILLEGAL_XML_CHARS = Regex("[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFE\uFFFF]")
}
