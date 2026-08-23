package com.attendo.core.data

/**
 * Code-to-name lookup for the two acronym keys bundled with the timetable — subjects
 * and faculty. The printed grid only ever gives a code, so this is what lets a screen
 * say "Analog and Digital Electronic Circuits" under "ADEC".
 *
 * ```
 * # code,name
 * ADEC,Analog and Digital Electronic Circuits
 * DE-1,Digital Electronics-I
 * ```
 *
 * Lookup tries the code as written first, then the part before its last hyphen. The
 * grid splits a shared elective into groups — `NN-A`/`NN-B`, `DIP-A`/`DIP-B` — while the
 * acronym page lists only `NN` and `DIP`; trying the exact code first is what keeps
 * genuinely hyphenated codes like `DE-1`, `EM-I` and `EVS-2` resolving to themselves.
 *
 * A missing code is never an error: [nameOf] hands back the code, so an out-of-date key
 * costs you a long name, not a blank screen.
 */
data class Glossary(val entries: Map<String, String>) {

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The full name for a code, or null if neither it nor its base code is listed. */
    operator fun get(code: String?): String? {
        val key = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        entries[key]?.let { return it }
        return key.substringBeforeLast('-', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && it != key }
            ?.let { entries[it] }
    }

    /** The full name, falling back to the code itself. */
    fun nameOf(code: String?): String = this[code] ?: code?.trim().orEmpty()

    /**
     * Resolves a faculty cell, which may name two teachers: `[AKT / SG]`, `[AKT/SG]`.
     * Codes are returned resolved and in the order printed.
     */
    fun namesOf(codes: String?): List<String> = splitCodes(codes).map { nameOf(it) }

    /** "Prof. A.K. Tandon & Dr. Shubham Gupta" — the shared-teaching cells. */
    fun label(codes: String?): String = namesOf(codes).joinToString(" & ")

    private fun splitCodes(codes: String?): List<String> =
        codes?.split('/', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    companion object {
        val EMPTY = Glossary(emptyMap())

        /** The header written and accepted by [parse]. */
        const val HEADER: String = "code,name"

        /**
         * Reads a `code,name` file. Comments, blank lines and a header row are skipped;
         * so is any line without both fields — a half-typed key should cost that line
         * and nothing else. A repeated code keeps the first spelling.
         */
        fun parse(text: String): Glossary {
            val entries = LinkedHashMap<String, String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val fields = TimetableCsv.splitCsv(line)
                if (fields.size < 2) return@forEach
                val code = fields[0].trim().uppercase()
                val name = fields[1].trim()
                if (code.isEmpty() || name.isEmpty()) return@forEach
                if (code == "CODE") return@forEach
                entries.putIfAbsent(code, name)
            }
            return Glossary(entries)
        }

        /**
         * Writes a `code,name` file, codes in order, with [note] as a leading comment.
         *
         * The comment is why this exists rather than a caller building the text: a key that
         * comes back out of the PDF converter has to be diffable against the hand-typed file
         * it replaces, and a stray difference in the header would show up as a change to
         * every line.
         */
        fun write(entries: Map<String, String>, note: String? = null): String = buildString {
            note?.lineSequence()?.forEach { append("# ").append(it).append('\n') }
            append("# ").append(HEADER).append('\n')
            entries.entries
                .sortedBy { it.key }
                .forEach { (code, name) -> append(escape(code)).append(',').append(escape(name)).append('\n') }
        }

        private fun escape(value: String): String =
            if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
