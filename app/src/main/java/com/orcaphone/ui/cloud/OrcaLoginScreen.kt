package com.orcaphone.ui.cloud

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.orcaphone.cloud.CloudSync
import com.orcaphone.cloud.OrcaCloudClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Sign-in to Orca Cloud using the same hosted login page as OrcaSlicer desktop
 * (cloud.orcaslicer.com/orcaslicer-login). The desktop app talks to that page through a wxWebView
 * message handler named "wx"; this screen provides the same bridge in an Android WebView.
 * Providers that cannot run inside a WebView (Google, GitHub) open in the browser and come back
 * through the localhost redirect handled by [CloudSync].
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OrcaLoginScreen(cloud: CloudSync, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val loginUrl = remember { OrcaCloudClient.CLOUD_URL + OrcaCloudClient.LOGIN_PATH + "?lang=" + java.util.Locale.getDefault().language }

    LaunchedEffect(Unit) {
        cloud.beginLogin()
        cloud.loginCompleted.collect { onClose() }
    }
    DisposableEffect(Unit) { onDispose { cloud.endLogin() } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Sign in to Orca Cloud") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { IconButton(onClick = { error = null; webView?.loadUrl(loginUrl) }) { Icon(Icons.Outlined.Refresh, "Reload") } },
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = settings.userAgentString + " OrcaPhone"

                        fun openExternal(url: String) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }

                        fun reply(message: String) {
                            post { evaluateJavascript("window.postMessage($message, '*');", null) }
                        }

                        val bridge = object {
                            @JavascriptInterface
                            fun postMessage(raw: String) {
                                val msg = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
                                val data = msg["data"] as? JsonObject
                                when ((msg["command"] as? JsonPrimitive)?.content) {
                                    "get_login_cmd" -> reply(cloud.loginConfig())
                                    "user_login" -> scope.launch {
                                        loading = true
                                        runCatching { cloud.completeLogin(data ?: msg) }
                                            .onFailure { error = it.message ?: "Sign-in failed"; loading = false }
                                    }
                                    "get_localhost_url" -> reply(buildJsonObject {
                                        put("command", "get_localhost_url")
                                        put("sequence_id", (msg["sequence_id"] as? JsonPrimitive)?.content ?: "")
                                        put("response", buildJsonObject {
                                            put("base_url", "http://localhost:${cloud.loginPort()}")
                                            put("result", "success")
                                        })
                                    }.toString())
                                    "thirdparty_login" -> (data?.get("url") as? JsonPrimitive)?.content?.let { url ->
                                        openExternal(rewritePort(url, cloud.loginPort()))
                                    }
                                    "new_webpage" -> (data?.get("url") as? JsonPrimitive)?.content?.let(::openExternal)
                                }
                            }
                        }
                        addJavascriptInterface(bridge, "OrcaPhoneBridge")
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, BRIDGE_JS, setOf("*"))
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                                view.evaluateJavascript(BRIDGE_JS, null)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(BRIDGE_JS, null)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                val host = url.host.orEmpty()
                                // Google/GitHub/Apple refuse sign-in inside embedded WebViews.
                                if (host.endsWith("accounts.google.com") || host == "github.com" || host.endsWith("appleid.apple.com")) {
                                    openExternal(rewritePort(url.toString(), cloud.loginPort()))
                                    return true
                                }
                                return false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    error = "Could not load the Orca Cloud sign-in page (${err.description}). Check your internet connection."
                                }
                            }
                        }
                        loadUrl(loginUrl)
                    }
                },
            )
            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { error = null; webView?.loadUrl(loginUrl) }, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                    }
                }
            }
        }
    }
}

/** OrcaSlicer's login page assumes the default loopback port; use the one actually bound. */
private fun rewritePort(url: String, port: Int): String {
    val def = OrcaCloudClient.LOOPBACK_PORT.toString()
    if (port <= 0 || port.toString() == def) return url
    return url.replace("localhost:$def", "localhost:$port").replace("127.0.0.1:$def", "127.0.0.1:$port")
        .replace("localhost%3A$def", "localhost%3A$port").replace("127.0.0.1%3A$def", "127.0.0.1%3A$port")
}

/** Emulates wxWebView's script message handler ("wx") and its Edge/WebKit variants. */
private const val BRIDGE_JS = """
(function(){
  if (window.__orcaPhoneBridge) return; window.__orcaPhoneBridge = true;
  function send(m){ try { OrcaPhoneBridge.postMessage(typeof m === 'string' ? m : JSON.stringify(m)); } catch(e) {} }
  window.wx = window.wx || {}; window.wx.postMessage = send;
  window.chrome = window.chrome || {};
  if (!window.chrome.webview) window.chrome.webview = { postMessage: send, addEventListener: function(){}, removeEventListener: function(){} };
  window.webkit = window.webkit || {}; window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.wx = { postMessage: send };
})();
"""
