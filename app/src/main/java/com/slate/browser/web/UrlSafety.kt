package com.slate.browser.web

import android.net.Uri
import java.net.IDN

/**
 * One place that decides what a URL is allowed to do, and how it is shown.
 *
 * Every URL in a browser is attacker-controlled until proven otherwise: typed, pasted, linked,
 * redirected to, handed in by another app, or written by a script. Scattering the checks means
 * each caller gets to be wrong on its own, so all of them are here and each entry point names
 * the decision it is making rather than re-deriving it.
 */
object UrlSafety {

    /** Schemes the browser will render itself. */
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * Schemes that must never be navigated to from anything a page or another app supplied.
     *
     * `javascript:` runs in whatever document is already loaded, which is why every browser
     * strips it from typed and pasted input — a URL a user was talked into pasting would
     * otherwise execute against the site they are signed in to. `data:` and `blob:` render
     * attacker-authored markup under an address bar that can show nothing useful, which is a
     * phishing primitive rather than a feature. `file:`, `content:` and the rest reach the
     * device instead of the web.
     */
    private val NEVER_NAVIGABLE = setOf(
        "javascript", "data", "blob", "file", "content", "filesystem",
        "android_asset", "android_res", "jar", "ftp", "ws", "wss",
    )

    /** Schemes that go to a well-understood system app rather than an arbitrary one. */
    val SYSTEM_APP_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "geo", "market")

    /**
     * The scheme an engine would act on, not the one the text appears to have.
     *
     * Tab, newline and carriage return are removed before parsing because that is what URL
     * parsers do with them — so `java\nscript:` is `javascript:` by the time anything executes
     * it. Reading the raw text instead would compare something the engine never sees.
     */
    fun schemeOf(url: String): String? =
        runCatching { Uri.parse(normalise(url)).scheme?.lowercase() }.getOrNull()

    /** The same stripping, for callers that need the address rather than its scheme. */
    fun normalise(url: String): String = url.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim()

    fun isWeb(url: String): Boolean = schemeOf(url) in WEB_SCHEMES

    /**
     * Whether the browser may load this in a tab.
     *
     * `about:blank` is allowed because that is what an empty tab is; every other `about:` form
     * is a page the browser does not implement.
     */
    fun isNavigable(url: String): Boolean {
        val scheme = schemeOf(url) ?: return false
        if (scheme in NEVER_NAVIGABLE) return false
        if (scheme == "about") return normalise(url) == "about:blank"
        return true
    }

    /**
     * Whether a URL arriving from outside the browser — another app's intent, a share — may be
     * opened. Only the web: an external caller has no business steering the browser at the
     * device, and an exported activity is reachable by any app on the phone.
     */
    fun isSafeExternalEntry(url: String): Boolean =
        isWeb(url) && !runCatching { Uri.parse(url).host }.getOrNull().isNullOrBlank()

    /**
     * Whether the browser may hand this address to another application. The deny list is what
     * matters: an app scheme is arbitrary by nature, but these reach the device or this
     * browser's own storage and are never a legitimate hand-off.
     */
    fun mayLeaveTheBrowser(url: String): Boolean {
        val scheme = schemeOf(url) ?: return false
        if (scheme in WEB_SCHEMES) return false
        return scheme !in NEVER_NAVIGABLE
    }

    /**
     * How a host should be written in the address bar.
     *
     * Unicode is shown only when it cannot be used to impersonate: a host written entirely in
     * one script reads as intended, while one that mixes scripts is almost always an attempt to
     * pass for something else — `аpple.com` with a Cyrillic а is indistinguishable from the
     * real thing at a glance. Those are shown in their encoded form, which is ugly and true.
     */
    fun displayHost(host: String): String {
        if (host.isEmpty()) return host
        val unicode = runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)
        if (unicode.all { it.code < 0x80 }) return unicode
        if (isSingleScript(unicode)) return unicode
        return runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)
    }

    /**
     * Whether a host's writing systems can be trusted to read as themselves.
     *
     * This is Unicode's "Highly Restrictive" profile (UTS #39), not a rule of our own: a single
     * script is fine, and so are the three combinations real languages are written in — Han
     * with Hiragana and Katakana for Japanese, Han with Bopomofo, Han with Hangul — each
     * optionally alongside Latin. Everything else that mixes is refused, which is what catches
     * a Latin word with one Cyrillic letter substituted into it.
     */
    private fun isSingleScript(host: String): Boolean {
        val scripts = mutableSetOf<Character.UnicodeScript>()
        for (character in host) {
            if (character == '.' || character == '-' || character.isDigit()) continue
            val script = runCatching { Character.UnicodeScript.of(character.code) }.getOrNull()
                ?: return false
            when (script) {
                // Punctuation and marks belong to no writing system and identify nothing.
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> continue
                else -> scripts.add(script)
            }
        }
        if (scripts.size <= 1) return true
        val withoutLatin = scripts - Character.UnicodeScript.LATIN
        return ALLOWED_COMBINATIONS.any { withoutLatin == it || withoutLatin.isEmpty() }
    }

    /** The script combinations that are languages rather than disguises. */
    private val ALLOWED_COMBINATIONS: List<Set<Character.UnicodeScript>> = listOf(
        setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
        ),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.KATAKANA),
        setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.BOPOMOFO),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL),
    )
}
