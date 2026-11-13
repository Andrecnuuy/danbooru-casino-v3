package com.andrecnuuy.assetcasino.core

/**
 * Reads a Danbooru Asset Organizer (DAO) backup. DAO stores everything keyed by UPLOAD id
 * (the data-id of the cards on /uploads, fetched through uploads.json?search[id]=):
 *  - "artist": { id: { name, unreg, ... } }  (name is a real artist tag only when unreg == false)
 *  - "tag":    { id: { copyright: [], character: [], ... } }
 * Both maps are stored as JSON *strings* inside the backup, but plain objects are accepted too.
 */
object DaoImport {

    fun parse(text: String, now: Long): DaoDb {
        val root = MiniJson.parse(text).asObj() ?: throw IllegalArgumentException("Not a DAO backup file")
        if (!root.containsKey("tag") && !root.containsKey("artist")) {
            throw IllegalArgumentException("This file has no DAO tag data (expected \"tag\" and \"artist\" caches)")
        }
        val artistMap = unwrap(root["artist"])
        val tagMap = unwrap(root["tag"])

        val out = HashMap<Long, DaoEntry>(maxOf(artistMap.size, tagMap.size) * 2)
        val ids = HashSet<String>(artistMap.keys)
        ids.addAll(tagMap.keys)
        for (key in ids) {
            val id = key.toLongOrNull() ?: continue
            val a = artistMap[key].asObj()
            val t = tagMap[key].asObj()
            val unreg = a.bool("unreg") ?: false
            val name = if (unreg) "" else (a.str("name") ?: "").trim()
            val copyright = stringList(t.arr("copyright"))
            val character = stringList(t.arr("character"))
            if (name.isEmpty() && copyright.isEmpty() && character.isEmpty()) continue
            out[id] = DaoEntry(name, copyright, character)
        }
        return DaoDb(out, now, (root["version"] as? String) ?: "")
    }

    private fun unwrap(v: Any?): JObj {
        return when (v) {
            is String -> MiniJson.parse(v).asObj() ?: emptyMap()
            is Map<*, *> -> v.asObj() ?: emptyMap()
            else -> emptyMap()
        }
    }

    private fun stringList(a: JArr?): List<String> {
        if (a == null || a.isEmpty()) return emptyList()
        val r = ArrayList<String>(a.size)
        for (x in a) if (x is String && x.isNotBlank()) r.add(x)
        return r
    }
}
