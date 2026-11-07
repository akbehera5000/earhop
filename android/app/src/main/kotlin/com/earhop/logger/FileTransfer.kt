package com.earhop.logger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files and photos over Wi-Fi, any size, streamed (same format as FileStream in FileTransfer.swift):
 * "EHF1" | header length (4) | header (ClipCodec frame, kind "file", data = JSON {"name","size"}) | records | a record of
 * length 0. Record: length (4) | nonce (12) | AES-256-GCM ciphertext and tag of up to 1 MB, with "EHF1" + file id +
 * record number (4) as associated data. Pure parts are unit tested.
 */
object FileStream {
    val MAGIC = "EHF1".toByteArray()
    const val MAGIC_INT = 0x45484631
    const val CHUNK = 1 shl 20

    fun aad(id: String, index: Int) = MAGIC + id.toByteArray() +
        byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

    fun seal(data: ByteArray, len: Int, secret: String, id: String, index: Int): ByteArray {
        val n = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ClipCodec.key(secret), "AES"), GCMParameterSpec(128, n))
        c.updateAAD(aad(id, index))
        return n + c.doFinal(data, 0, len)
    }

    fun open(rec: ByteArray, secret: String, id: String, index: Int): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(ClipCodec.key(secret), "AES"), GCMParameterSpec(128, rec, 0, 12))
        c.updateAAD(aad(id, index))
        c.doFinal(rec, 12, rec.size - 12)
    } catch (e: Exception) { null }

    /** A file name safe to save: no folders, no hidden names, at most 120 characters. */
    fun safeName(n: String): String {
        var s = n.substringAfterLast('/').substringAfterLast('\\').replace(':', '-').trimStart('.')
        if (s.isEmpty()) s = "file"
        return s.take(120)
    }

    /** Writes a whole stream for [input] to [out]. */
    fun write(out: OutputStream, header: ByteArray, input: InputStream, secret: String, id: String) {
        val d = DataOutputStream(out.buffered(1 shl 16))
        d.write(MAGIC); d.writeInt(header.size); d.write(header)
        val buf = ByteArray(CHUNK)
        var index = 0
        while (true) {
            var n = 0
            while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
            if (n == 0) break
            val rec = seal(buf, n, secret, id, index++)
            d.writeInt(rec.size); d.write(rec)
            if (n < CHUNK) break
        }
        d.writeInt(0)
        d.flush()
    }

    /** Reads the records after the header into [out]; false when the stream is broken or forged. */
    fun read(inp: DataInputStream, out: OutputStream, secret: String, id: String): Boolean {
        var index = 0
        while (true) {
            val n = inp.readInt()
            if (n == 0) return true
            if (n < 28 || n > CHUNK + 64) return false
            val rec = ByteArray(n); inp.readFully(rec)
            val plain = open(rec, secret, id, index++) ?: return false
            out.write(plain)
        }
    }
}

/** Receiving and sending files and links on Android. */
object FileShare {
    private const val CHANNEL = "earhop-shared"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Files and links from your devices", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notify(ctx: Context, title: String, text: String, open: Intent) {
        channel(ctx)
        val pi = PendingIntent.getActivity(ctx, (System.currentTimeMillis() and 0xFFFF).toInt(), open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
        val n = b.setSmallIcon(R.drawable.ic_tile_clip).setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() and 0x7FFFFFF).toInt(), n) } catch (e: Exception) { }
    }

    /** A link from another device: a notification, one tap opens it (Android does not let EarHop open it by itself). */
    fun showLink(ctx: Context, from: String, url: String) {
        val u = Uri.parse(url)
        if (u.scheme?.lowercase() !in setOf("http", "https")) return
        notify(ctx, "Link from ${ClipSync.pretty(from)}", url, Intent(Intent.ACTION_VIEW, u))
        EhLog.w("link: a link from $from is waiting in the notification")
    }

    /**
     * Saves an incoming file stream (header already read) in Downloads/EarHop. Returns the saved name, or null when the
     * stream broke off (nothing is left behind).
     */
    fun receive(ctx: Context, item: ClipItem, inp: DataInputStream, secret: String): String? {
        val meta = ClipCodec.parseFlat(String(item.data, Charsets.UTF_8))
        val name = FileStream.safeName(meta["name"] ?: "file")
        return if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/EarHop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            val ok = try { cr.openOutputStream(uri)?.use { FileStream.read(inp, it, secret, item.id) } ?: false } catch (e: Exception) { false }
            if (!ok) { cr.delete(uri, null, null); return null }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val saved = cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else name } ?: name
            notify(ctx, "$saved from ${ClipSync.pretty(item.dev)}", "Saved in Downloads/EarHop",
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, cr.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            saved
        } else {
            val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "EarHop").apply { mkdirs() }
            var f = java.io.File(dir, name); var i = 2
            while (f.exists()) { f = java.io.File(dir, name.substringBeforeLast('.') + " $i" + (if ('.' in name) "." + name.substringAfterLast('.') else "")); i++ }
            val ok = try { f.outputStream().use { FileStream.read(inp, it, secret, item.id) } } catch (e: Exception) { false }
            if (!ok) { f.delete(); return null }
            notify(ctx, "${f.name} from ${ClipSync.pretty(item.dev)}", "Saved in EarHop's Downloads folder", Intent(Intent.ACTION_VIEW))
            f.name
        }
    }

    /** Sends one shared file or photo to devices over Wi-Fi; returns how many devices it went to. */
    fun send(ctx: Context, uri: Uri, devs: Collection<String>): Int {
        val p = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
        val secret = p.getString("secret", "") ?: ""
        val self = p.getString("name", "phone") ?: "phone"
        val cr = ctx.contentResolver
        var name = "file"; var size = -1L
        try {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
            }
        } catch (e: Exception) { }
        name = FileStream.safeName(name)
        val id = (1..8).joinToString("") { "%02x".format(java.util.Random().nextInt(256)) }
        var sent = 0
        val targets = devs.filter { it != self && EhState.wifiHostsOf(it).isNotEmpty() }
        ClipSync.trackSent(ctx, id, targets.toSet(), "file", name)
        for (dev in targets) {
            val host = EhState.wifiHostsOf(dev).first()
            // Opened here, while the share screen holds the permission to read it; the open stream stays readable.
            val input = try { cr.openInputStream(uri) } catch (e: Exception) { EhLog.w("file: cannot read $name: ${e.message}"); null } ?: continue
            val meta = "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"size\":$size}"
            val header = ClipCodec.encode(ClipItem(id, self, "file", "application/octet-stream", System.currentTimeMillis(), meta.toByteArray(), dev), secret)
                ?: run { input.close(); null } ?: continue
            Thread {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, ClipTcp.PORT), 5000)
                        s.soTimeout = 30_000
                        input.use { FileStream.write(s.getOutputStream(), header, it, secret, id) }
                    }
                    EhLog.w("file: $name sent to $dev, waiting for the receipt")
                } catch (e: Exception) { EhLog.w("file: $name to $dev failed: ${e.message}") }
            }.start()
            sent++
        }
        return sent
    }
}
