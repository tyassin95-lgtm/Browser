package com.slate.browser.web.filter

/**
 * Reads Adblock Plus filter syntax — the format EasyList, EasyPrivacy and the AdGuard lists are
 * published in.
 *
 * Using the published format is the point: the rules stay the maintained, community-reviewed
 * ones, and updating the browser's blocking means dropping in a newer copy of a list rather
 * than hand-editing a bespoke set of domains.
 */
object FilterParser {

    /** A rule the engine cannot honour is skipped rather than approximated. */
    fun parseNetworkRule(line: String): FilterRule? {
        var text = line
        val exception = text.startsWith("@@")
        if (exception) text = text.substring(2)

        var types = 0
        var excludedTypes = 0
        var party = PARTY_ANY
        var important = false
        var included: MutableList<String>? = null
        var excluded: MutableList<String>? = null

        val optionsAt = optionsIndex(text)
        if (optionsAt >= 0) {
            val options = text.substring(optionsAt + 1)
            text = text.substring(0, optionsAt)
            for (raw in options.split(',')) {
                if (raw.isEmpty()) continue
                val negated = raw.startsWith("~")
                val option = if (negated) raw.substring(1) else raw
                val eq = option.indexOf('=')
                val name = if (eq < 0) option else option.substring(0, eq)
                val value = if (eq < 0) "" else option.substring(eq + 1)
                when (name) {
                    "third-party", "3p" -> party = if (negated) PARTY_FIRST else PARTY_THIRD
                    "first-party", "1p" -> party = if (negated) PARTY_THIRD else PARTY_FIRST
                    "important" -> important = true
                    "match-case" -> Unit // Handled by lowercasing both sides; harmless to ignore.
                    "domain", "from" -> for (d in value.split('|')) {
                        if (d.isEmpty()) continue
                        if (d.startsWith("~")) {
                            (excluded ?: mutableListOf<String>().also { excluded = it })
                                .add(d.substring(1).lowercase())
                        } else {
                            (included ?: mutableListOf<String>().also { included = it })
                                .add(d.lowercase())
                        }
                    }
                    // Options that change what a request returns rather than whether it is made,
                    // or that scope a rule in a way this engine does not model, disqualify it.
                    "csp", "redirect", "redirect-rule", "rewrite", "replace", "removeparam",
                    "method", "header", "permissions", "inline-script", "inline-font",
                    "elemhide", "generichide", "specifichide", "genericblock", "content",
                    "cookie", "stealth", "app", "denyallow", "to",
                    -> return null
                    else -> {
                        val flag = ResourceType.fromOption(name) ?: return null
                        if (negated) excludedTypes = excludedTypes or flag else types = types or flag
                    }
                }
            }
        }

        // A regular-expression rule is a whole other matcher; the lists use very few of them.
        if (text.length > 1 && text.startsWith("/") && text.endsWith("/")) return null
        if (text.isEmpty()) return null

        var hostAnchored = false
        var leftAnchored = false
        if (text.startsWith("||")) {
            hostAnchored = true
            text = text.substring(2)
        } else if (text.startsWith("|")) {
            leftAnchored = true
            text = text.substring(1)
        }
        var endAnchored = false
        if (text.endsWith("|")) {
            endAnchored = true
            text = text.dropLast(1)
        }
        var rightSeparator = false
        if (text.endsWith("^")) {
            rightSeparator = true
            text = text.dropLast(1)
        }
        if (text.isEmpty()) return null

        val pattern = text.lowercase()
        // Only an interior `^` or any `*` needs the regular-expression path.
        val complex = pattern.indexOf('*') >= 0 || pattern.indexOf('^') >= 0

        val effective = when {
            types != 0 -> types
            excludedTypes != 0 -> ResourceType.DEFAULT and excludedTypes.inv()
            else -> ResourceType.DEFAULT
        }

        return FilterRule(
            pattern = pattern,
            hostAnchored = hostAnchored,
            leftAnchored = leftAnchored,
            rightSeparator = rightSeparator,
            endAnchored = endAnchored,
            complex = complex,
            isException = exception,
            isImportant = important,
            types = effective,
            party = party,
            includedDomains = included?.toTypedArray(),
            excludedDomains = excluded?.toTypedArray(),
        )
    }

    /**
     * The `$` that starts the options, ignoring one inside a regular-expression body.
     * Returns -1 when the rule carries no options.
     */
    private fun optionsIndex(text: String): Int {
        val at = text.lastIndexOf('$')
        if (at <= 0) return -1
        // `$` immediately followed by a separator is part of the pattern, not an option list.
        if (at + 1 >= text.length) return -1
        return at
    }

    /**
     * Splits a cosmetic rule into the domains it is scoped to and the selector it hides.
     * Returns null for the extended syntaxes (`#?#`, `#$#`, `#%#`) this engine does not run.
     */
    fun parseCosmeticRule(line: String): CosmeticRule? {
        val hide = line.indexOf("##")
        val allow = line.indexOf("#@#")
        val at: Int
        val exception: Boolean
        when {
            allow >= 0 && (hide < 0 || allow < hide) -> { at = allow; exception = true }
            hide >= 0 -> { at = hide; exception = false }
            else -> return null
        }
        val selector = line.substring(at + if (exception) 3 else 2)
        if (selector.isEmpty()) return null
        // Procedural and scriptlet syntax is not a CSS selector and must not be injected as one.
        if (selector.startsWith("+js") || selector.contains(":has-text(") ||
            selector.contains(":matches-css") || selector.contains(":xpath(") ||
            selector.contains(":style(") || selector.contains(":remove(") ||
            selector.contains(":upward(") || selector.contains(":watch-attr(")
        ) {
            return null
        }
        val scope = line.substring(0, at)
        val domains = mutableListOf<String>()
        val notDomains = mutableListOf<String>()
        for (d in scope.split(',')) {
            val trimmed = d.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("~")) notDomains.add(trimmed.substring(1).lowercase())
            else domains.add(trimmed.lowercase())
        }
        return CosmeticRule(domains, notDomains, selector, exception)
    }
}

/** One `domain##selector` rule. Empty [domains] means the rule is generic. */
class CosmeticRule(
    val domains: List<String>,
    val excludedDomains: List<String>,
    val selector: String,
    val isException: Boolean,
)
