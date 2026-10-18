package com.orcaphone.cloud

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Minimal HTTP server on 127.0.0.1 that receives the OAuth redirect
 * (http://localhost:PORT/callback?code=…&state=…) when signing in with Google/GitHub in the
 * system browser — the same loopback flow OrcaSlicer desktop uses.
 */
class LoopbackServer(private val onCallback: (params: Map<String, String>) -> Boolean) {
    private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: -1

    /** Binds [preferredPort], or any free port if it is taken. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        socket?.let { return it.localPort }
        val s = runCatching { ServerSocket(preferredPort, 8, InetAddress.getByName("127.0.0.1")) }
            .getOrElse { ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) }
        socket = s
        thread(name = "orca-loopback", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(client) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val params = query.split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val (status, body) = if (path.endsWith("/callback") && (params.containsKey("code") || params.containsKey("error"))) {
                val ok = runCatching { onCallback(params) }.getOrDefault(false)
                "200 OK" to page(ok)
            } else "404 Not Found" to "Not found"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun page(ok: Boolean): String {
        val title = if (ok) "Signed in" else "Sign-in failed"
        val msg = if (ok) "You are signed in to Orca Cloud. Return to OrcaPhone." else "Something went wrong. Return to OrcaPhone and try again."
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;margin:32px;color:#171d1c;background:#f4f7f6}
            a{display:inline-block;margin-top:16px;padding:14px 22px;background:#00796b;color:#fff;border-radius:24px;text-decoration:none}</style>
            </head><body><h2>$title</h2><p>$msg</p>
            <a href="intent://auth#Intent;scheme=orcaphone;package=com.orcaphone;end">Open OrcaPhone</a></body></html>"""
    }
}
