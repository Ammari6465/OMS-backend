package com.sunrich.oms.workplace.detection

import java.io.ByteArrayInputStream
import java.io.FilterReader
import java.io.InputStreamReader
import java.io.Reader
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
 * Opens plan bytes as XML character input.
 *
 * Two failure modes are handled here, both hit by the same production file:
 *
 * Encoding — real exports lie. CAD and Office tools emit an `encoding="UTF-8"`
 * declaration over Windows-1252 bytes, or prepend a BOM. A byte-level parse
 * then dies on the first accented character ("Invalid byte 1 of 1-byte UTF-8
 * sequence") and the scan reports an empty floor.
 *
 * Size — plans run to the 10MB upload limit, and a 10MB SVG does not cost 10MB
 * to read. Decoding it to one String, running regexes that each copy it, then
 * building a DOM multiplies it by an order of magnitude and gets the container
 * OOM-killed mid-request. So nothing here materialises the document: bytes are
 * decoded as a stream and the caller pulls events off it.
 */
object SvgSource {

    /** Cheap sniff over the head of the file — no decode of a large plan. */
    fun looksLikeSvg(bytes: ByteArray): Boolean =
        head(bytes).contains("<svg", ignoreCase = true)

    /**
     * A streaming reader over the plan, decoded leniently. Undecodable bytes
     * become U+FFFD and characters XML 1.0 forbids are dropped, so a parser
     * sees well-formed input instead of aborting on one bad byte.
     *
     * The XML declaration is left in place: a parser reading from a Reader
     * ignores the declared encoding, which is exactly what is wanted here.
     */
    fun reader(bytes: ByteArray): Reader {
        val (charset, skip) = charsetOf(bytes)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val stream = ByteArrayInputStream(bytes, skip, bytes.size - skip)
        return XmlSafeReader(InputStreamReader(stream, decoder))
    }

    /** Reads the head of the file through ISO-8859-1, where every byte maps. */
    private fun head(bytes: ByteArray): String =
        String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), StandardCharsets.ISO_8859_1)

    /**
     * Picks a charset from the BOM, then the XML declaration, then UTF-8, with
     * the number of BOM bytes to skip past.
     */
    private fun charsetOf(bytes: ByteArray): Pair<Charset, Int> {
        bom(bytes)?.let { return it }
        val declared = DECLARED_ENCODING.find(head(bytes))?.groupValues?.get(1)?.trim()
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
        return charset to 0
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            StandardCharsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            StandardCharsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            StandardCharsets.UTF_16BE to 2
        else -> null
    }

    /**
     * Drops the control characters XML 1.0 rejects, in place and without
     * buffering. One stray 0x00 left behind by a drawing tool would otherwise
     * fail the whole document.
     */
    private class XmlSafeReader(source: Reader) : FilterReader(source) {
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read <= 0) return read
            var out = offset
            for (i in offset until offset + read) {
                val c = buffer[i]
                if (legal(c)) buffer[out++] = c
            }
            val kept = out - offset
            // Every character in this chunk was illegal. Read on rather than
            // returning 0, which the caller would take as end of input.
            return if (kept == 0) read(buffer, offset, length) else kept
        }

        override fun read(): Int {
            while (true) {
                val c = super.read()
                if (c == -1 || legal(c.toChar())) return c
            }
        }

        private fun legal(c: Char) = when {
            c == '\t' || c == '\n' || c == '\r' -> true
            c < ' ' -> false
            c.code == 0xFFFE || c.code == 0xFFFF -> false
            else -> true
        }
    }

    private const val SNIFF_BYTES = 4096
    private val DECLARED_ENCODING =
        Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
}
