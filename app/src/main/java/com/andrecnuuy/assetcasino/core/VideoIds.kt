package com.andrecnuuy.assetcasino.core

enum class Platform(val label: String) {
    BILIBILI("Bilibili"),
    YOUTUBE("YouTube")
}

data class VideoRef(val platform: Platform, val id: String, val title: String, val url: String)

/**
 * Recognises yt-dlp style file names ("Title [ID].mp4") of direct uploads and rebuilds the video URL.
 * Same rules as the Tampermonkey helper (BV... = Bilibili, otherwise YouTube); Niconico is not supported.
 */
object VideoIds {
    private val BRACKET = Regex("^(.*)\\s\\[([^\\]]+)]$", RegexOption.DOT_MATCHES_ALL)
    private val EXT = Regex("\\.[^/.]+$")
    private val BV = Regex("^BV[0-9A-Za-z]{10}$")
    private val BV_PART = Regex("_p\\d+$", RegexOption.IGNORE_CASE)
    private val YT = Regex("^[A-Za-z0-9_-]{11}$")

    fun parse(rawSource: String?): VideoRef? {
        if (rawSource.isNullOrBlank()) return null
        var clean = rawSource.trim()
        if (clean.startsWith("file://", ignoreCase = true)) clean = clean.substring(7)
        clean = clean.replace(EXT, "")
        val m = BRACKET.find(clean) ?: return null
        val title = m.groupValues[1].trim()
        var id = m.groupValues[2].trim()
        if (id.startsWith("BV")) id = id.replace(BV_PART, "")
        return when {
            BV.matches(id) -> VideoRef(Platform.BILIBILI, id, title, "https://www.bilibili.com/video/$id")
            looksLikeYoutubeId(id) -> VideoRef(Platform.YOUTUBE, id, title, "https://www.youtube.com/watch?v=$id")
            else -> null
        }
    }

    private fun looksLikeYoutubeId(id: String): Boolean {
        if (!YT.matches(id)) return false
        val hasDigit = id.any { it in '0'..'9' }
        val hasUpper = id.any { it in 'A'..'Z' }
        val hasLower = id.any { it in 'a'..'z' }
        val hasSym = id.any { it == '-' || it == '_' }
        // Real ids are random base64; a plain lower-case word is almost certainly not an id.
        return hasDigit || hasSym || (hasUpper && hasLower)
    }
}
