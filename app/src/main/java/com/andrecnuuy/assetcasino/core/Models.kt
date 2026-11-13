package com.andrecnuuy.assetcasino.core

/** One media asset inside an upload. */
data class MediaRef(
    val id: Long,          // media asset id  (danbooru.donmai.us/media_assets/<id>)
    val umaId: Long,       // upload_media_asset id (used for the per-asset upload page of multi-asset uploads)
    val ext: String,
    val size: Long,
    val w: Int,
    val h: Int,
    val dur: Double,       // seconds, 0 when not a video
    val srcUrl: String,    // upload_media_asset.source_url  (file://name for direct uploads)
    val pageUrl: String    // upload_media_asset.page_url ("" when none)
)

/** One of my uploads (one entry in the casino pool). A "matome" has several media assets. */
data class UploadEntry(
    val id: Long,          // upload id (danbooru.donmai.us/uploads/<id>)
    val createdAt: Long,   // epoch seconds, 0 if unknown
    val source: String,    // upload.source ("" when none)
    val media: List<MediaRef>
)

enum class MediaKind(val label: String) {
    PHOTO("Photos"),
    VIDEO("Videos"),
    ANIMATED("Animated (GIF / ugoira)")
}

enum class TagType(val label: String) {
    ARTIST("Artist"),
    COPYRIGHT("Copyright"),
    CHARACTER("Character")
}

/**
 * Checkpoint of the media asset scan. The database always covers the contiguous id range
 * [oldestId, newestId] of my unposted uploads; [complete] means oldestId is the very end of the list.
 */
data class ScanState(
    val newestId: Long = 0,
    val oldestId: Long = 0,
    val complete: Boolean = false
)

data class ScanHistoryItem(
    val at: Long,          // epoch millis
    val mode: String,
    val pages: Int,
    val added: Int,
    val total: Int,
    val result: String,
    val note: String
)

class AssetDb {
    val items = LinkedHashMap<Long, UploadEntry>()
    var state = ScanState()
    val history = ArrayList<ScanHistoryItem>()

    fun clear() {
        items.clear()
        state = ScanState()
        history.clear()
    }
}

/** What DAO knows about one upload. */
class DaoEntry(
    val artist: String,            // registered artist tag ("" if unknown / unregistered)
    val copyright: List<String>,
    val character: List<String>
)

class DaoDb(
    val entries: Map<Long, DaoEntry>,   // key = upload id (danbooru.donmai.us/uploads/<id>)
    val importedAt: Long,
    val sourceVersion: String
)

class Profile(val id: Long, val name: String, val level: String)

class Variant(val type: String, val url: String, val w: Int, val h: Int, val ext: String)

class AssetDetail(
    val umaId: Long,
    val mediaId: Long,
    val ext: String,
    val size: Long,
    val variants: List<Variant>,
    val postId: Long?
)

class UploadDetail(val id: Long, val status: String, val assets: List<AssetDetail>)

class ApiException(message: String, val code: Int = 0) : RuntimeException(message)
