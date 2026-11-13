package com.andrecnuuy.assetcasino.core

/** Source classification: web source picking, site categories, media kinds. */
object Sources {
    const val NON_WEB = "Direct upload (non-web)"

    private val AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)")

    private val RULES: List<Pair<String, List<String>>> = listOf(
        "X / Twitter" to listOf("twitter.com", "x.com", "twimg.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com", "nitter.net"),
        "Pixiv" to listOf("pixiv.net", "pximg.net"),
        "Bilibili" to listOf("bilibili.com", "b23.tv", "hdslb.com", "biliimg.com"),
        "YouTube" to listOf("youtube.com", "youtu.be", "ytimg.com", "ggpht.com"),
        "Niconico" to listOf("nicovideo.jp", "nimg.jp", "nico.ms"),
        "Fanbox" to listOf("fanbox.cc"),
        "Fantia" to listOf("fantia.jp"),
        "Skeb" to listOf("skeb.jp"),
        "Pawoo" to listOf("pawoo.net"),
        "Misskey" to listOf("misskey.io", "misskey.design", "misskey.art"),
        "Bluesky" to listOf("bsky.app", "bsky.social", "bsky.network"),
        "Instagram" to listOf("instagram.com", "cdninstagram.com"),
        "TikTok" to listOf("tiktok.com", "tiktokcdn.com"),
        "Weibo" to listOf("weibo.com", "weibo.cn", "sinaimg.cn"),
        "Tumblr" to listOf("tumblr.com"),
        "Reddit" to listOf("reddit.com", "redd.it", "redditmedia.com"),
        "Discord" to listOf("discordapp.com", "discordapp.net", "discord.com"),
        "Catbox" to listOf("catbox.moe"),
        "DeviantArt" to listOf("deviantart.com", "deviantart.net"),
        "ArtStation" to listOf("artstation.com"),
        "Lofter" to listOf("lofter.com"),
        "Kemono / Coomer" to listOf("kemono.su", "kemono.party", "kemono.cr", "coomer.su", "coomer.party", "coomer.st"),
        "Patreon" to listOf("patreon.com", "patreonusercontent.com"),
        "Naver" to listOf("naver.com", "pstatic.net", "naver.me"),
        "Tenor" to listOf("tenor.com"),
        "Arca.live" to listOf("arca.live"),
        "Web Archive" to listOf("archive.org", "archive.ph", "archive.today"),
        "Danbooru" to listOf("donmai.us"),
        "Xfolio" to listOf("xfolio.jp")
    )

    private val TWO_PART_TLDS = setOf("co.jp", "ne.jp", "or.jp", "ac.jp", "com.cn", "com.tw", "co.kr", "or.kr", "co.uk", "com.au", "com.br")

    fun isHttp(u: String?): Boolean {
        if (u == null) return false
        return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
    }

    fun hostOf(url: String): String? {
        val m = AUTHORITY.find(url) ?: return null
        var a = m.groupValues[1]
        val at = a.lastIndexOf('@')
        if (at >= 0) a = a.substring(at + 1)
        val colon = a.lastIndexOf(':')
        if (colon >= 0 && !a.endsWith("]")) a = a.substring(0, colon)
        a = a.lowercase()
        return if (a.isBlank()) null else a
    }

    fun categoryOfUrl(url: String): String {
        val host = hostOf(url) ?: return "Other"
        for ((label, suffixes) in RULES) {
            for (s in suffixes) {
                if (host == s || host.endsWith(".$s")) return label
            }
        }
        val parts = host.split('.')
        if (parts.size <= 2) return host.removePrefix("www.")
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in TWO_PART_TLDS && parts.size >= 3) parts.takeLast(3).joinToString(".") else last2
    }

    /** Best http(s) source of asset [i] of upload [u], or null when it is a non-web (file://) upload. */
    fun webSource(u: UploadEntry, i: Int): String? {
        val m = u.media.getOrNull(i)
        if (m != null && isHttp(m.pageUrl)) return m.pageUrl
        if (isHttp(u.source)) return u.source
        if (m != null && isHttp(m.srcUrl)) return m.srcUrl
        return null
    }

    /** The raw "file://name.ext" source of a direct upload, or null. */
    fun rawFileSource(u: UploadEntry, i: Int): String? {
        val m = u.media.getOrNull(i)
        if (m != null && m.srcUrl.startsWith("file://", ignoreCase = true)) return m.srcUrl
        if (u.source.startsWith("file://", ignoreCase = true)) return u.source
        return null
    }

    fun categoryOf(u: UploadEntry): String {
        val w = webSource(u, 0) ?: return NON_WEB
        return categoryOfUrl(w)
    }

    fun kindOfExt(ext: String): MediaKind = when (ext.lowercase()) {
        "mp4", "webm", "mkv", "mov", "m4v" -> MediaKind.VIDEO
        "gif", "zip", "swf", "apng" -> MediaKind.ANIMATED
        else -> MediaKind.PHOTO
    }

    fun kindsOf(u: UploadEntry): Set<MediaKind> {
        val s = HashSet<MediaKind>()
        for (m in u.media) s.add(kindOfExt(m.ext))
        return s
    }

    fun mimeOfExt(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "zip" -> "application/zip"
        "swf" -> "application/x-shockwave-flash"
        else -> "application/octet-stream"
    }

    /** File name used when downloading asset [i]. */
    fun downloadName(u: UploadEntry, i: Int): String {
        val m = u.media.getOrNull(i)
        val ext = m?.ext?.ifBlank { "bin" } ?: "bin"
        val raw = rawFileSource(u, i)
        var base = raw?.removePrefix("file://")?.removePrefix("FILE://")?.substringAfterLast('/')?.trim()
        if (base.isNullOrBlank()) base = "${m?.id ?: u.id}.$ext"
        base = base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lower = base.lowercase()
        val extOk = lower.endsWith(".${ext.lowercase()}") || (ext.lowercase() == "jpg" && lower.endsWith(".jpeg"))
        if (!extOk) base = "$base.$ext"
        return base
    }
}
