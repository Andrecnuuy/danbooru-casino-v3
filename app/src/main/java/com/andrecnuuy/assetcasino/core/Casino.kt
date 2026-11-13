package com.andrecnuuy.assetcasino.core

import kotlin.random.Random

/** An upload plus everything the filters need, computed once per database / DAO change. */
class AssetRow(
    val e: UploadEntry,
    val category: String,
    val kinds: Set<MediaKind>,
    val artists: List<String>,
    val copyrights: List<String>,
    val characters: List<String>
)

class Filters(
    val categories: Set<String> = emptySet(),
    val kinds: Set<MediaKind> = emptySet(),
    val artists: Set<String> = emptySet(),
    val copyrights: Set<String> = emptySet(),
    val characters: Set<String> = emptySet()
) {
    val isEmpty: Boolean
        get() = categories.isEmpty() && kinds.isEmpty() && artists.isEmpty() && copyrights.isEmpty() && characters.isEmpty()
}

class TagCount(val name: String, val count: Int)

object Casino {

    /** Rows sorted oldest -> newest (by upload id). */
    fun buildRows(entries: Collection<UploadEntry>, dao: DaoDb?): List<AssetRow> {
        val sorted = entries.sortedBy { it.id }
        val rows = ArrayList<AssetRow>(sorted.size)
        for (e in sorted) {
            val artists = LinkedHashSet<String>()
            val cps = LinkedHashSet<String>()
            val chs = LinkedHashSet<String>()
            if (dao != null) {
                // DAO keys everything by UPLOAD id (the id on its /uploads cards), not by media asset id.
                val d = dao.entries[e.id]
                if (d != null) {
                    if (d.artist.isNotEmpty()) artists.add(d.artist)
                    cps.addAll(d.copyright)
                    chs.addAll(d.character)
                }
            }
            rows.add(AssetRow(e, Sources.categoryOf(e), Sources.kindsOf(e), artists.toList(), cps.toList(), chs.toList()))
        }
        return rows
    }

    /** OR inside one group, AND between groups. */
    fun applyFilters(rows: List<AssetRow>, f: Filters): List<AssetRow> {
        if (f.isEmpty) return rows
        val out = ArrayList<AssetRow>()
        for (r in rows) {
            if (f.categories.isNotEmpty() && r.category !in f.categories) continue
            if (f.kinds.isNotEmpty() && r.kinds.none { it in f.kinds }) continue
            if (f.artists.isNotEmpty() && r.artists.none { it in f.artists }) continue
            if (f.copyrights.isNotEmpty() && r.copyrights.none { it in f.copyrights }) continue
            if (f.characters.isNotEmpty() && r.characters.none { it in f.characters }) continue
            out.add(r)
        }
        return out
    }

    /** Tags of one type, highest count first (counted once per upload). */
    fun tagCounts(rows: List<AssetRow>, type: TagType): List<TagCount> {
        val counts = HashMap<String, Int>()
        for (r in rows) {
            val list = when (type) {
                TagType.ARTIST -> r.artists
                TagType.COPYRIGHT -> r.copyrights
                TagType.CHARACTER -> r.characters
            }
            for (t in list) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.entries
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
    }

    fun categoryCounts(rows: List<AssetRow>): List<TagCount> {
        val counts = HashMap<String, Int>()
        for (r in rows) counts[r.category] = (counts[r.category] ?: 0) + 1
        return counts.entries
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
    }

    /** Seeded Fisher-Yates permutation of 0 until n: the "shuffled numbering". */
    fun permutation(n: Int, seed: Long): IntArray {
        val a = IntArray(n) { it }
        val rnd = Random(seed)
        for (i in n - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a
    }

    /** Number 1..n (as typed in the casino) -> index into the pool. */
    fun poolIndex(number: Int, n: Int, perm: IntArray?): Int {
        require(number in 1..n) { "number out of range" }
        val k = number - 1
        return if (perm != null && perm.size == n) perm[k] else k
    }

    /** Asset shown first: the first one matching the media filter, else the first not-yet-posted, else 0. */
    fun defaultAssetIndex(e: UploadEntry, kinds: Set<MediaKind>): Int {
        if (kinds.isNotEmpty()) {
            val i = e.media.indexOfFirst { Sources.kindOfExt(it.ext) in kinds }
            if (i >= 0) return i
        }
        return 0
    }
}

class PreviewSpec(val url: String, val isVideo: Boolean)

/** Chooses what to show / download for an asset from the variants the API returned. */
object PreviewPick {
    private const val BIG_ORIGINAL = 10L * 1024 * 1024
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v")

    private fun variant(a: AssetDetail, type: String): Variant? = a.variants.firstOrNull { it.type == type }

    fun preview(a: AssetDetail): PreviewSpec? {
        val original = variant(a, "original")
        val sample = variant(a, "sample")
        val v720 = variant(a, "720x720")
        val v360 = variant(a, "360x360")
        val still = v720 ?: v360
        return when (Sources.kindOfExt(a.ext)) {
            MediaKind.VIDEO -> {
                val v = original ?: a.variants.firstOrNull { it.ext.lowercase() in VIDEO_EXT }
                if (v != null) PreviewSpec(v.url, true) else still?.let { PreviewSpec(it.url, false) }
            }
            MediaKind.ANIMATED -> {
                if (sample != null && sample.ext.lowercase() in VIDEO_EXT) PreviewSpec(sample.url, true)
                else (still ?: sample ?: original)?.let { PreviewSpec(it.url, false) }
            }
            MediaKind.PHOTO -> {
                val pick = sample ?: (if (original != null && a.size in 1 until BIG_ORIGINAL) original else null) ?: still ?: original
                pick?.let { PreviewSpec(it.url, false) }
            }
        }
    }

    /** The file that the Download button saves. */
    fun download(a: AssetDetail): Variant? = variant(a, "original") ?: a.variants.lastOrNull()
}

object Links {
    const val BASE = "https://danbooru.donmai.us"

    /** Upload page; for a multi-asset upload the page of that specific asset. */
    fun uploadPage(u: UploadEntry, assetIndex: Int): String {
        val m = u.media.getOrNull(assetIndex)
        return if (u.media.size > 1 && m != null && m.umaId > 0) "$BASE/uploads/${u.id}/assets/${m.umaId}" else "$BASE/uploads/${u.id}"
    }

    fun mediaAsset(m: MediaRef): String = "$BASE/media_assets/${m.id}"

    /** prefix / link / (optional source) / affix, one per line, blanks skipped. */
    fun shareMessage(prefix: String, link: String, source: String?, affix: String): String {
        val parts = ArrayList<String>()
        if (prefix.isNotBlank()) parts.add(prefix.trim())
        parts.add(link)
        if (!source.isNullOrBlank()) parts.add(source)
        if (affix.isNotBlank()) parts.add(affix.trim())
        return parts.joinToString("\n")
    }
}
