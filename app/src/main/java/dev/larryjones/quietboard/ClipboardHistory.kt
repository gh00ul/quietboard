package dev.larryjones.quietboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Gboard-style clipboard history kept only in app-private storage.
 * Clips marked sensitive (passwords from password managers) are never recorded. Unpinned clips are
 * deleted from disk once they're an hour old: by a timer while the keyboard runs, and by an alarm
 * ([ClipExpiryReceiver]) otherwise (phone asleep, another keyboard selected).
 */
class ClipboardHistory(private val ctx: Context, private val prefs: Prefs) {
    class Item(val text: String, val time: Long, var pinned: Boolean = false)

    private val file = File(ctx.filesDir, FILE)
    private val io = Executors.newSingleThreadExecutor()
    private val cm = ctx.getSystemService(ClipboardManager::class.java)
    private val items = ArrayList<Item>()
    private var chipDismissedAt = 0L
    private var stopped = false
    var onChange: (() -> Unit)? = null
    /** When true, a copy is not recorded (e.g. it happened while an incognito field was active). */
    var skipCapture: () -> Boolean = { false }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }
    private val handler = Handler(Looper.getMainLooper())
    /** Fires when a clip expires or the paste chip times out, so both disappear on time. */
    private val tick = Runnable {
        prune()
        scheduleTimers()
        onChange?.invoke()
    }

    fun start() {
        load()
        // History was switched off while the keyboard wasn't running: don't keep what's left.
        if (!prefs.clipboardHistory) clear()
        cm?.addPrimaryClipChangedListener(listener)
    }

    fun stop() {
        cm?.removePrimaryClipChangedListener(listener)
        prune() // write the latest state; the alarm keeps deleting expired clips from here on
        stopped = true
        // After the prune: its save re-arms the timer, which would keep the dead keyboard alive.
        handler.removeCallbacks(tick)
        io.shutdown() // queued saves still run
    }

    private fun capture() {
        if (stopped || !prefs.clipboardHistory || skipCapture()) return
        val clip = try { cm?.primaryClip } catch (_: Exception) { null } ?: return
        if (isSensitive(clip.description) || clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        // Text only. Image/file clips are URIs: never record those, and never read a content stream
        // on the UI thread (coerceToText would).
        val text = try {
            item.text?.toString()
                ?: item.htmlText?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
        } catch (_: Exception) { null } ?: return
        if (text.isBlank() || text.length > 50_000) return
        add(text)
    }

    private fun isSensitive(d: ClipDescription?): Boolean {
        val extras = d?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun add(text: String) {
        prune()
        val wasPinned = items.any { it.text == text && it.pinned }
        items.removeAll { it.text == text }
        items.add(0, Item(text, System.currentTimeMillis(), wasPinned))
        while (items.count { !it.pinned } > MAX_ITEMS) {
            val last = items.indexOfLast { !it.pinned }
            if (last < 0) break
            items.removeAt(last)
        }
        save()
        onChange?.invoke()
    }

    /** Pinned clips first (kept until you unpin them), then recent ones, newest first. */
    fun list(): List<Item> {
        prune()
        return items.filter { it.pinned } + items.filter { !it.pinned }
    }

    fun togglePin(item: Item) {
        item.pinned = !item.pinned
        if (!prune()) save()
    }

    /** The most recent clip if it was copied in the last few minutes and not yet pasted/dismissed. */
    fun freshClip(): Item? {
        if (!prefs.clipboardHistory) return null
        prune()
        val top = items.firstOrNull() ?: return null
        val age = System.currentTimeMillis() - top.time
        return if (age < CHIP_MS && top.time > chipDismissedAt) top else null
    }

    fun dismissChip() {
        chipDismissedAt = items.firstOrNull()?.time ?: System.currentTimeMillis()
        scheduleTimers()
    }

    fun remove(item: Item) {
        items.remove(item)
        save()
    }

    /** Clears recent clips; pinned ones stay unless [includePinned]. */
    fun clear(includePinned: Boolean = false) {
        if (includePinned) items.clear() else items.removeAll { !it.pinned }
        save()
    }

    /** Drops expired unpinned clips; returns true if any were removed (and saved). */
    private fun prune(): Boolean {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val removed = items.removeAll { !it.pinned && it.time < cutoff }
        if (removed) save()
        return removed
    }

    /** In-process wake-ups for the next clip expiry and paste-chip timeout. */
    private fun scheduleTimers() {
        handler.removeCallbacks(tick)
        if (stopped) return
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        items.filter { !it.pinned }.minOfOrNull { it.time }?.let { next = it + RETENTION_MS }
        val top = items.firstOrNull()
        if (top != null && top.time > chipDismissedAt && now - top.time < CHIP_MS) next = minOf(next, top.time + CHIP_MS)
        if (next == Long.MAX_VALUE) return
        handler.postDelayed(tick, (next - now).coerceAtLeast(1_000L))
    }

    private fun load() {
        items.clear()
        items += readItems(file)
        if (!prune()) {
            scheduleTimers()
            scheduleDiskExpiry(ctx, items.filter { !it.pinned }.minOfOrNull { it.time })
        }
    }

    private fun save() {
        scheduleTimers()
        if (stopped || io.isShutdown) return
        // Never write back a clip that has already expired (one still in memory after a long sleep).
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val snapshot = items.filter { it.pinned || it.time >= cutoff }.map { Item(it.text, it.time, it.pinned) }
        scheduleDiskExpiry(ctx, snapshot.filter { !it.pinned }.minOfOrNull { it.time })
        io.execute { writeItems(file, snapshot) }
    }

    companion object {
        private const val FILE = "clipboard.json"
        const val MAX_ITEMS = 30
        const val RETENTION_MS = 60 * 60 * 1000L
        const val CHIP_MS = 3 * 60 * 1000L

        private fun readItems(f: File): List<Item> = runCatching {
            val arr = JSONArray(String(SafeFile.read(f) ?: return emptyList()))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Item(o.getString("t"), o.getLong("at"), o.optBoolean("p", false))
            }
        }.getOrDefault(emptyList())

        /**
         * The keyboard's save thread, the expiry alarm and Settings all write the file: one at a time,
         * and a read-modify-write (below) as one step, so none overwrites a newer state with an older one.
         */
        private val diskLock = Any()

        private fun writeItems(f: File, items: List<Item>) {
            synchronized(diskLock) {
                if (items.isEmpty()) { SafeFile.delete(f); return }
                val arr = JSONArray()
                for (it in items) arr.put(JSONObject().put("t", it.text).put("at", it.time).put("p", it.pinned))
                SafeFile.write(f, arr.toString().toByteArray())
            }
        }

        /** Used by the settings screen when the keyboard may not be running. */
        fun clearUnpinnedOnDisk(filesDir: File) {
            synchronized(diskLock) {
                val f = File(filesDir, FILE)
                writeItems(f, readItems(f).filter { it.pinned })
            }
        }

        /** Deletes clips over an hour old from disk, then re-arms the alarm for the next one. */
        fun pruneExpiredOnDisk(ctx: Context) {
            val keep = synchronized(diskLock) {
                val f = File(ctx.filesDir, FILE)
                val all = readItems(f)
                val cutoff = System.currentTimeMillis() - RETENTION_MS
                val kept = all.filter { it.pinned || it.time >= cutoff }
                if (kept.size != all.size) writeItems(f, kept)
                kept
            }
            scheduleDiskExpiry(ctx, keep.filter { !it.pinned }.minOfOrNull { it.time })
        }

        /**
         * Arms (or cancels) the alarm that deletes the oldest unpinned clip when it turns an hour old.
         * Inexact and Doze-friendly, so no exact-alarm permission: it may run a few minutes late.
         */
        fun scheduleDiskExpiry(ctx: Context, oldestUnpinned: Long?) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, ClipExpiryReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (oldestUnpinned == null) { am.cancel(pi); return }
            val delay = (oldestUnpinned + RETENTION_MS - System.currentTimeMillis()).coerceAtLeast(1_000L)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi)
        }
    }
}

/** Deletes expired clips from disk when the keyboard isn't around to do it (see [ClipboardHistory]). */
class ClipExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClipboardHistory.pruneExpiredOnDisk(context)
    }
}
