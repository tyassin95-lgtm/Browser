package com.slate.browser.cast.dlna

/**
 * Naming a stream in a word the television already uses.
 *
 * "File format not supported" is very rarely a television that cannot decode a video. It is
 * almost always a television that was handed a media type it does not have in its list, and
 * refused before decoding anything — the same bytes under a different name play perfectly. Every
 * renderer publishes that list through `GetProtocolInfo`, and the only sound way to pick a name
 * is to read it rather than to guess.
 *
 * The types here are grouped by what they actually are on the wire. An MPEG transport stream is
 * called four different things by four different manufacturers, and all four are the same bytes;
 * choosing between them is naming, not conversion.
 */
object MediaTypes {

    /** The names one container goes by, best first. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "video/mp2t" to listOf(
            "video/mp2t",
            "video/mpeg",
            "video/vnd.dlna.mpeg-tts",
            "video/x-mpegts",
            "video/mp2p",
            "video/x-mpeg",
        ),
        "video/mp4" to listOf("video/mp4", "video/x-m4v", "video/mpeg4", "video/3gpp"),
        "video/webm" to listOf("video/webm", "video/x-matroska"),
        "video/x-matroska" to listOf("video/x-matroska", "video/webm"),
        "video/quicktime" to listOf("video/quicktime", "video/mp4"),
        "audio/mpeg" to listOf("audio/mpeg", "audio/mp3", "audio/x-mpeg"),
        "audio/mp4" to listOf("audio/mp4", "audio/x-m4a", "audio/aac", "audio/mpeg"),
    )

    /**
     * What to call [container] when speaking to a renderer that accepts [accepted], or null when
     * the renderer has said it accepts nothing of the kind.
     *
     * An empty list means the device did not answer the question, and a device that will not say
     * what it plays is given the honest name and allowed to decide for itself — refusing on its
     * behalf would be worse than letting it try.
     */
    fun nameFor(container: String, accepted: Set<String>): String? {
        val normalised = container.substringBefore(';').trim().lowercase()
        if (accepted.isEmpty()) return normalised
        val names = ALIASES[normalised] ?: listOf(normalised)
        names.firstOrNull { it in accepted }?.let { return it }
        // Some renderers publish a wildcard for a whole family rather than each member.
        val family = normalised.substringBefore('/') + "/*"
        if (family in accepted || "*/*" in accepted) return normalised
        return null
    }

    /**
     * Every name a container goes by, for the devices that will not say what they accept.
     *
     * A renderer that answers `GetProtocolInfo` with nothing leaves only one honest approach:
     * offer the names in turn and let the device answer with its behaviour. That is a short,
     * bounded list — four attempts at most — rather than a guess, and it ends in a refusal that
     * says so rather than in another try.
     */
    fun namesFor(container: String): List<String> {
        val normalised = container.substringBefore(';').trim().lowercase()
        return ALIASES[normalised] ?: listOf(normalised)
    }

    /** What to tell somebody whose television will not take the stream, in its own vocabulary. */
    fun describe(accepted: Set<String>): String {
        val video = accepted.filter { it.startsWith("video/") }
        if (video.isEmpty()) return ""
        return video.take(4).joinToString(", ") { it.substringAfter('/') }
    }
}
