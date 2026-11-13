package com.andrecnuuy.assetcasino.core

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class StatRow(val label: String, val value: Double, val text: String)
class StatSection(val title: String, val rows: List<StatRow>, val note: String = "")
class StatsReport(val overview: List<Pair<String, String>>, val sections: List<StatSection>)

object StatsBuilder {

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun humanDuration(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, r) else String.format(Locale.US, "%dm %02ds", m, r)
    }

    fun date(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun pct(v: Int, total: Int): String =
        if (total <= 0) "" else String.format(Locale.US, " (%.1f%%)", 100.0 * v / total)

    private fun countRows(counts: Map<String, Int>, total: Int, limit: Int, otherLabel: String): List<StatRow> {
        val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val out = ArrayList<StatRow>()
        var rest = 0
        var restKinds = 0
        for ((i, e) in sorted.withIndex()) {
            if (i < limit) {
                out.add(StatRow(e.key, e.value.toDouble(), "${e.value}${pct(e.value, total)}"))
            } else {
                rest += e.value
                restKinds++
            }
        }
        if (rest > 0) out.add(StatRow("$otherLabel ($restKinds more)", rest.toDouble(), "$rest${pct(rest, total)}"))
        return out
    }

    private fun orderedRows(items: List<Pair<String, Int>>, total: Int): List<StatRow> =
        items.filter { it.second > 0 }.map { StatRow(it.first, it.second.toDouble(), "${it.second}${pct(it.second, total)}") }

    fun build(rows: List<AssetRow>, dao: DaoDb?, state: ScanState, nowSec: Long): StatsReport {
        val uploads = rows.size
        val overview = ArrayList<Pair<String, String>>()
        val sections = ArrayList<StatSection>()
        if (uploads == 0) {
            overview.add("Uploads in database" to "0")
            return StatsReport(overview, sections)
        }

        var assets = 0
        var totalSize = 0L
        var matome = 0
        var matomeAssets = 0
        val kindAssets = HashMap<String, Int>()
        val extAssets = HashMap<String, Int>()
        val kindSize = HashMap<String, Long>()
        var videoCount = 0
        var videoDur = 0.0
        var longestVideo = 0.0
        val resBuckets = arrayOf(0, 0, 0, 0, 0)
        var withRes = 0
        var oldest = Long.MAX_VALUE
        var newest = 0L
        val perYear = java.util.TreeMap<Int, Int>()
        val ageBuckets = IntArray(7)
        val catCounts = HashMap<String, Int>()
        var nonWeb = 0
        var nonWebBili = 0
        var nonWebYt = 0
        var daoAny = 0
        var daoArtist = 0
        var daoCopy = 0
        var daoChar = 0
        val zone = ZoneId.systemDefault()

        for (r in rows) {
            val e = r.e
            catCounts[r.category] = (catCounts[r.category] ?: 0) + 1
            if (e.media.size > 1) {
                matome++
                matomeAssets += e.media.size
            }
            for (m in e.media) {
                assets++
                totalSize += m.size
                val kind = Sources.kindOfExt(m.ext)
                kindAssets[kind.label] = (kindAssets[kind.label] ?: 0) + 1
                extAssets[m.ext.lowercase()] = (extAssets[m.ext.lowercase()] ?: 0) + 1
                kindSize[kind.label] = (kindSize[kind.label] ?: 0L) + m.size
                if (kind == MediaKind.VIDEO) {
                    videoCount++
                    videoDur += m.dur
                    if (m.dur > longestVideo) longestVideo = m.dur
                }
                if (kind == MediaKind.PHOTO && m.w > 0 && m.h > 0) {
                    withRes++
                    val side = maxOf(m.w, m.h)
                    val idx = when {
                        side < 1000 -> 0
                        side < 2000 -> 1
                        side < 3000 -> 2
                        side < 4000 -> 3
                        else -> 4
                    }
                    resBuckets[idx]++
                }
            }
            if (e.createdAt > 0) {
                if (e.createdAt < oldest) oldest = e.createdAt
                if (e.createdAt > newest) newest = e.createdAt
                val year = Instant.ofEpochSecond(e.createdAt).atZone(zone).year
                perYear[year] = (perYear[year] ?: 0) + 1
                val days = (nowSec - e.createdAt) / 86400
                val b = when {
                    days <= 30 -> 0
                    days <= 90 -> 1
                    days <= 180 -> 2
                    days <= 365 -> 3
                    days <= 730 -> 4
                    else -> 5
                }
                ageBuckets[b]++
            } else {
                ageBuckets[6]++
            }
            if (r.category == Sources.NON_WEB) {
                nonWeb++
                val v = VideoIds.parse(Sources.rawFileSource(e, 0))
                if (v?.platform == Platform.BILIBILI) nonWebBili++
                if (v?.platform == Platform.YOUTUBE) nonWebYt++
            }
            if (r.artists.isNotEmpty() || r.copyrights.isNotEmpty() || r.characters.isNotEmpty()) daoAny++
            if (r.artists.isNotEmpty()) daoArtist++
            if (r.copyrights.isNotEmpty()) daoCopy++
            if (r.characters.isNotEmpty()) daoChar++
        }

        overview.add("Uploads in database" to uploads.toString())
        overview.add("Media assets" to assets.toString())
        overview.add("Multi-asset uploads (matome)" to "$matome ($matomeAssets assets)")
        overview.add("Total file size" to humanSize(totalSize))
        if (oldest != Long.MAX_VALUE) {
            overview.add("Oldest upload" to date(oldest))
            overview.add("Newest upload" to date(newest))
        }
        overview.add(
            "Scan coverage" to
                if (state.complete) "complete (whole list scanned)" else "partial - run the scan again to finish"
        )
        if (videoCount > 0) {
            overview.add("Videos" to "$videoCount, ${humanDuration(videoDur)} total, longest ${humanDuration(longestVideo)}")
        }
        if (dao != null) {
            overview.add("DAO data" to "$daoAny of $uploads uploads have DAO tags")
        } else {
            overview.add("DAO data" to "not imported yet")
        }

        sections.add(StatSection("Source (uploads)", countRows(catCounts, uploads, 16, "Other sites")))

        sections.add(
            StatSection(
                "Media type (assets)",
                countRows(kindAssets, assets, 10, "Other")
            )
        )
        sections.add(StatSection("File extension (assets)", countRows(extAssets, assets, 12, "Other")))

        if (kindSize.isNotEmpty()) {
            val sizeRows = kindSize.entries.sortedByDescending { it.value }
                .map { StatRow(it.key, it.value.toDouble(), humanSize(it.value)) }
            sections.add(StatSection("Storage used by type", sizeRows))
        }

        if (nonWeb > 0) {
            val other = nonWeb - nonWebBili - nonWebYt
            sections.add(
                StatSection(
                    "Direct uploads (non-web)",
                    orderedRows(
                        listOf(
                            "Bilibili id in file name" to nonWebBili,
                            "YouTube id in file name" to nonWebYt,
                            "No recoverable source" to other
                        ),
                        nonWeb
                    ),
                    "$nonWeb direct uploads in total. Ones with a Bilibili/YouTube id can have their source and info rebuilt in Sharing mode."
                )
            )
        }

        sections.add(
            StatSection(
                "Backlog age (since upload)",
                orderedRows(
                    listOf(
                        "Up to 1 month" to ageBuckets[0],
                        "1 - 3 months" to ageBuckets[1],
                        "3 - 6 months" to ageBuckets[2],
                        "6 - 12 months" to ageBuckets[3],
                        "1 - 2 years" to ageBuckets[4],
                        "Over 2 years" to ageBuckets[5],
                        "Unknown date" to ageBuckets[6]
                    ),
                    uploads
                )
            )
        )

        if (perYear.isNotEmpty()) {
            sections.add(StatSection("Uploaded per year", orderedRows(perYear.entries.map { it.key.toString() to it.value }, uploads)))
        }

        if (withRes > 0) {
            sections.add(
                StatSection(
                    "Photo resolution (longest side)",
                    orderedRows(
                        listOf(
                            "Under 1000 px" to resBuckets[0],
                            "1000 - 1999 px" to resBuckets[1],
                            "2000 - 2999 px" to resBuckets[2],
                            "3000 - 3999 px" to resBuckets[3],
                            "4000 px and up" to resBuckets[4]
                        ),
                        withRes
                    )
                )
            )
        }

        if (dao != null) {
            sections.add(
                StatSection(
                    "DAO tag coverage (uploads)",
                    orderedRows(
                        listOf(
                            "With an artist tag" to daoArtist,
                            "With a copyright tag" to daoCopy,
                            "With a character tag" to daoChar,
                            "No DAO data at all" to (uploads - daoAny)
                        ),
                        uploads
                    ),
                    "Uploads without DAO data cannot be reached by tag filters. Re-import a fresh DAO backup after scanning them in DAO."
                )
            )
            for (type in TagType.values()) {
                val top = Casino.tagCounts(rows, type).take(12)
                if (top.isNotEmpty()) {
                    sections.add(
                        StatSection(
                            "Top ${type.label.lowercase()} tags",
                            top.map { StatRow(it.name, it.count.toDouble(), it.count.toString()) }
                        )
                    )
                }
            }
        }
        return StatsReport(overview, sections)
    }
}
