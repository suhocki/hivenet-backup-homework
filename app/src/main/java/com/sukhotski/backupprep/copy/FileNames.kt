package com.sukhotski.backupprep.copy

object FileNames {
    /** Linux NAME_MAX is 255 bytes. Leave room for " (99)" and ".part". */
    private const val MAX_BYTES = 240
    private const val FALLBACK = "file"
    private val illegalChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** Makes a provider-supplied display name safe to use as a file name. */
    fun sanitize(displayName: String?): String {
        var cleaned = displayName.orEmpty().replace(illegalChars, "_").trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return FALLBACK
        if (cleaned.utf8Size() > MAX_BYTES) {
            val (base, ext) = split(cleaned)
            var cut = base
            while (cut.isNotEmpty() && (cut + ext).utf8Size() > MAX_BYTES) cut = cut.dropLast(1)
            cleaned = if (cut.isEmpty()) cleaned.take(MAX_BYTES / 4) else cut + ext
        }
        // ".part" is reserved for temp files, otherwise "foo.part" and "foo" in one batch would share a temp file.
        return if (cleaned.endsWith(StagingDestination.PART_SUFFIX, ignoreCase = true)) cleaned + "_" else cleaned
    }

    /** Keeps the first occurrence of a name and appends " (1)", " (2)", ... to later duplicates. */
    fun unique(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { name ->
            var candidate = name
            var n = 1
            while (!used.add(candidate.lowercase())) {
                val (base, ext) = split(name)
                candidate = "$base (${n++})$ext"
            }
            candidate
        }
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }
}
