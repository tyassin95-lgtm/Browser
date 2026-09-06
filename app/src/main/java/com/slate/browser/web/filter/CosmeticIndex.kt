package com.slate.browser.web.filter

/**
 * The element-hiding half of the lists.
 *
 * Cosmetic rules are what removes the floating panels, sticky bars and fake players that a
 * blocked request leaves no trace of — the markup is the site's own, so no network rule can
 * touch it. Rules scoped to a domain are the accurate ones and are all kept; unscoped rules
 * apply everywhere and so are kept only where they name markup that is unambiguously ad
 * tooling, because a generic rule that guesses is how a blocker starts eating real content.
 */
class CosmeticIndex private constructor(
    private val specific: HashMap<String, Array<String>>,
    private val exceptions: HashMap<String, Array<String>>,
    private val generic: Array<String>,
) {

    /** Every selector to hide on [host], with the site's own exceptions already removed. */
    fun selectorsFor(host: String): List<String> {
        val lower = host.lowercase()
        val allowed = collect(exceptions, lower)
        val out = ArrayList<String>(generic.size + 16)
        for (selector in generic) if (allowed == null || selector !in allowed) out.add(selector)
        for (selector in collect(specific, lower).orEmpty()) {
            if (allowed == null || selector !in allowed) out.add(selector)
        }
        return out
    }

    /** Gathers the entries filed under the host and each of its parent domains. */
    private fun collect(source: HashMap<String, Array<String>>, host: String): Set<String>? {
        var result: MutableSet<String>? = null
        var index = 0
        while (true) {
            source[host.substring(index)]?.let { found ->
                (result ?: mutableSetOf<String>().also { result = it }).addAll(found)
            }
            val dot = host.indexOf('.', index)
            if (dot < 0) return result
            index = dot + 1
        }
    }

    companion object {

        fun compile(lines: Sequence<String>): CosmeticIndex {
            val specific = HashMap<String, MutableSet<String>>(1 shl 12)
            val exceptions = HashMap<String, MutableSet<String>>(1 shl 8)
            val generic = LinkedHashSet<String>()

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                val rule = FilterParser.parseCosmeticRule(line) ?: continue
                if (rule.isException) {
                    for (domain in rule.domains) {
                        exceptions.getOrPut(domain) { mutableSetOf() }.add(rule.selector)
                    }
                    continue
                }
                if (rule.domains.isEmpty()) {
                    if (isSafeGeneric(rule.selector)) generic.add(rule.selector)
                    continue
                }
                for (domain in rule.domains) {
                    specific.getOrPut(domain) { mutableSetOf() }.add(rule.selector)
                }
                // A `~domain` on a hiding rule means "everywhere but here", which for a
                // domain-scoped rule is an exception on that domain.
                for (domain in rule.excludedDomains) {
                    exceptions.getOrPut(domain) { mutableSetOf() }.add(rule.selector)
                }
            }

            fun freeze(source: HashMap<String, MutableSet<String>>): HashMap<String, Array<String>> {
                val out = HashMap<String, Array<String>>(source.size * 2)
                for ((key, value) in source) out[key] = value.toTypedArray()
                return out
            }

            return CosmeticIndex(freeze(specific), freeze(exceptions), generic.toTypedArray())
        }

        /**
         * Whether an unscoped rule is safe to run on every site. It has to name advertising in
         * the identifier itself, and it must not be a bare tag or attribute selector that could
         * plausibly describe ordinary markup.
         */
        internal fun isSafeGeneric(selector: String): Boolean {
            if (selector.length < 4 || selector.length > 120) return false
            if (selector[0] != '.' && selector[0] != '#') return false
            if (selector.contains(' ') || selector.contains(',') || selector.contains(':')) return false
            val name = selector.substring(1).lowercase()
            if (name.isEmpty() || !name[0].isLetter()) return false
            return AD_WORDS.any { name.contains(it) }
        }

        /**
         * The words that make an identifier unambiguous. "ad" alone is not among them: it
         * matches "header", "gradient" and "download", and a blocker that hides those is worse
         * than one that misses an advert.
         */
        private val AD_WORDS = listOf(
            "advert", "adbanner", "ad-banner", "ad_banner", "adslot", "ad-slot", "ad_slot",
            "adbox", "ad-box", "ad-container", "ad_container", "adcontainer", "adwrapper",
            "ad-wrapper", "ad_wrapper", "adframe", "ad-frame", "adsense", "adsbygoogle",
            "doubleclick", "googlead", "google-ad", "google_ad", "banner-ad", "banner_ad",
            "bannerad", "sponsor", "popunder", "pop-under", "popupad", "interstitial",
            "taboola", "outbrain", "revcontent", "mgid", "adzone", "ad-zone", "adunit",
            "ad-unit", "ad_unit", "exoclick", "juicyads", "trafficjunky", "adspot",
        )
    }
}
