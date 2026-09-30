package fr.arthurbrugiere.forgeline.signin

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where a browser sign-in comes back to: the redirect URI to give the forge, and what arrives there. */
interface BrowserRedirect : Closeable {
    val redirectUri: String

    /** Waits for the forge to send the browser back, answers it with [page], and returns the query parameters. */
    suspend fun await(page: String): Map<String, String>
}

fun interface BrowserRedirects {
    fun open(): BrowserRedirect
}

class LoopbackRedirects @Inject constructor() : BrowserRedirects {
    override fun open(): BrowserRedirect = LoopbackRedirect(PATH)

    companion object {
        /** Register the OAuth application with `http://127.0.0.1/oauth/codeberg`: Forgejo ignores the port of loopback redirects. */
        const val PATH = "/oauth/codeberg"
    }
}

/**
 * A one-request web server on 127.0.0.1, on a free port, for the native-app redirect of RFC 8252 §7.3. Forgejo only
 * accepts http(s) redirect URIs, and for public clients it matches a loopback IP's redirect whatever its port.
 */
class LoopbackRedirect(private val path: String) : BrowserRedirect {
    private val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    override val redirectUri: String = "http://127.0.0.1:${server.localPort}$path"

    override suspend fun await(page: String): Map<String, String> = suspendCancellableCoroutine { continuation ->
        // accept() blocks and ignores interrupts: cancelling closes the socket, which unblocks it.
        continuation.invokeOnCancellation { close() }
        thread(name = "loopback-redirect", isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val target = socket.getInputStream().bufferedReader().readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        val output = socket.getOutputStream()
                        if (!target.startsWith(path)) {
                            // A favicon or a stray request: not the redirect, keep waiting.
                            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val body = page.toByteArray()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        output.write(body)
                        output.flush()
                        continuation.resume(query(target.substringAfter('?', "")))
                        return@thread
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

    override fun close() = server.close()

    private fun query(query: String): Map<String, String> = query.split('&').filter { '=' in it }.associate { pair ->
        val (key, value) = pair.split('=', limit = 2)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
}
