package com.arin.player.vr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent

/** Lets the VR screen receive hardware keys (headset button, Bluetooth remote/gamepad, volume keys). */
object KeyRouter {
    var handler: ((KeyEvent) -> Boolean)? = null
}

sealed interface VoiceCommand {
    data class Seek(val deltaMs: Long) : VoiceCommand
    data object Play : VoiceCommand
    data object Pause : VoiceCommand
    data object Exit : VoiceCommand
    data object Recenter : VoiceCommand
    data object Subtitles : VoiceCommand
}

/**
 * Hands-free voice control in VR: "forward", "back", "skip 2 minutes", "rewind 30 seconds",
 * "pause", "play", "subtitles", "center", "exit". Keeps listening until stopped.
 */
class VoiceCommands(context: Context, private val onCommand: (VoiceCommand) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    private var running = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    }

    val available get() = recognizer != null

    fun start() {
        val r = recognizer ?: return
        running = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                texts.firstNotNullOfOrNull { parse(it) }?.let(onCommand)
                restart(150)
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { running = false; return }
                restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800 else 250)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent) }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.postDelayed({ if (running) runCatching { recognizer?.cancel(); recognizer?.startListening(intent) } }, delayMs)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
    }

    companion object {
        private val NUMBERS = mapOf(
            "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "sixty" to 60,
        )

        fun parse(raw: String): VoiceCommand? {
            val t = raw.lowercase()
            val amount = Regex("""(\d+|one|an?|two|three|four|five|ten|fifteen|twenty|thirty|forty|sixty)\s*(second|sec|minute|min)""")
                .find(t)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: NUMBERS[m.groupValues[1]] ?: 1
                    if (m.groupValues[2].startsWith("min")) n * 60_000L else n * 1_000L
                }
            return when {
                Regex("""\b(exit|close|quit)\b""").containsMatchIn(t) -> VoiceCommand.Exit
                Regex("""\b(pause|stop|wait)\b""").containsMatchIn(t) -> VoiceCommand.Pause
                Regex("""\b(back|rewind|previous|before|backward)""").containsMatchIn(t) -> VoiceCommand.Seek(-(amount ?: 10_000L))
                Regex("""\b(forward|skip|ahead|next|fast)""").containsMatchIn(t) -> VoiceCommand.Seek(amount ?: 10_000L)
                Regex("""\b(play|resume|continue|start)\b""").containsMatchIn(t) -> VoiceCommand.Play
                Regex("""\b(cent(er|re)|recenter|recentre|reset)\b""").containsMatchIn(t) -> VoiceCommand.Recenter
                Regex("""\b(subtitle|caption)""").containsMatchIn(t) -> VoiceCommand.Subtitles
                else -> null
            }
        }
    }
}
