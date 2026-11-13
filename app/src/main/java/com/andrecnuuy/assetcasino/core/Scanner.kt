package com.andrecnuuy.assetcasino.core

class ScanProgress(val phase: String, val pages: Int, val added: Int, val total: Int)

/**
 * Scans my unposted uploads newest -> oldest and keeps a checkpoint:
 *  A) "new" phase: from the top until it reaches the newest id of the previous scan, then stops.
 *  B) "resume" phase: if an earlier scan was interrupted, continues below the oldest id already stored.
 * A brand new database simply runs phase B from the top.
 */
class Scanner(
    private val api: DanbooruApi,
    private val db: AssetDb,
    private val userId: Long,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun run(
        shouldStop: () -> Boolean,
        persist: () -> Unit,
        onProgress: (ScanProgress) -> Unit
    ): ScanHistoryItem {
        val startNewest = db.state.newestId
        var newest = db.state.newestId
        var oldest = db.state.oldestId
        var complete = db.state.complete
        val mode = when {
            startNewest == 0L -> "full scan"
            !complete -> "new + resume"
            else -> "new only"
        }
        var pages = 0
        var added = 0
        var result = "complete"
        var note = ""
        var stopped = false
        var limit = 200
        var useOnly = true

        // null = stop requested
        fun fetchPage(cursor: Long?): List<UploadEntry>? {
            while (true) {
                if (shouldStop()) return null
                try {
                    return api.uploadsPage(userId, cursor, limit, useOnly)
                } catch (e: ApiException) {
                    if (e.code == 400 || e.code == 422) {
                        val msg = e.message ?: ""
                        if (msg.contains("Too many included") && limit > 10) {
                            limit = maxOf(10, limit / 2)
                            continue
                        }
                        if (useOnly) {
                            useOnly = false
                            continue
                        }
                    }
                    throw e
                }
            }
        }

        try {
            // Phase A: new uploads above the checkpoint.
            if (startNewest > 0L) {
                var cursor: Long? = null
                var topSeen = 0L
                while (true) {
                    val page = fetchPage(cursor)
                    if (page == null) {
                        stopped = true
                        break
                    }
                    pages++
                    if (page.isEmpty()) {
                        // The whole list was walked without meeting the checkpoint: nothing older is left.
                        complete = true
                        break
                    }
                    var hit = false
                    for (e in page) {
                        if (e.id <= startNewest) {
                            hit = true
                            break
                        }
                        if (db.items.put(e.id, e) == null) added++
                        if (e.id > topSeen) topSeen = e.id
                    }
                    onProgress(ScanProgress("Looking for new uploads", pages, added, db.items.size))
                    if (hit) break
                    cursor = page.minOf { it.id }
                    if (pages % 10 == 0) persist()
                }
                if (!stopped && topSeen > newest) newest = topSeen
                db.state = ScanState(newest, oldest, complete)
                persist()
            }

            // Phase B: older uploads that earlier scans did not reach yet.
            if (!stopped && !complete) {
                var cursor: Long? = if (startNewest == 0L) null else oldest
                while (true) {
                    val page = fetchPage(cursor)
                    if (page == null) {
                        stopped = true
                        break
                    }
                    pages++
                    if (page.isEmpty()) {
                        complete = true
                        break
                    }
                    for (e in page) {
                        if (db.items.put(e.id, e) == null) added++
                    }
                    val top = page.maxOf { it.id }
                    val low = page.minOf { it.id }
                    if (newest == 0L) newest = top
                    oldest = low
                    cursor = low
                    db.state = ScanState(newest, oldest, false)
                    onProgress(ScanProgress("Scanning older uploads", pages, added, db.items.size))
                    if (pages % 10 == 0) persist()
                }
                db.state = ScanState(newest, oldest, complete)
            }
        } catch (e: Exception) {
            result = "error"
            note = e.message ?: e.javaClass.simpleName
        }

        if (stopped && result == "complete") result = "stopped"
        db.state = ScanState(newest.coerceAtLeast(db.state.newestId), if (oldest == 0L) db.state.oldestId else oldest, complete)
        val item = ScanHistoryItem(clock(), mode, pages, added, db.items.size, result, note)
        db.history.add(0, item)
        while (db.history.size > 30) db.history.removeAt(db.history.size - 1)
        persist()
        return item
    }
}

/** Removes uploads that have been posted since they were scanned. */
class Purger(private val api: DanbooruApi, private val db: AssetDb) {
    fun run(shouldStop: () -> Boolean, persist: () -> Unit, onProgress: (Int, Int, Int) -> Unit): Int {
        val ids = db.items.keys.sorted()
        var removed = 0
        var done = 0
        for (chunk in ids.chunked(120)) {
            if (shouldStop()) break
            val posted = api.postedAmong(chunk)
            for (id in posted) {
                if (db.items.remove(id) != null) removed++
            }
            done += chunk.size
            onProgress(done, ids.size, removed)
        }
        persist()
        return removed
    }
}
