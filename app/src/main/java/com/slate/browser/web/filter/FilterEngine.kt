package com.slate.browser.web.filter

/**
 * The matcher the published lists are run through.
 *
 * A hundred thousand rules cannot be scanned per request, so rules are indexed the way every
 * serious blocker indexes them: each rule contributes one literal token from its pattern, and a
 * request only ever tests the rules filed under the tokens its own address contains. A typical
 * lookup therefore compares a handful of rules rather than the whole list, and does so without
 * allocating anything but the token substrings.
 */
class FilterEngine private constructor(
    private val blockBuckets: HashMap<String, Array<FilterRule>>,
    private val blockGeneric: Array<FilterRule>,
    private val allowBuckets: HashMap<String, Array<FilterRule>>,
    private val allowGeneric: Array<FilterRule>,
    val ruleCount: Int,
) {

    /** True when the request should be refused. */
    fun shouldBlock(url: String, type: ResourceType, thirdParty: Boolean, documentHost: String): Boolean {
        val lower = url.lowercase()
        val host = documentHost.lowercase()

        val verdict = scan(lower, type, thirdParty, host, blockBuckets, blockGeneric)
        // An `$important` block beats any exception, which is what the option means.
        if (verdict == IMPORTANT) return true
        if (verdict == NONE) return false
        return scan(lower, type, thirdParty, host, allowBuckets, allowGeneric) == NONE
    }

    /**
     * Walks the rules a URL could possibly match. Written as plain loops rather than sequences:
     * this runs on WebView's network threads for every subresource of every page, so it must
     * not allocate beyond the token substrings the index needs.
     */
    private fun scan(
        url: String,
        type: ResourceType,
        thirdParty: Boolean,
        host: String,
        buckets: HashMap<String, Array<FilterRule>>,
        generic: Array<FilterRule>,
    ): Int {
        var found = NONE
        for (rule in generic) {
            if (!rule.matches(url, type, thirdParty, host)) continue
            if (rule.isImportant) return IMPORTANT
            found = MATCH
        }
        var start = -1
        var i = 0
        val length = url.length
        while (i <= length) {
            val tokenChar = i < length && isTokenChar(url[i])
            if (tokenChar) {
                if (start < 0) start = i
            } else {
                if (start >= 0 && i - start >= MIN_TOKEN) {
                    val rules = buckets[url.substring(start, i)]
                    if (rules != null) {
                        for (rule in rules) {
                            if (!rule.matches(url, type, thirdParty, host)) continue
                            if (rule.isImportant) return IMPORTANT
                            found = MATCH
                        }
                    }
                }
                start = -1
            }
            i++
        }
        return found
    }

    companion object {

        /**
         * Builds the index from filter-list text. Unsupported rules are dropped, so a list the
         * engine only partly understands still contributes everything it does understand.
         */
        fun compile(lines: Sequence<String>): FilterEngine {
            val block = HashMap<String, MutableList<FilterRule>>(1 shl 16)
            val allow = HashMap<String, MutableList<FilterRule>>(1 shl 12)
            val blockGeneric = mutableListOf<FilterRule>()
            val allowGeneric = mutableListOf<FilterRule>()
            var count = 0

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#') continue
                if (line.contains("##") || line.contains("#@#") || line.contains("#?#") ||
                    line.contains("#\$#") || line.contains("#%#")
                ) {
                    continue
                }
                val rule = FilterParser.parseNetworkRule(line) ?: continue
                count++
                val token = bestToken(line)
                val buckets = if (rule.isException) allow else block
                val generic = if (rule.isException) allowGeneric else blockGeneric
                if (token == null) generic.add(rule)
                else buckets.getOrPut(token) { mutableListOf() }.add(rule)
            }

            fun freeze(source: HashMap<String, MutableList<FilterRule>>): HashMap<String, Array<FilterRule>> {
                val out = HashMap<String, Array<FilterRule>>(source.size * 2)
                for ((key, value) in source) out[key] = value.toTypedArray()
                return out
            }

            return FilterEngine(
                blockBuckets = freeze(block),
                blockGeneric = blockGeneric.toTypedArray(),
                allowBuckets = freeze(allow),
                allowGeneric = allowGeneric.toTypedArray(),
                ruleCount = count,
            )
        }

        /**
         * The literal a matching address is guaranteed to contain: the longest token in the
         * pattern, ignoring anything past a wildcard boundary. Null when the pattern has no
         * usable literal, which files the rule in the small always-checked set.
         */
        internal fun bestToken(line: String): String? {
            var text = line
            if (text.startsWith("@@")) text = text.substring(2)
            val options = text.lastIndexOf('$')
            if (options > 0) text = text.substring(0, options)
            var best: String? = null
            for (token in tokens(text.lowercase())) {
                if (best == null || token.length > best.length) best = token
            }
            // A one-token pattern of "http" or "www" indexes almost every address; those belong
            // in the generic set where they are checked once rather than filed uselessly.
            return best?.takeUnless { it == "http" || it == "https" || it == "www" || it == "com" }
        }

        /** Every run of at least three name characters, which is what patterns are built from. */
        internal fun tokens(text: String): List<String> {
            val out = mutableListOf<String>()
            var start = -1
            for (i in text.indices) {
                if (isTokenChar(text[i])) {
                    if (start < 0) start = i
                } else {
                    if (start >= 0 && i - start >= MIN_TOKEN) out.add(text.substring(start, i))
                    start = -1
                }
            }
            if (start >= 0 && text.length - start >= MIN_TOKEN) out.add(text.substring(start))
            return out
        }

        internal fun isTokenChar(c: Char): Boolean =
            (c in 'a'..'z') || (c in '0'..'9') || c == '%'

        internal const val MIN_TOKEN = 3
        internal const val NONE = 0
        internal const val MATCH = 1
        internal const val IMPORTANT = 2
    }
}
