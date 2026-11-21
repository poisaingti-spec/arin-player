package com.arin.player.data

import android.content.Context

enum class EngineMode(val label: String, val desc: String) {
    AUTO("Auto", "Cloud when online, offline model when not"),
    CLOUD("Cloud only", "Best accuracy, needs internet + API key"),
    OFFLINE("Offline only", "Runs on the phone, no internet needed"),
}

enum class CloudProvider(
    val label: String,
    val base: String,
    val model: String,
    /** Text model that translates the timed lines into English. */
    val chatModel: String,
    val keyHint: String,
) {
    GROQ("Groq (fast, cheap)", "https://api.groq.com/openai/v1", "whisper-large-v3", "openai/gpt-oss-120b", "console.groq.com/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "whisper-1", "gpt-4.1-mini", "platform.openai.com/api-keys");

    val transcribeUrl get() = "$base/audio/transcriptions"
    val translateUrl get() = "$base/audio/translations"
    val chatUrl get() = "$base/chat/completions"
}

/** [aheads] = whisper.cpp DTW alignment-heads preset id, for precise word timing. */
enum class LocalModel(val label: String, val file: String, val sizeMb: Int, val note: String, val aheads: Int) {
    TINY("Tiny", "ggml-tiny.bin", 75, "Fastest, rough translation", 4),
    BASE("Base", "ggml-base.bin", 142, "Balanced", 6),
    SMALL("Small", "ggml-small.bin", 466, "Good quality, needs a fast phone", 8),
    LARGE_TURBO("Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 547, "Most accurate, needs a recent flagship phone", 14);

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("arin_player", Context.MODE_PRIVATE)

    var mode: EngineMode
        get() = runCatching { EngineMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(EngineMode.AUTO)
        set(v) = p.edit().putString("mode", v.name).apply()

    var provider: CloudProvider
        get() = runCatching { CloudProvider.valueOf(p.getString("provider", null)!!) }.getOrDefault(CloudProvider.GROQ)
        set(v) = p.edit().putString("provider", v.name).apply()

    /** One key per provider so switching doesn't lose it. */
    fun apiKey(provider: CloudProvider = this.provider): String = p.getString("key_${provider.name}", "")!!.trim()
    fun setApiKey(provider: CloudProvider, key: String) = p.edit().putString("key_${provider.name}", key.trim()).apply()

    /** Free backup cloud: Cloudflare Workers AI (used when the main cloud's free limit runs out). */
    var cfAccountId: String
        get() = p.getString("cf_account", "")!!.trim()
        set(v) = p.edit().putString("cf_account", v.trim()).apply()
    var cfToken: String
        get() = p.getString("cf_token", "")!!.trim()
        set(v) = p.edit().putString("cf_token", v.trim()).apply()

    var localModel: LocalModel
        get() = runCatching { LocalModel.valueOf(p.getString("local_model", null)!!) }.getOrDefault(LocalModel.BASE)
        set(v) = p.edit().putString("local_model", v.name).apply()

    var subtitleSizeSp: Float
        get() = p.getFloat("sub_size", 20f)
        set(v) = p.edit().putFloat("sub_size", v).apply()

    /** Last few online video links, newest first. */
    var recentUrls: List<String>
        get() = p.getString("recent_urls", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString("recent_urls", v.take(8).joinToString("\n")).apply()

    fun addRecentUrl(url: String) { recentUrls = listOf(url) + recentUrls.filter { it != url } }

    var showLanguageTag: Boolean
        get() = p.getBoolean("lang_tag", true)
        set(v) = p.edit().putBoolean("lang_tag", v).apply()
}
