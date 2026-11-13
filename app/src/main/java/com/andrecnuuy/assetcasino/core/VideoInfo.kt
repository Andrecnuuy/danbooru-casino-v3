package com.andrecnuuy.assetcasino.core

import java.net.URLDecoder
import java.text.Normalizer

class ChannelRef(
    val name: String,
    val url: String,
    val handle: String? = null,
    val altUrls: List<String> = emptyList()
)

/** An uploader that has no Danbooru artist entry yet. [registrationUrl] opens a pre-filled "new artist" form. */
class MissingArtist(
    val name: String,
    val original: String,
    val url: String,
    val extraUrls: List<String>
) {
    fun registrationUrl(): String {
        val sb = StringBuilder("${Links.BASE}/artists/new?")
        sb.append("artist%5Bname%5D=").append(UrlUtil.enc(name))
        if (original.isNotBlank()) sb.append("&artist%5Bother_names%5D=").append(UrlUtil.enc(original))
        val urls = (listOf(url) + extraUrls).filter { it.isNotBlank() }.distinct()
        if (urls.isNotEmpty()) sb.append("&artist%5Burl_string%5D=").append(UrlUtil.enc(urls.joinToString("\n")))
        return sb.toString()
    }
}

class PageData(val title: String, val description: String, val artists: List<ChannelRef>)

class VideoInfo(
    val title: String,
    val description: String,
    val source: String,
    val registered: List<String>,
    val missing: List<MissingArtist>,
    val notes: List<String>
) {
    /** The copyable block: Title / Description / Source / Artist (+ registration links). */
    fun toText(): String {
        val sb = StringBuilder()
        sb.append("Title: ").append(title).append('\n')
        sb.append("Description: ").append(description).append('\n')
        sb.append("Source: ").append(source).append('\n')
        val lines = ArrayList<String>()
        if (registered.isNotEmpty()) lines.add(registered.joinToString(" "))
        for (m in missing) lines.add(m.registrationUrl())
        sb.append("Artist: ").append(lines.joinToString("\n"))
        return sb.toString()
    }
}

/** Pulls a balanced JSON object out of a page, e.g. `var ytInitialData = {...};`. */
internal object HtmlJson {
    fun extract(html: String, key: String): Any? {
        var from = 0
        while (true) {
            val k = html.indexOf(key, from)
            if (k < 0) return null
            var i = k + key.length
            while (i < html.length && (html[i] == ' ' || html[i] == '=' || html[i] == '"' || html[i] == ']')) i++
            if (i < html.length && html[i] == '{') {
                val end = matchBrace(html, i)
                if (end > i) {
                    try {
                        return MiniJson.parse(html.substring(i, end + 1))
                    } catch (e: Exception) {
                        // not the real assignment, keep looking
                    }
                }
            }
            from = k + key.length
        }
    }

    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
            } else {
                if (c == '"') {
                    inStr = true
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}

/**
 * Port of the "YT/BB/NC Video Direct Upload Helper" userscript logic for Bilibili and YouTube:
 * read the video page, find the uploader(s), check them against Danbooru artists, romanise names
 * of unregistered ones and prepare a pre-filled artist registration link.
 */
class VideoInfoService(private val http: Http, private val api: DanbooruApi) {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        const val TRANSLIT_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=rm&q="

        private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uff66-\\uff9f]")
        private val ASCII_NAME = Regex("^[a-z0-9\\s_!-]+$", RegexOption.IGNORE_CASE)
        private val WS = Regex("\\s+")
        private val URL_IN_TEXT = Regex("(https?://[^\\s]+)")
        private val BILI_DESC = Regex("(https?://[^\\s]+)|(BV[a-zA-Z0-9]{10,})")
        private val REDIRECT = Regex("\"https://www\\.youtube\\.com/redirect\\?event=[^\"]+?q=(https%3A%2F%2F[^\"]+?)\"")
        private val GENERIC_PATHS = setOf("intent", "share", "home", "search", "hashtag", "explore", "settings", "login", "privacy")
        private val LINK_PATTERNS = listOf(
            Regex("(?<![A-Za-z0-9-])(?:twitter|x)\\.com/[A-Za-z0-9_]{4,}", RegexOption.IGNORE_CASE),
            Regex("pixiv\\.net/(?:en/)?users/\\d+", RegexOption.IGNORE_CASE),
            Regex("[a-z0-9-]+\\.fanbox\\.cc", RegexOption.IGNORE_CASE),
            Regex("fantia\\.jp/fanclubs/\\d+", RegexOption.IGNORE_CASE),
            Regex("skeb\\.jp/@[A-Za-z0-9_]+", RegexOption.IGNORE_CASE),
            Regex("pawoo\\.net/@[A-Za-z0-9_]+", RegexOption.IGNORE_CASE)
        )
    }

    fun gather(ref: VideoRef): VideoInfo {
        val notes = ArrayList<String>()
        val page = when (ref.platform) {
            Platform.BILIBILI -> bilibili(ref.id, notes)
            Platform.YOUTUBE -> youtube(ref.id, notes)
        }
        val title = if (ref.title.isNotBlank()) ref.title else page.title
        if (page.description.isBlank()) notes.add("No description found on the video page.")
        val registered = LinkedHashSet<String>()
        val missing = ArrayList<MissingArtist>()
        if (page.artists.isEmpty()) {
            notes.add("Could not detect the uploader on the video page.")
        } else {
            checkArtists(page.artists, registered, missing, notes)
        }
        return VideoInfo(title, page.description, ref.url, registered.toList(), missing, notes)
    }

    // ---- page fetching -------------------------------------------------------------------------

    private fun page(url: String, extra: Map<String, String> = emptyMap()): HttpResponse? {
        return try {
            val h = HashMap<String, String>()
            h["User-Agent"] = DESKTOP_UA
            h["Accept-Language"] = "en-US,en;q=0.9"
            h.putAll(extra)
            http.get(url, h)
        } catch (e: Exception) {
            null
        }
    }

    private fun bilibili(id: String, notes: MutableList<String>): PageData {
        var title = ""
        var desc = ""
        val artists = ArrayList<ChannelRef>()
        val seen = HashSet<Long>()
        val referer = mapOf("Referer" to "https://www.bilibili.com/")

        fun addPerson(o: JObj?) {
            val mid = o.long("mid") ?: return
            val name = o.str("name") ?: return
            if (seen.add(mid)) artists.add(ChannelRef(name, "https://space.bilibili.com/$mid"))
        }

        fun fill(data: JObj?) {
            if (data == null) return
            title = data.str("title") ?: ""
            desc = linkifyBili(data.str("desc") ?: "")
            addPerson(data.obj("owner"))
            for (s in data.arr("staff").orEmpty()) addPerson(s.asObj())
        }

        val api = page("https://api.bilibili.com/x/web-interface/view?bvid=$id", referer)
        if (api != null && api.code == 200) {
            val root = try { MiniJson.parse(api.body).asObj() } catch (e: Exception) { null }
            if (root != null && root.long("code") == 0L) fill(root.obj("data"))
        }
        if (artists.isEmpty()) {
            val r = page("https://www.bilibili.com/video/$id", referer)
            if (r != null && r.code == 200) {
                val state = HtmlJson.extract(r.body, "__INITIAL_STATE__").asObj()
                fill(state.obj("videoData"))
            } else {
                notes.add("Bilibili page could not be loaded (HTTP ${r?.code ?: "error"}).")
            }
        }
        return PageData(title, desc, artists)
    }

    private fun youtube(id: String, notes: MutableList<String>): PageData {
        var title = ""
        var desc = ""
        val artists = ArrayList<ChannelRef>()
        val r = page("https://www.youtube.com/watch?v=$id")
        if (r != null && r.code == 200) {
            val html = r.body
            val player = HtmlJson.extract(html, "ytInitialPlayerResponse").asObj()
            val details = player.obj("videoDetails")
            title = details.str("title") ?: ""
            var rawDesc = details.str("shortDescription") ?: ""
            var chName = details.str("author")
            var chId = details.str("channelId")
            var handle: String? = null

            val data = HtmlJson.extract(html, "ytInitialData").asObj()
            val contents = data.obj("contents").obj("twoColumnWatchNextResults").obj("results").obj("results").arr("contents")
            val info = contents.orEmpty().mapNotNull { it.asObj().obj("videoSecondaryInfoRenderer") }.firstOrNull()
            val owner = info.obj("owner").obj("videoOwnerRenderer")
            val ownerName = owner.obj("title").arr("runs")?.firstOrNull().asObj().str("text")
            val endpoint = owner.obj("navigationEndpoint").obj("browseEndpoint")
            val browseId = endpoint.str("browseId")
            val canonical = endpoint.str("canonicalBaseUrl")
            if (!ownerName.isNullOrBlank()) chName = ownerName
            if (!browseId.isNullOrBlank()) chId = browseId
            if (canonical != null && canonical.startsWith("/@")) handle = canonical.removePrefix("/@")
            if (rawDesc.isBlank()) rawDesc = info.obj("attributedDescription").str("content") ?: ""
            desc = linkifyUrls(rawDesc)

            if (!chName.isNullOrBlank() && !chId.isNullOrBlank()) {
                val alts = if (handle != null) listOf("https://www.youtube.com/@$handle") else emptyList()
                artists.add(ChannelRef(chName, "https://www.youtube.com/channel/$chId", handle, alts))
            }
        } else {
            notes.add("YouTube page could not be loaded (HTTP ${r?.code ?: "error"}).")
        }
        if (artists.isEmpty()) {
            // Fallback: oEmbed only knows the title and the channel.
            val o = page("https://www.youtube.com/oembed?format=json&url=" + UrlUtil.enc("https://www.youtube.com/watch?v=$id"))
            if (o != null && o.code == 200) {
                val j = try { MiniJson.parse(o.body).asObj() } catch (e: Exception) { null }
                if (title.isBlank()) title = j.str("title") ?: ""
                val an = j.str("author_name")
                val au = j.str("author_url")
                if (!an.isNullOrBlank() && !au.isNullOrBlank()) {
                    val h = if (au.contains("/@")) au.substringAfter("/@").substringBefore('/') else null
                    artists.add(ChannelRef(an, au, h))
                }
            }
        }
        return PageData(title, desc, artists)
    }

    private fun linkifyUrls(raw: String): String =
        raw.replace("\r", "").replace(URL_IN_TEXT) { m -> "\"${m.value}\":[${m.value}]" }

    private fun linkifyBili(raw: String): String =
        raw.replace("\r", "").replace(BILI_DESC) { m ->
            val url = m.groups[1]?.value
            val bv = m.groups[2]?.value
            if (url != null) "\"$url\":[$url]" else if (bv != null) "\"$bv\":[https://www.bilibili.com/video/$bv]" else m.value
        }

    // ---- artist check (port of scanArtists) ----------------------------------------------------

    private fun lookup(block: () -> List<String>, notes: MutableList<String>): List<String> {
        return try {
            block()
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) throw e
            val msg = "Artist lookup failed: ${e.message}"
            if (msg !in notes) notes.add(msg)
            emptyList()
        } catch (e: Exception) {
            val msg = "Artist lookup failed: ${e.message}"
            if (msg !in notes) notes.add(msg)
            emptyList()
        }
    }

    private fun checkArtists(
        artists: List<ChannelRef>,
        registered: MutableSet<String>,
        missing: MutableList<MissingArtist>,
        notes: MutableList<String>
    ) {
        for (a in artists) {
            var results = lookup({ api.artistByUrl(a.url) }, notes)
            if (results.isEmpty()) {
                for (alt in a.altUrls) {
                    results = lookup({ api.artistByUrl(alt) }, notes)
                    if (results.isNotEmpty()) break
                }
            }
            if (results.isEmpty() && !a.handle.isNullOrBlank()) {
                val handle = a.handle
                results = lookup({ api.artistByName(handle) }, notes)
            }
            if (results.size > 3) results = emptyList()

            var extra: List<String>? = null
            if (results.isEmpty()) {
                extra = scrapeExtraLinks(a.url)
                for (link in extra) {
                    val r = lookup({ api.artistByUrl(link) }, notes)
                    if (r.isNotEmpty() && r.size <= 3) {
                        results = r
                        break
                    }
                }
            }

            if (results.isNotEmpty() && results.size <= 3) {
                registered.addAll(results)
            } else {
                missing.add(buildMissing(a, extra ?: scrapeExtraLinks(a.url)))
            }
        }
    }

    private fun buildMissing(a: ChannelRef, extra: List<String>): MissingArtist {
        var main = ""
        var other = ""
        if (a.name.isNotBlank() && a.name != "Unknown_Artist") {
            other = a.name.trim().replace(WS, "_")
            main = romanize(a.name.trim()).replace(WS, "_").trim('_', '-')
        }
        return MissingArtist(main, other, a.url, extra)
    }

    /** Links (X, Pixiv, Fanbox...) listed on a YouTube channel's About page. Other sites are not scraped. */
    private fun scrapeExtraLinks(channelUrl: String): List<String> {
        if (!channelUrl.contains("youtube.com")) return emptyList()
        val target = if (channelUrl.contains("/about")) channelUrl else "$channelUrl/about"
        val r = page(target) ?: return emptyList()
        if (r.code != 200) return emptyList()
        val html = r.body
        val links = LinkedHashSet<String>()

        val data = HtmlJson.extract(html, "ytInitialData").asObj()
        val text = if (data != null) MiniJson.stringify(data["metadata"]) + " " + MiniJson.stringify(data["header"]) else html

        for (m in REDIRECT.findAll(text)) {
            try {
                val decoded = URLDecoder.decode(m.groupValues[1], "UTF-8")
                if (!decoded.contains("google.com")) links.add(decoded)
            } catch (e: Exception) {
                // ignore malformed link
            }
        }
        if (data != null) {
            for (p in LINK_PATTERNS) {
                for (m in p.findAll(text)) {
                    val raw = m.value
                    if (raw.contains("twitter.com/", true) || raw.contains("x.com/", true)) {
                        val seg = raw.substringAfter(".com/").substringBefore('/').lowercase()
                        if (seg in GENERIC_PATHS) continue
                    }
                    links.add("https://" + raw.replace(Regex("^https?://", RegexOption.IGNORE_CASE), ""))
                }
            }
        }
        return links.toList()
    }

    // ---- romanisation (port of fetchRomanization) ----------------------------------------------

    fun romanize(text: String): String {
        if (!CJK.containsMatchIn(text) && ASCII_NAME.matches(text)) {
            return text.lowercase().replace(WS, "_")
        }
        val fallback = "check_reading_$text"
        return try {
            val r = http.get(TRANSLIT_URL + UrlUtil.enc(text), mapOf("User-Agent" to DESKTOP_UA))
            if (r.code != 200) return fallback
            val root = MiniJson.parse(r.body).asArr()
            val segments = root?.getOrNull(0).asArr().orEmpty()
            val parts = ArrayList<String>()
            for (seg in segments) {
                val s = seg.asArr()?.getOrNull(3) as? String
                if (!s.isNullOrBlank()) parts.add(s)
            }
            var roman = parts.joinToString(" ")
            roman = Normalizer.normalize(roman, Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
            roman = roman.lowercase().replace(Regex("[^a-z0-9\\s_-]"), "").trim().replace(WS, "_")
            if (roman.isBlank()) fallback else roman
        } catch (e: Exception) {
            fallback
        }
    }
}
