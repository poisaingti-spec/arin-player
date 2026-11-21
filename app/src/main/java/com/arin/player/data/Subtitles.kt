package com.arin.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One subtitle line. Times are absolute positions in the video. [lang] = spoken language code, e.g. "JA". */
data class Cue(val startMs: Long, val endMs: Long, val text: String, val lang: String)

object Srt {
    fun build(cues: List<Cue>): String = buildString {
        cues.sortedBy { it.startMs }.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append('\n')
            append(c.text.trim()).append("\n\n")
        }
    }

    private fun ts(ms: Long) = String.format(
        Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000
    )
}

/** Saves translated subtitles per video so re-watching is instant and costs nothing. */
class SubtitleCache(context: Context) {
    private val dir = File(context.filesDir, "subs").apply { mkdirs() }

    fun key(videoId: String, durationMs: Long): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$videoId|$durationMs".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun load(key: String): Pair<Set<Int>, List<Cue>>? = runCatching {
        val f = File(dir, "$key.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val done = o.getJSONArray("done").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
        val arr = o.getJSONArray("cues")
        val cues = (0 until arr.length()).map {
            val c = arr.getJSONObject(it)
            Cue(c.getLong("s"), c.getLong("e"), c.getString("t"), c.optString("l"))
        }
        done to cues
    }.getOrNull()

    fun save(key: String, done: Set<Int>, cues: List<Cue>) {
        val arr = JSONArray()
        cues.forEach { arr.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text).put("l", it.lang)) }
        val o = JSONObject().put("v", 1).put("done", JSONArray(done.sorted())).put("cues", arr)
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "$key.json"))
    }
}
