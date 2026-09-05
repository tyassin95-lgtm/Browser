package com.slate.browser.data

/** A single row in the omnibox drop-down. */
sealed interface Suggestion {
    /** Stable identity, used to de-duplicate across sources. */
    val key: String
    val primary: String
    val secondary: String

    data class Search(val query: String, val engine: SearchEngine) : Suggestion {
        override val key = "search:$query"
        override val primary = query
        override val secondary = "Search ${engine.label}"
    }

    data class Page(val url: String, val title: String, val bookmarked: Boolean) : Suggestion {
        override val key = url
        override val primary = title.ifBlank { url }
        override val secondary = url
    }
}
