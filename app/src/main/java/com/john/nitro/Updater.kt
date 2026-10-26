package com.john.nitro

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prüft beim Start, ob auf GitHub eine neuere Version liegt,
 * lädt sie auf Wunsch herunter und öffnet die Installation.
 */
class Updater(private val ctx: Context) {

    enum class Status { IDLE, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

    private val api = "https://api.github.com/repos/thebiz/nitro-app/releases/latest"

    @Volatile var status = Status.IDLE; private set
    @Volatile var latest = 0; private set
    @Volatile var progress = 0f; private set
    @Volatile private var apkUrl: String? = null
    private var downloadId = -1L

    val current: Int
    val currentName: String

    init {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        current = info.longVersionCode.toInt()
        currentName = info.versionName ?: "?"
    }

    fun check() {
        if (status == Status.DOWNLOADING || status == Status.INSTALLING) return
        Thread {
            try {
                val c = URL(api).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Nitro-App")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                val num = json.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                if (num > current && url != null) {
                    latest = num
                    apkUrl = url
                    status = Status.AVAILABLE
                }
            } catch (_: Exception) {
                // kein Netz oder GitHub nicht erreichbar: einfach weiterspielen
            }
        }.start()
    }

    fun download() {
        val url = apkUrl ?: return
        if (status == Status.DOWNLOADING) return
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Nitro 0.$latest")
                .setDescription("Update wird geladen")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "Nitro-$latest.apk")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            downloadId = dm.enqueue(req)
            status = Status.DOWNLOADING
            progress = 0f
            Thread { watch(dm, downloadId) }.start()
        } catch (_: Exception) {
            status = Status.FAILED
        }
    }

    private fun watch(dm: DownloadManager, id: Long) {
        while (status == Status.DOWNLOADING) {
            Thread.sleep(400)
            val cur = dm.query(DownloadManager.Query().setFilterById(id)) ?: continue
            cur.use {
                if (!it.moveToFirst()) { status = Status.FAILED; return }
                val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) progress = done.toFloat() / total
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> { install(dm, id); return }
                    DownloadManager.STATUS_FAILED -> { status = Status.FAILED; return }
                }
            }
        }
    }

    private fun install(dm: DownloadManager, id: Long) {
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) { status = Status.FAILED; return }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            status = Status.INSTALLING
        } catch (_: Exception) {
            status = Status.FAILED
        }
    }
}
