package com.arin.player.online

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arin.player.ui.Accent

private data class FoundVideo(val url: String, val pageUrl: String, val label: String)

private val MEDIA_HINTS = Regex(
    """\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|flv)(\?|$)|/videoplayback\?|mime=video""",
    RegexOption.IGNORE_CASE,
)
// Pieces of a stream, not something to play on its own
private val SEGMENT_HINTS = Regex("""\.(ts|m4s|aac|m4a)(\?|$)|/seg-|/segment|/chunk|range=""", RegexOption.IGNORE_CASE)

/**
 * Fallback for websites yt-dlp doesn't know: open the page, let it load its player, and catch
 * the video file / stream it requests. The user taps one of the found videos to play it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(startUrl: String, onPlay: (PlayRequest) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var address by remember { mutableStateOf(startUrl) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    val found = remember { mutableStateListOf<FoundVideo>() }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun addFound(url: String, page: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        if (found.any { it.url == url }) return
        val u = Uri.parse(url)
        val kind = when {
            ".m3u8" in url -> "Stream (HLS)"
            ".mpd" in url -> "Stream (DASH)"
            else -> u.lastPathSegment?.substringAfterLast('.', "")?.uppercase()?.take(4)?.ifBlank { null } ?: "Video"
        }
        found.add(0, FoundVideo(url, page, "$kind · ${u.host ?: ""}"))
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val a = address.trim().let { if ("://" in it) it else "https://$it" }
                        found.clear(); webView?.loadUrl(a)
                    }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { found.clear(); webView?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
            }
            if (loadingProgress in 1..99) {
                LinearProgressIndicator(progress = { loadingProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loadingProgress = newProgress
                                    if (newProgress == 100) scanVideoTags(view) { u -> addFound(u, view.url ?: startUrl) }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val u = request.url.toString()
                                    if (MEDIA_HINTS.containsMatchIn(u) && !SEGMENT_HINTS.containsMatchIn(u)) {
                                        val page = request.requestHeaders["Referer"] ?: startUrl
                                        view.post { addFound(u, page) }
                                    }
                                    return null // let the page load normally
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    address = url
                                }
                            }
                            loadUrl(startUrl)
                            webView = this
                        }
                    },
                )
            }

            // Found videos
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                Text(
                    if (found.isEmpty()) "Looking for videos… start the video on the page if nothing appears"
                    else "Videos found on this page (${found.size}) — tap to play in Arin player",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                if (found.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(found, key = { it.url }) { v ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val ua = webView?.settings?.userAgentString
                                    val headers = buildMap {
                                        put("Referer", v.pageUrl)
                                        if (ua != null) put("User-Agent", ua)
                                    }
                                    onPlay(PlayRequest(Uri.parse(v.url), headers, direct = true))
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PlayCircle, null, tint = Accent, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.label, fontSize = 14.sp)
                                    Text(v.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Also read <video src> / <source src> straight from the page. */
private fun scanVideoTags(view: WebView, onUrl: (String) -> Unit) {
    val js = """
        (function(){
          var out=[];
          document.querySelectorAll('video, video source, source[type^="video"]').forEach(function(v){
            var s=v.currentSrc||v.src; if(s && s.indexOf('blob:')!==0) out.push(s);
          });
          return JSON.stringify(out);
        })()
    """.trimIndent()
    view.evaluateJavascript(js) { result ->
        runCatching {
            val arr = org.json.JSONArray(org.json.JSONTokener(result).nextValue() as String)
            for (i in 0 until arr.length()) onUrl(arr.getString(i))
        }
    }
}
