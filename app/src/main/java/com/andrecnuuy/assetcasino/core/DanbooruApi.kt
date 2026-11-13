package com.andrecnuuy.assetcasino.core

import java.time.OffsetDateTime

class HttpResponse(val code: Int, val body: String, val finalUrl: String)

/** Blocking HTTP GET. The Android implementation adds Danbooru credentials for donmai.us hosts. */
interface Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

object UrlUtil {
    /** RFC 3986 percent-encoding (spaces become %20). */
    fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val plain = (c >= 'a'.code && c <= 'z'.code) || (c >= 'A'.code && c <= 'Z'.code) ||
                (c >= '0'.code && c <= '9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (plain) sb.append(c.toChar()) else sb.append('%').append(String.format("%02X", c))
        }
        return sb.toString()
    }
}

class DanbooruApi(
    private val http: Http,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val base = Links.BASE

    companion object {
        const val UPLOADS_ONLY =
            "id,source,created_at,media_asset_count," +
                "media_assets[id,file_ext,file_size,image_width,image_height,duration]," +
                "upload_media_assets[id,media_asset_id,source_url,page_url]"
    }

    private fun fetch(url: String): String {
        var attempt = 0
        while (true) {
            val r = http.get(url, mapOf("Accept" to "application/json"))
            val code = r.code
            if (code in 200..299) return r.body
            if (code == 429 || code >= 500) {
                attempt++
                if (attempt > 4) throw ApiException("Danbooru keeps answering HTTP $code - try again later", code)
                sleeper(1500L * attempt)
                continue
            }
            if (code == 401 || code == 403) {
                throw ApiException("Danbooru refused the credentials (HTTP $code). Check login and API key.", code)
            }
            throw ApiException("HTTP $code: ${r.body.take(300)}", code)
        }
    }

    fun profile(): Profile {
        val o = MiniJson.parse(fetch("$base/profile.json")).asObj()
            ?: throw ApiException("Unexpected profile response")
        val id = o.long("id") ?: throw ApiException("Profile has no id - are you logged in?")
        return Profile(id, o.str("name") ?: "?", o.str("level_string") ?: "")
    }

    /** One page of my completed, unposted uploads, newest first. [beforeId] = continue below this upload id. */
    fun uploadsPage(userId: Long, beforeId: Long?, limit: Int, useOnly: Boolean = true): List<UploadEntry> {
        val sb = StringBuilder("$base/uploads.json?")
        sb.append("search%5Buploader_id%5D=").append(userId)
        sb.append("&search%5Bis_posted%5D=false")
        sb.append("&search%5Bstatus%5D=completed")
        sb.append("&search%5Border%5D=id_desc")
        sb.append("&limit=").append(limit)
        if (beforeId != null) sb.append("&page=b").append(beforeId)
        if (useOnly) sb.append("&only=").append(UrlUtil.enc(UPLOADS_ONLY))
        return parseUploads(fetch(sb.toString()))
    }

    /** Which of [ids] are posted by now (used by "Purge posted"). */
    fun postedAmong(ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val url = "$base/uploads.json?search%5Bid%5D=" + ids.joinToString(",") +
            "&search%5Bis_posted%5D=true&limit=200&only=id"
        val arr = MiniJson.parse(fetch(url)).asArr() ?: return emptySet()
        val wanted = ids.toHashSet()
        val out = HashSet<Long>()
        for (x in arr) {
            val id = x.asObj().long("id") ?: continue
            if (id in wanted) out.add(id)
        }
        return out
    }

    fun uploadDetail(id: Long): UploadDetail {
        val o = MiniJson.parse(fetch("$base/uploads/$id.json")).asObj()
            ?: throw ApiException("Unexpected upload response")
        val assets = ArrayList<AssetDetail>()
        for (u in o.arr("upload_media_assets").orEmpty().mapNotNull { it.asObj() }.sortedBy { it.long("id") ?: 0L }) {
            val ma = u.obj("media_asset") ?: continue
            val mid = ma.long("id") ?: u.long("media_asset_id") ?: continue
            val variants = ArrayList<Variant>()
            for (v in ma.arr("variants").orEmpty()) {
                val vo = v.asObj() ?: continue
                val url = vo.str("url") ?: continue
                variants.add(Variant(vo.str("type") ?: "", url, vo.int("width") ?: 0, vo.int("height") ?: 0, vo.str("file_ext") ?: ""))
            }
            val postId = ma.obj("post").long("id")
            assets.add(AssetDetail(u.long("id") ?: 0L, mid, ma.str("file_ext") ?: "", ma.long("file_size") ?: 0L, variants, postId))
        }
        return UploadDetail(id, o.str("status") ?: "", assets)
    }

    /** Names of artists matching a raw query string such as "search[url_matches]=...". */
    fun artistSearch(query: String): List<String> {
        val arr = MiniJson.parse(fetch("$base/artists.json?$query&limit=10&only=id,name")).asArr() ?: return emptyList()
        val out = ArrayList<String>()
        for (x in arr) {
            val n = x.asObj().str("name")
            if (!n.isNullOrBlank()) out.add(n)
        }
        return out
    }

    fun artistByUrl(url: String): List<String> = artistSearch("search%5Burl_matches%5D=" + UrlUtil.enc(url))
    fun artistByName(name: String): List<String> = artistSearch("search%5Bany_name_matches%5D=" + UrlUtil.enc(name))

    internal fun parseUploads(body: String): List<UploadEntry> {
        val arr = MiniJson.parse(body).asArr() ?: throw ApiException("Unexpected uploads response (not a list)")
        val out = ArrayList<UploadEntry>(arr.size)
        for (item in arr) {
            val o = item.asObj() ?: continue
            val id = o.long("id") ?: continue
            val source = o.str("source") ?: ""
            val mediaById = HashMap<Long, JObj>()
            for (m in o.arr("media_assets").orEmpty()) {
                val mo = m.asObj() ?: continue
                val mid = mo.long("id") ?: continue
                mediaById[mid] = mo
            }
            val refs = ArrayList<MediaRef>()
            val seen = HashSet<Long>()
            val umas = o.arr("upload_media_assets").orEmpty().mapNotNull { it.asObj() }.sortedBy { it.long("id") ?: 0L }
            for (u in umas) {
                val nested = u.obj("media_asset")
                val mid = u.long("media_asset_id") ?: nested.long("id") ?: continue
                if (!seen.add(mid)) continue
                val mo = nested ?: mediaById[mid] ?: continue
                refs.add(mediaRef(mo, mid, u.long("id") ?: 0L, u.str("source_url") ?: "", u.str("page_url") ?: ""))
            }
            if (refs.isEmpty()) {
                for ((mid, mo) in mediaById) {
                    refs.add(mediaRef(mo, mid, 0L, source, ""))
                }
            }
            if (refs.isEmpty()) continue
            out.add(UploadEntry(id, parseTime(o.str("created_at")), source, refs))
        }
        return out
    }

    private fun mediaRef(mo: JObj, mid: Long, umaId: Long, srcUrl: String, pageUrl: String) = MediaRef(
        id = mid,
        umaId = umaId,
        ext = mo.str("file_ext") ?: "",
        size = mo.long("file_size") ?: 0L,
        w = mo.int("image_width") ?: 0,
        h = mo.int("image_height") ?: 0,
        dur = mo.dbl("duration") ?: 0.0,
        srcUrl = srcUrl,
        pageUrl = pageUrl
    )

    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(s).toEpochSecond()
        } catch (e: Exception) {
            0L
        }
    }
}
