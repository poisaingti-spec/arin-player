package com.arin.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A single spoken word with its exact timing (used for word-by-word highlighting). */
data class Word(val startMs: Long, val endMs: Long, val text: String)

/**
 * One subtitle line. Times are absolute positions in the video. [lang] = spoken language code, e.g. "JA".
 * [words] is filled when the line is the actual spoken words (English audio), so each word can light
 * up exactly when it's said.
 */
data class Cue(val startMs: Long, val endMs: Long, val text: String, val lang: String, val words: List<Word> = emptyList())

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
        // "v2": new word-timed pipeline, so older (looser) cached subtitles are redone.
        val d = MessageDigest.getInstance("SHA-1").digest("v2|$videoId|$durationMs".toByteArray())
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
            val w = c.optJSONArray("w")
            val words = if (w == null) emptyList() else (0 until w.length()).map { i ->
                val a = w.getJSONArray(i); Word(a.getLong(0), a.getLong(1), a.getString(2))
            }
            Cue(c.getLong("s"), c.getLong("e"), c.getString("t"), c.optString("l"), words)
        }
        done to cues
    }.getOrNull()

    fun save(key: String, done: Set<Int>, cues: List<Cue>) {
        val arr = JSONArray()
        cues.forEach { c ->
            val o = JSONObject().put("s", c.startMs).put("e", c.endMs).put("t", c.text).put("l", c.lang)
            if (c.words.isNotEmpty()) o.put("w", JSONArray().apply {
                c.words.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) }
            })
            arr.put(o)
        }
        val o = JSONObject().put("v", 1).put("done", JSONArray(done.sorted())).put("cues", arr)
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "$key.json"))
    }
}
