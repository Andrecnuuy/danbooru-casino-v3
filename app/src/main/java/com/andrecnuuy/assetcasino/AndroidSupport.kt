package com.andrecnuuy.assetcasino

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.andrecnuuy.assetcasino.core.AssetDb
import com.andrecnuuy.assetcasino.core.Codec
import com.andrecnuuy.assetcasino.core.DaoDb
import com.andrecnuuy.assetcasino.core.Http
import com.andrecnuuy.assetcasino.core.HttpResponse
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Danbooru login + API key, read by the HTTP interceptor on every request to donmai.us. */
class AuthStore {
    @Volatile var login: String = ""
    @Volatile var apiKey: String = ""
}

fun buildClient(auth: AuthStore): OkHttpClient {
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val b = req.newBuilder()
        if (req.header("User-Agent") == null) {
            b.header("User-Agent", "DanbooruAssetCasino/1.0 (Android)")
        }
        val host = req.url.host
        val isDanbooru = host == "donmai.us" || host.endsWith(".donmai.us")
        if (isDanbooru && req.header("Authorization") == null && auth.login.isNotBlank() && auth.apiKey.isNotBlank()) {
            b.header("Authorization", Credentials.basic(auth.login, auth.apiKey))
        }
        chain.proceed(b.build())
    }
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .build()
}

class AndroidHttp(private val client: OkHttpClient) : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val rb = Request.Builder().url(url)
        for ((k, v) in headers) rb.header(k, v)
        return client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            HttpResponse(resp.code, body, resp.request.url.toString())
        }
    }
}

/** Private files + small preferences. */
class Storage(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("casino_prefs", Context.MODE_PRIVATE)

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun getBool(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }

    private fun file(name: String) = File(ctx.filesDir, name)

    private fun writeAtomic(name: String, text: String) {
        val target = file(name)
        val tmp = file("$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save $name")
        }
    }

    fun loadDb(): AssetDb? {
        val f = file("assets.json")
        if (!f.exists()) return null
        return try {
            Codec.decodeDb(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            f.renameTo(file("assets.corrupt.json"))
            null
        }
    }

    fun saveDb(db: AssetDb) {
        writeAtomic("assets.json", Codec.encodeDb(db, System.currentTimeMillis()))
    }

    fun loadDao(): DaoDb? {
        val f = file("dao_tags.json")
        if (!f.exists()) return null
        return try {
            Codec.decodeDao(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun saveDao(dao: DaoDb) {
        writeAtomic("dao_tags.json", Codec.encodeDao(dao))
    }
}

object Downloader {
    /** Streams [url] into Downloads/DanbooruAssetCasino/[name] (MediaStore). Returns the visible path. */
    fun save(
        ctx: Context,
        client: OkHttpClient,
        url: String,
        name: String,
        mime: String,
        onProgress: (Long, Long) -> Unit
    ): String {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DanbooruAssetCasino")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                val total = body.contentLength()
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open the output file")
                out.use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            }
            val finished = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, finished, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Downloads/DanbooruAssetCasino/$name"
    }
}

fun copyToClipboard(ctx: Context, text: String, label: String = "Copied") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("casino", text))
    Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
}

fun openInBrowser(ctx: Context, url: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
