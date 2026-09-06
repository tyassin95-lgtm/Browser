package com.slate.browser.web.filter

/**
 * The resource kinds a filter rule can be scoped to.
 *
 * These are the Adblock Plus option names the published lists actually use, so a rule's
 * `$script,third-party` means here what it means in EasyList rather than something approximate.
 */
enum class ResourceType(val flag: Int) {
    DOCUMENT(1 shl 0),
    SUBDOCUMENT(1 shl 1),
    SCRIPT(1 shl 2),
    IMAGE(1 shl 3),
    STYLESHEET(1 shl 4),
    XHR(1 shl 5),
    MEDIA(1 shl 6),
    FONT(1 shl 7),
    PING(1 shl 8),
    WEBSOCKET(1 shl 9),
    POPUP(1 shl 10),
    OTHER(1 shl 11),
    ;

    companion object {
        const val ALL: Int = (1 shl 12) - 1

        /** Everything a rule with no type option applies to: every kind except a popup. */
        @JvmField
        val DEFAULT: Int = ALL and POPUP.flag.inv()

        fun fromOption(name: String): Int? = when (name) {
            "document", "doc" -> DOCUMENT.flag
            "subdocument", "frame" -> SUBDOCUMENT.flag
            "script" -> SCRIPT.flag
            "image" -> IMAGE.flag
            "stylesheet", "css" -> STYLESHEET.flag
            "xmlhttprequest", "xhr" -> XHR.flag
            "media" -> MEDIA.flag
            "font" -> FONT.flag
            "ping", "beacon" -> PING.flag
            "websocket" -> WEBSOCKET.flag
            "popup" -> POPUP.flag
            "other", "object", "object-subrequest", "webrtc" -> OTHER.flag
            else -> null
        }
    }
}

/** Third-party scoping: a rule can require a cross-origin request, a same-origin one, or neither. */
internal const val PARTY_ANY = 0
internal const val PARTY_THIRD = 1
internal const val PARTY_FIRST = 2

/**
 * One parsed network filter.
 *
 * Patterns keep Adblock Plus semantics: `||` anchors to a hostname and any of its subdomains,
 * `|` to the start or end of the address, `^` to a separator, and `*` to anything. Simple
 * patterns — the overwhelming majority — are matched by [matchPlain] without ever building a
 * regular expression; only the ones that genuinely need one pay for it, and then only once.
 */
class FilterRule internal constructor(
    private val pattern: String,
    private val hostAnchored: Boolean,
    private val leftAnchored: Boolean,
    /** The pattern ended with `^`: the match must be followed by a separator or the end. */
    private val rightSeparator: Boolean,
    /** The pattern ended with `|`: the match must reach the end of the address. */
    private val endAnchored: Boolean,
    /** True when the pattern contains `*` or an interior `^`, which needs the regex path. */
    private val complex: Boolean,
    val isException: Boolean,
    val isImportant: Boolean,
    private val types: Int,
    private val party: Int,
    private val includedDomains: Array<String>?,
    private val excludedDomains: Array<String>?,
) {

    /**
     * Built on first use and kept. A plain field rather than `by lazy`, which would put a
     * second object behind every one of a hundred thousand rules for a value most of them
     * never compute; a benign race just builds the same expression twice.
     */
    @Volatile
    private var compiled: Regex? = null

    private fun regex(): Regex? {
        compiled?.let { return it }
        val built = runCatching { Regex(toRegexSource()) }.getOrNull() ?: return null
        compiled = built
        return built
    }

    fun matches(url: String, type: ResourceType, thirdParty: Boolean, documentHost: String): Boolean {
        if (types and type.flag == 0) return false
        if (party == PARTY_THIRD && !thirdParty) return false
        if (party == PARTY_FIRST && thirdParty) return false
        if (!domainAllows(documentHost)) return false
        return if (complex) regex()?.containsMatchIn(url) == true else matchPlain(url)
    }

    private fun domainAllows(documentHost: String): Boolean {
        val excluded = excludedDomains
        if (excluded != null && excluded.any { hostMatches(documentHost, it) }) return false
        val included = includedDomains ?: return true
        return included.any { hostMatches(documentHost, it) }
    }

    /** Literal matching for the common shapes, with `^` only ever at the very end. */
    private fun tailOk(url: String, end: Int): Boolean {
        if (endAnchored && end != url.length) return false
        return !rightSeparator || isSeparatorOrEnd(url, end)
    }

    private fun matchPlain(url: String): Boolean {
        val core = pattern
        if (hostAnchored) {
            // `||a.com/x` must start at a host boundary: the host itself or any subdomain of it.
            val schemeEnd = url.indexOf("://")
            var start = if (schemeEnd < 0) 0 else schemeEnd + 3
            while (true) {
                if (url.startsWith(core, start) && tailOk(url, start + core.length)) return true
                val dot = url.indexOf('.', start)
                // Only walk within the authority; a dot in the path is not a host boundary.
                val authorityEnd = authorityEnd(url, schemeEnd)
                if (dot < 0 || dot + 1 >= authorityEnd) return false
                start = dot + 1
            }
        }
        if (leftAnchored) {
            return url.startsWith(core) && tailOk(url, core.length)
        }
        var from = 0
        while (true) {
            val at = url.indexOf(core, from)
            if (at < 0) return false
            if (tailOk(url, at + core.length)) return true
            from = at + 1
        }
    }

    private fun authorityEnd(url: String, schemeEnd: Int): Int {
        val from = if (schemeEnd < 0) 0 else schemeEnd + 3
        var i = from
        while (i < url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') return i
            i++
        }
        return url.length
    }

    private fun toRegexSource(): String {
        val sb = StringBuilder(pattern.length + 16)
        if (hostAnchored) sb.append("^[a-z-]+://(?:[^/?#]*\\.)?")
        if (leftAnchored) sb.append('^')
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append(SEPARATOR_CLASS)
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append('\\').append(c)
            }
        }
        if (rightSeparator) sb.append(SEPARATOR_CLASS)
        if (endAnchored) sb.append('$')
        return sb.toString()
    }

    internal companion object {
        /** ABP's separator: anything that is not part of a name, or the end of the address. */
        const val SEPARATOR_CLASS = "(?:[^a-zA-Z0-9_.%-]|\$)"

        fun isSeparatorOrEnd(url: String, index: Int): Boolean {
            if (index >= url.length) return true
            val c = url[index]
            return !(c.isLetterOrDigit() || c == '_' || c == '.' || c == '%' || c == '-')
        }

        /** `example.com` covers `a.example.com`; it does not cover `notexample.com`. */
        fun hostMatches(host: String, domain: String): Boolean {
            if (host == domain) return true
            return host.length > domain.length &&
                host.endsWith(domain) &&
                host[host.length - domain.length - 1] == '.'
        }
    }
}
