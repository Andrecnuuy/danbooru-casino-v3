package com.andrecnuuy.assetcasino

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.andrecnuuy.assetcasino.core.ApiException
import com.andrecnuuy.assetcasino.core.AssetDb
import com.andrecnuuy.assetcasino.core.AssetDetail
import com.andrecnuuy.assetcasino.core.AssetRow
import com.andrecnuuy.assetcasino.core.Casino
import com.andrecnuuy.assetcasino.core.Codec
import com.andrecnuuy.assetcasino.core.DanbooruApi
import com.andrecnuuy.assetcasino.core.DaoDb
import com.andrecnuuy.assetcasino.core.DaoImport
import com.andrecnuuy.assetcasino.core.Filters
import com.andrecnuuy.assetcasino.core.Links
import com.andrecnuuy.assetcasino.core.MediaKind
import com.andrecnuuy.assetcasino.core.MediaRef
import com.andrecnuuy.assetcasino.core.PreviewPick
import com.andrecnuuy.assetcasino.core.PreviewSpec
import com.andrecnuuy.assetcasino.core.Purger
import com.andrecnuuy.assetcasino.core.ScanHistoryItem
import com.andrecnuuy.assetcasino.core.ScanState
import com.andrecnuuy.assetcasino.core.Scanner
import com.andrecnuuy.assetcasino.core.Sources
import com.andrecnuuy.assetcasino.core.StatsBuilder
import com.andrecnuuy.assetcasino.core.StatsReport
import com.andrecnuuy.assetcasino.core.TagType
import com.andrecnuuy.assetcasino.core.UploadDetail
import com.andrecnuuy.assetcasino.core.VideoIds
import com.andrecnuuy.assetcasino.core.VideoInfo
import com.andrecnuuy.assetcasino.core.VideoInfoService
import com.andrecnuuy.assetcasino.core.VideoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.random.Random

enum class Mode { UPLOAD, SHARING }

enum class Screen { HOME, STATS }

class Winner(val number: Int, val row: AssetRow, val assetIndex: Int) {
    val asset: MediaRef
        get() = row.e.media[assetIndex.coerceIn(0, row.e.media.size - 1)]
}

sealed interface DetailState {
    object Idle : DetailState
    object Loading : DetailState
    class Ready(val detail: UploadDetail) : DetailState
    class Posted(val postId: Long) : DetailState
    class Failed(val message: String) : DetailState
}

sealed interface VideoState {
    object Idle : VideoState
    object Loading : VideoState
    class Done(val info: VideoInfo) : VideoState
    class Failed(val message: String) : VideoState
}

class PendingImport(val db: AssetDb)

/** Video id recovered from a direct upload's file name (only for non-web sources). */
fun videoRefOf(w: Winner): VideoRef? {
    if (Sources.webSource(w.row.e, w.assetIndex) != null) return null
    return VideoIds.parse(Sources.rawFileSource(w.row.e, w.assetIndex))
}

/** The source link offered in Sharing mode: the web source, or the rebuilt Bilibili/YouTube link. */
fun shareSourceOf(w: Winner): String? = Sources.webSource(w.row.e, w.assetIndex) ?: videoRefOf(w)?.url

class CasinoViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = Storage(app)
    private val auth = AuthStore()
    val client: OkHttpClient = buildClient(auth)
    private val http = AndroidHttp(client)
    private val api = DanbooruApi(http)

    val imageLoader: ImageLoader by lazy { ImageLoader.Builder(app).okHttpClient(client).crossfade(true).build() }
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    fun toast(msg: String) {
        toasts.tryEmit(msg)
    }

    // ---- credentials ---------------------------------------------------------------------------

    var login by mutableStateOf(storage.getString("login", ""))
        private set
    var apiKey by mutableStateOf(storage.getString("api_key", ""))
        private set
    var accountText by mutableStateOf("")
        private set
    var verifying by mutableStateOf(false)
        private set
    private var userId: Long = storage.getLong("user_id", 0L)

    fun updateLogin(v: String) {
        login = v
    }

    fun updateApiKey(v: String) {
        apiKey = v
    }

    fun saveCredentials() {
        auth.login = login.trim()
        auth.apiKey = apiKey.trim()
        storage.putString("login", login.trim())
        storage.putString("api_key", apiKey.trim())
        userId = 0L
        storage.putLong("user_id", 0L)
        verify()
    }

    private fun verify() {
        if (auth.login.isBlank() || auth.apiKey.isBlank()) {
            accountText = "Enter both your login and API key."
            return
        }
        verifying = true
        accountText = "Checking..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = api.profile()
                userId = p.id
                storage.putLong("user_id", p.id)
                accountText = "Signed in as ${p.name} (${p.level})"
            } catch (e: Exception) {
                accountText = "Could not verify: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                verifying = false
            }
        }
    }

    // ---- database ------------------------------------------------------------------------------

    private var db = AssetDb()
    var rows by mutableStateOf<List<AssetRow>>(emptyList())
        private set
    var dbCount by mutableStateOf(0)
        private set
    var scanState by mutableStateOf(ScanState())
        private set
    var history by mutableStateOf<List<ScanHistoryItem>>(emptyList())
        private set
    var dao by mutableStateOf<DaoDb?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set
    var busyLabel by mutableStateOf("")
        private set
    var progressText by mutableStateOf("")
        private set
    @Volatile
    private var stopRequested = false

    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    var confirmClear by mutableStateOf(false)
        private set

    var screen by mutableStateOf(Screen.HOME)
        private set
    var statsReport by mutableStateOf<StatsReport?>(null)
        private set

    // ---- filters / casino state ----------------------------------------------------------------

    private fun loadSet(key: String): Set<String> =
        storage.getString(key, "").split('\n').filter { it.isNotBlank() }.toSet()

    private fun saveSet(key: String, s: Set<String>) {
        storage.putString(key, s.joinToString("\n"))
    }

    var selCategories by mutableStateOf(loadSet("f_cat"))
        private set
    var selKinds by mutableStateOf(
        loadSet("f_kind").mapNotNull { n -> MediaKind.values().firstOrNull { it.name == n } }.toSet()
    )
        private set
    var selArtists by mutableStateOf(loadSet("f_artist"))
        private set
    var selCopyrights by mutableStateOf(loadSet("f_copy"))
        private set
    var selCharacters by mutableStateOf(loadSet("f_char"))
        private set

    var mode by mutableStateOf(if (storage.getString("mode", "UPLOAD") == "SHARING") Mode.SHARING else Mode.UPLOAD)
        private set
    var shuffle by mutableStateOf(storage.getBool("shuffle", false))
        private set
    var seed by mutableStateOf(storage.getLong("seed", Random.nextLong()))
        private set
    var numberText by mutableStateOf("")
        private set
    var prefix by mutableStateOf(storage.getString("prefix", ""))
        private set
    var affix by mutableStateOf(storage.getString("affix", ""))
        private set
    var includeSource by mutableStateOf(storage.getBool("include_source", false))
        private set

    val filters by derivedStateOf { Filters(selCategories, selKinds, selArtists, selCopyrights, selCharacters) }
    val pool: List<AssetRow> by derivedStateOf { Casino.applyFilters(rows, filters) }
    val perm: IntArray? by derivedStateOf { if (shuffle) Casino.permutation(pool.size, seed) else null }

    val categoryOptions by derivedStateOf { Casino.categoryCounts(rows) }
    val artistOptions by derivedStateOf { Casino.tagCounts(rows, TagType.ARTIST) }
    val copyrightOptions by derivedStateOf { Casino.tagCounts(rows, TagType.COPYRIGHT) }
    val characterOptions by derivedStateOf { Casino.tagCounts(rows, TagType.CHARACTER) }

    var winner by mutableStateOf<Winner?>(null)
        private set
    var detail by mutableStateOf<DetailState>(DetailState.Idle)
        private set
    var rolling by mutableStateOf(false)
        private set
    var rollingText by mutableStateOf("")
        private set

    var preview by mutableStateOf<PreviewSpec?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var downloadStatus by mutableStateOf("")
        private set

    var videoState by mutableStateOf<VideoState>(VideoState.Idle)
        private set
    var videoText by mutableStateOf("")
        private set

    init {
        auth.login = login.trim()
        auth.apiKey = apiKey.trim()
        viewModelScope.launch(Dispatchers.IO) {
            val d = storage.loadDao()
            if (d != null) dao = d
            val stored = storage.loadDb()
            if (stored != null) db = stored
            publish()
            loaded = true
        }
    }

    private fun publish() {
        val r = Casino.buildRows(db.items.values, dao)
        rows = r
        dbCount = db.items.size
        scanState = db.state
        history = db.history.toList()
    }

    // ---- busy tasks ----------------------------------------------------------------------------

    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (busy) {
            toast("Another task is still running")
            return
        }
        busy = true
        busyLabel = label
        progressText = ""
        stopRequested = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                toast("$label failed: not enough memory for this file")
            } catch (e: Exception) {
                toast("$label failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                busyLabel = ""
                progressText = ""
            }
        }
    }

    fun requestStop() {
        stopRequested = true
        toast("Stopping after the current step...")
    }

    fun startScan() {
        if (auth.login.isBlank() || auth.apiKey.isBlank()) {
            toast("Enter your Danbooru login and API key first")
            return
        }
        runBusy("Scanning media assets") {
            if (userId == 0L) {
                val p = api.profile()
                userId = p.id
                storage.putLong("user_id", p.id)
                accountText = "Signed in as ${p.name} (${p.level})"
            }
            val item = Scanner(api, db, userId).run(
                shouldStop = { stopRequested },
                persist = { storage.saveDb(db) },
                onProgress = { p ->
                    progressText = "${p.phase}... page ${p.pages}, +${p.added} new, ${p.total} in database"
                }
            )
            publish()
            when (item.result) {
                "complete" -> toast("Scan complete: +${item.added} new, ${item.total} total")
                "stopped" -> toast("Scan stopped: +${item.added} new (progress saved)")
                else -> toast("Scan error: ${item.note} (progress saved)")
            }
        }
    }

    fun purgePosted() {
        if (auth.login.isBlank() || auth.apiKey.isBlank()) {
            toast("Enter your Danbooru login and API key first")
            return
        }
        runBusy("Purging posted uploads") {
            val removed = Purger(api, db).run(
                shouldStop = { stopRequested },
                persist = { storage.saveDb(db) },
                onProgress = { done, total, removedSoFar ->
                    progressText = "Checked $done of $total, $removedSoFar posted so far"
                }
            )
            publish()
            toast("Removed $removed uploads that have been posted")
        }
    }

    fun askClear() {
        confirmClear = true
    }

    fun cancelClear() {
        confirmClear = false
    }

    fun clearDb() {
        confirmClear = false
        runBusy("Clearing database") {
            db = AssetDb()
            storage.saveDb(db)
            winner = null
            detail = DetailState.Idle
            publish()
            toast("Database cleared")
        }
    }

    // ---- export / import -----------------------------------------------------------------------

    private fun readText(uri: Uri): String {
        val ins = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot read that file")
        return ins.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun exportDb(uri: Uri) {
        runBusy("Exporting database") {
            val text = Codec.encodeDb(db, System.currentTimeMillis())
            val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot write to that location")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast("Exported ${db.items.size} uploads")
        }
    }

    fun importDb(uri: Uri) {
        runBusy("Reading database file") {
            val imported = Codec.decodeDb(readText(uri))
            pendingImport = PendingImport(imported)
        }
    }

    fun cancelImport() {
        pendingImport = null
    }

    fun confirmImport() {
        val p = pendingImport ?: return
        pendingImport = null
        runBusy("Importing database") {
            db = p.db
            storage.saveDb(db)
            winner = null
            detail = DetailState.Idle
            publish()
            toast("Imported ${db.items.size} uploads")
        }
    }

    fun importDao(uri: Uri) {
        runBusy("Importing DAO database") {
            val d = DaoImport.parse(readText(uri), System.currentTimeMillis())
            storage.saveDao(d)
            dao = d
            publish()
            toast("DAO database imported: tags for ${d.entries.size} assets")
        }
    }

    fun openStats() {
        if (busy) {
            toast("Wait for the running task to finish")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val rep = StatsBuilder.build(rows, dao, scanState, System.currentTimeMillis() / 1000)
            statsReport = rep
            screen = Screen.STATS
        }
    }

    fun closeStats() {
        screen = Screen.HOME
    }

    // ---- filters -------------------------------------------------------------------------------

    private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x

    fun toggleCategory(name: String) {
        selCategories = selCategories.toggle(name)
        saveSet("f_cat", selCategories)
    }

    fun toggleKind(kind: MediaKind) {
        selKinds = selKinds.toggle(kind)
        saveSet("f_kind", selKinds.map { it.name }.toSet())
    }

    fun toggleTag(type: TagType, name: String) {
        when (type) {
            TagType.ARTIST -> {
                selArtists = selArtists.toggle(name)
                saveSet("f_artist", selArtists)
            }
            TagType.COPYRIGHT -> {
                selCopyrights = selCopyrights.toggle(name)
                saveSet("f_copy", selCopyrights)
            }
            TagType.CHARACTER -> {
                selCharacters = selCharacters.toggle(name)
                saveSet("f_char", selCharacters)
            }
        }
    }

    fun selectedTags(type: TagType): Set<String> = when (type) {
        TagType.ARTIST -> selArtists
        TagType.COPYRIGHT -> selCopyrights
        TagType.CHARACTER -> selCharacters
    }

    fun clearFilters() {
        selCategories = emptySet()
        selKinds = emptySet()
        selArtists = emptySet()
        selCopyrights = emptySet()
        selCharacters = emptySet()
        saveSet("f_cat", emptySet())
        saveSet("f_kind", emptySet())
        saveSet("f_artist", emptySet())
        saveSet("f_copy", emptySet())
        saveSet("f_char", emptySet())
    }

    // ---- casino --------------------------------------------------------------------------------

    fun changeMode(m: Mode) {
        mode = m
        storage.putString("mode", m.name)
    }

    fun changeShuffle(on: Boolean) {
        shuffle = on
        storage.putBool("shuffle", on)
    }

    fun reshuffle() {
        seed = Random.nextLong()
        storage.putLong("seed", seed)
        toast("Numbering reshuffled")
    }

    fun updateNumber(t: String) {
        numberText = t
    }

    fun randomizeNumber() {
        val n = pool.size
        if (n == 0) {
            toast("The pool is empty")
            return
        }
        numberText = Random.nextInt(1, n + 1).toString()
    }

    fun updatePrefix(v: String) {
        prefix = v
        storage.putString("prefix", v)
    }

    fun updateAffix(v: String) {
        affix = v
        storage.putString("affix", v)
    }

    fun changeIncludeSource(on: Boolean) {
        includeSource = on
        storage.putBool("include_source", on)
    }

    fun gamba() {
        if (rolling || busy) return
        val p = pool
        val n = p.size
        if (n == 0) {
            toast("Nothing to pick from - the pool is empty")
            return
        }
        val typed = numberText.trim().toIntOrNull()
        if (numberText.isNotBlank() && (typed == null || typed !in 1..n)) {
            toast("Pick a number from 1 to $n")
            return
        }
        val pm = perm
        val kinds = selKinds
        viewModelScope.launch {
            rolling = true
            winner = null
            detail = DetailState.Idle
            videoState = VideoState.Idle
            preview = null
            downloadStatus = ""
            repeat(14) {
                rollingText = "#" + Random.nextInt(1, n + 1)
                delay(55)
            }
            val chosen = typed ?: Random.nextInt(1, n + 1)
            numberText = chosen.toString()
            val row = p[Casino.poolIndex(chosen, n, pm)]
            winner = Winner(chosen, row, Casino.defaultAssetIndex(row.e, kinds))
            rolling = false
            loadDetail(row)
        }
    }

    fun selectAsset(index: Int) {
        val w = winner ?: return
        winner = Winner(w.number, w.row, index)
        videoState = VideoState.Idle
        downloadStatus = ""
    }

    private fun loadDetail(row: AssetRow) {
        detail = DetailState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val d = api.uploadDetail(row.e.id)
                withContext(Dispatchers.Main) { applyDetail(row, d) }
            } catch (e: ApiException) {
                if (e.code == 404) {
                    removeUploadAsync(row.e.id)
                    detail = DetailState.Failed("This upload no longer exists on Danbooru. It was removed from your database; gamba again.")
                } else {
                    detail = DetailState.Failed(e.message ?: "Could not load the asset details")
                }
            } catch (e: Exception) {
                detail = DetailState.Failed("Could not load the asset details: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun applyDetail(row: AssetRow, d: UploadDetail) {
        val assets = d.assets
        if (assets.isNotEmpty() && assets.all { it.postId != null }) {
            removeUploadAsync(row.e.id)
            detail = DetailState.Posted(assets.first().postId ?: 0L)
            return
        }
        detail = DetailState.Ready(d)
        val w = winner
        if (w != null && w.row === row) {
            val currentPosted = assets.firstOrNull { it.mediaId == w.asset.id }?.postId != null
            if (currentPosted) {
                val i = row.e.media.indexOfFirst { m -> assets.firstOrNull { it.mediaId == m.id }?.postId == null }
                if (i >= 0) winner = Winner(w.number, w.row, i)
            }
        }
    }

    private fun removeUploadAsync(id: Long) {
        if (busy) return
        viewModelScope.launch(Dispatchers.IO) {
            db.items.remove(id)
            storage.saveDb(db)
            publish()
        }
    }

    // ---- preview / download --------------------------------------------------------------------

    private fun currentAssetDetail(): AssetDetail? {
        val w = winner ?: return null
        val d = (detail as? DetailState.Ready)?.detail ?: return null
        val mid = w.asset.id
        return d.assets.firstOrNull { it.mediaId == mid }
    }

    fun openPreview() {
        val a = currentAssetDetail()
        if (a == null) {
            toast("Asset details are not loaded yet")
            return
        }
        val spec = PreviewPick.preview(a)
        if (spec == null) toast("No preview available for this asset") else preview = spec
    }

    fun closePreview() {
        preview = null
    }

    fun download() {
        val w = winner ?: return
        val a = currentAssetDetail()
        if (a == null) {
            toast("Asset details are not loaded yet")
            return
        }
        val v = PreviewPick.download(a)
        if (v == null) {
            toast("No downloadable file found")
            return
        }
        if (downloading) return
        downloading = true
        downloadStatus = "Downloading..."
        val name = Sources.downloadName(w.row.e, w.assetIndex)
        val mime = Sources.mimeOfExt(a.ext.ifBlank { v.ext })
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = Downloader.save(getApplication<Application>(), client, v.url, name, mime) { done, total ->
                    val mb = done / 1048576.0
                    downloadStatus = if (total > 0) {
                        String.format("Downloading... %.1f / %.1f MB", mb, total / 1048576.0)
                    } else {
                        String.format("Downloading... %.1f MB", mb)
                    }
                }
                downloadStatus = "Saved to $path"
                toast("Saved to $path")
            } catch (e: Exception) {
                downloadStatus = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                toast(downloadStatus)
            } finally {
                downloading = false
            }
        }
    }

    // ---- video info ----------------------------------------------------------------------------

    fun gatherVideoInfo() {
        val w = winner ?: return
        val ref = videoRefOf(w) ?: return
        if (auth.login.isBlank() || auth.apiKey.isBlank()) {
            toast("Enter your Danbooru login and API key first (artists are looked up on Danbooru)")
            return
        }
        videoState = VideoState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = VideoInfoService(http, api).gather(ref)
                videoText = info.toText()
                videoState = VideoState.Done(info)
            } catch (e: Exception) {
                videoState = VideoState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun updateVideoText(t: String) {
        videoText = t
    }

    // ---- derived text --------------------------------------------------------------------------

    fun uploadLink(w: Winner): String = Links.uploadPage(w.row.e, w.assetIndex)

    fun mediaLink(w: Winner): String = Links.mediaAsset(w.asset)

    fun shareMessage(w: Winner): String {
        val source = if (includeSource) shareSourceOf(w) else null
        return Links.shareMessage(prefix, mediaLink(w), source, affix)
    }
}
