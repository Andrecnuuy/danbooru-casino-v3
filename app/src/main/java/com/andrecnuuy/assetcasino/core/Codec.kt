package com.andrecnuuy.assetcasino.core

/** (De)serialisation of the asset database (also the export/import format) and of the stored DAO tags. */
object Codec {
    const val DB_FORMAT = "danbooru-asset-casino"
    const val DAO_FORMAT = "danbooru-asset-casino-dao"

    fun encodeDb(db: AssetDb, exportedAt: Long): String {
        val sb = StringBuilder(db.items.size * 220 + 1024)
        sb.append("{\"format\":").append('"').append(DB_FORMAT).append('"')
        sb.append(",\"v\":1,\"exportedAt\":").append(exportedAt)
        sb.append(",\"state\":{\"newest\":").append(db.state.newestId)
        sb.append(",\"oldest\":").append(db.state.oldestId)
        sb.append(",\"complete\":").append(if (db.state.complete) "true" else "false").append('}')
        sb.append(",\"history\":[")
        var first = true
        for (h in db.history) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"at\":").append(h.at)
            sb.append(",\"mode\":"); MiniJson.quote(h.mode, sb)
            sb.append(",\"pages\":").append(h.pages)
            sb.append(",\"added\":").append(h.added)
            sb.append(",\"total\":").append(h.total)
            sb.append(",\"result\":"); MiniJson.quote(h.result, sb)
            sb.append(",\"note\":"); MiniJson.quote(h.note, sb)
            sb.append('}')
        }
        sb.append("],\"uploads\":[")
        first = true
        for (u in db.items.values) {
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(u.id).append(',').append(u.createdAt).append(',')
            MiniJson.quote(u.source, sb)
            sb.append(",[")
            var fm = true
            for (m in u.media) {
                if (!fm) sb.append(',')
                fm = false
                sb.append('[').append(m.id).append(',').append(m.umaId).append(',')
                MiniJson.quote(m.ext, sb)
                sb.append(',').append(m.size).append(',').append(m.w).append(',').append(m.h).append(',')
                MiniJson.write(m.dur, sb)
                sb.append(','); MiniJson.quote(m.srcUrl, sb)
                sb.append(','); MiniJson.quote(m.pageUrl, sb)
                sb.append(']')
            }
            sb.append("]]")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun decodeDb(text: String): AssetDb {
        val root = MiniJson.parse(text).asObj()
            ?: throw IllegalArgumentException("Not a database file")
        if (root.str("format") != DB_FORMAT) throw IllegalArgumentException("Not a Danbooru Asset Casino database")
        val db = AssetDb()
        val st = root.obj("state")
        db.state = ScanState(st.long("newest") ?: 0L, st.long("oldest") ?: 0L, st.bool("complete") ?: false)
        for (h in root.arr("history").orEmpty()) {
            val o = h.asObj() ?: continue
            db.history.add(
                ScanHistoryItem(
                    at = o.long("at") ?: 0L,
                    mode = o.str("mode") ?: "",
                    pages = o.int("pages") ?: 0,
                    added = o.int("added") ?: 0,
                    total = o.int("total") ?: 0,
                    result = o.str("result") ?: "",
                    note = o.str("note") ?: ""
                )
            )
        }
        for (row in root.arr("uploads").orEmpty()) {
            val a = row.asArr() ?: continue
            if (a.size < 4) continue
            val id = a[0].asLong() ?: continue
            val media = ArrayList<MediaRef>()
            for (mr in a[3].asArr().orEmpty()) {
                val m = mr.asArr() ?: continue
                if (m.size < 9) continue
                media.add(
                    MediaRef(
                        id = m[0].asLong() ?: continue,
                        umaId = m[1].asLong() ?: 0L,
                        ext = m[2] as? String ?: "",
                        size = m[3].asLong() ?: 0L,
                        w = (m[4].asLong() ?: 0L).toInt(),
                        h = (m[5].asLong() ?: 0L).toInt(),
                        dur = m[6].asDouble() ?: 0.0,
                        srcUrl = m[7] as? String ?: "",
                        pageUrl = m[8] as? String ?: ""
                    )
                )
            }
            if (media.isEmpty()) continue
            db.items[id] = UploadEntry(id, a[1].asLong() ?: 0L, a[2] as? String ?: "", media)
        }
        return db
    }

    fun encodeDao(dao: DaoDb): String {
        val sb = StringBuilder(dao.entries.size * 90 + 256)
        sb.append("{\"format\":\"").append(DAO_FORMAT).append("\",\"v\":1,\"at\":").append(dao.importedAt)
        sb.append(",\"ver\":"); MiniJson.quote(dao.sourceVersion, sb)
        sb.append(",\"e\":[")
        var first = true
        for ((id, e) in dao.entries) {
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(id).append(',')
            MiniJson.quote(e.artist, sb)
            sb.append(','); MiniJson.quote(e.copyright.joinToString(" "), sb)
            sb.append(','); MiniJson.quote(e.character.joinToString(" "), sb)
            sb.append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun decodeDao(text: String): DaoDb {
        val root = MiniJson.parse(text).asObj() ?: throw IllegalArgumentException("Not a DAO tag file")
        if (root.str("format") != DAO_FORMAT) throw IllegalArgumentException("Not a DAO tag file")
        val map = HashMap<Long, DaoEntry>()
        for (row in root.arr("e").orEmpty()) {
            val a = row.asArr() ?: continue
            if (a.size < 4) continue
            val id = a[0].asLong() ?: continue
            map[id] = DaoEntry(
                artist = a[1] as? String ?: "",
                copyright = splitTags(a[2] as? String),
                character = splitTags(a[3] as? String)
            )
        }
        return DaoDb(map, root.long("at") ?: 0L, root.str("ver") ?: "")
    }

    private fun splitTags(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList() else s.split(' ').filter { it.isNotBlank() }
}
