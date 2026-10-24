package com.abfilepro.app.assistant

/**
 * P27: one search box over every assistant module. Arabic spelling variants (أ/ا, ة/ه, ي/ى, tashkeel,
 * Arabic-Indic digits) match each other; every word typed must appear; title hits come first.
 */
object AssistantSearch {
    const val MAX_RESULTS = 40

    fun search(entries: List<AssistantEntry>, query: String, limit: Int = MAX_RESULTS): List<AssistantEntry> {
        val words = query.trim().split(Regex("\\s+")).map(QuickAddParser::normalize).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            val title = QuickAddParser.normalize(entry.title)
            val details = QuickAddParser.normalize(entry.details)
            if (!words.all { title.contains(it) || details.contains(it) }) return@mapNotNull null
            val score = (if (words.all { title.contains(it) }) 2 else 0) + (if (entry.pinned) 1 else 0)
            Triple(entry, score, entry.createdAt)
        }.sortedWith(compareByDescending<Triple<AssistantEntry, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(limit.coerceAtLeast(0)).map { it.first }
    }
}
