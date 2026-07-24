package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CronetDohResolver(private val context: Context) {

    companion object {
        private const val TAG = "CronetDohResolver"
    }

    private val executor: Executor = Executors.newFixedThreadPool(4)
    private var cronetEngine: CronetEngine? = null

    init {
        try {
            Log.i(TAG, "Initializing secure DNS-over-HTTPS/3 Resolver with Dual-Provider Cronet Engine...")
            cronetEngine = CronetEngineProvider.createCronetEngine(context)
            if (cronetEngine != null) {
                Log.i(TAG, "CronetDohResolver initialized successfully.")
            } else {
                Log.e(TAG, "CronetDohResolver failed: CronetEngine is null.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CronetDohResolver.", e)
        }
    }

    /**
     * Converts a domain-based DoH URL to a direct IP-based DoH URL.
     * This completely avoids recursive bootstrap lookups (chicken-and-egg DNS loop)
     * and relies on IP address SANs in certificates (RFC 2818) for native TLS security.
     */
    private fun convertToDirectIpUrl(serverUrl: String): String {
        return try {
            val uri = java.net.URI(serverUrl)
            val host = uri.host ?: return serverUrl
            val ip = when (host) {
                "dns.google", "dns.google.com" -> "8.8.8.8"
                "cloudflare-dns.com", "one.one.one.one" -> "1.1.1.1"
                "dns.quad9.net" -> "9.9.9.9"
                "dns.adguard-dns.com", "dns.adguard.com" -> "94.140.14.14"
                "dns.controld.com" -> "76.76.2.0"
                "dns.nextdns.io" -> "45.90.28.0"
                "free.shecan.ir" -> "178.22.122.100"
                else -> host
            }
            if (ip != host) {
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                val path = uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/dns-query"
                val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                val directUrl = "https://$ip$portStr$path$query"
                Log.d(TAG, "Bootstrap bypass: mapped domain '$host' to direct IP URL: $directUrl")
                directUrl
            } else {
                serverUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing direct IP bootstrap translation for: $serverUrl", e)
            serverUrl
        }
    }

    /**
     * Asynchronously resolves a DNS query over HTTP/3 (or fallback to HTTP/2 TCP)
     * using direct IP URL to avoid the bootstrap chicken-and-egg problem.
     * Falls back to standard HttpURLConnection POST if Cronet engine is unavailable.
     */
    suspend fun resolveQuery(query: ByteArray, serverUrl: String): ByteArray {
        val directIpUrl = convertToDirectIpUrl(serverUrl)
        val engine = cronetEngine

        if (engine == null) {
            return resolveQueryHttpFallback(query, directIpUrl)
        }

        return try {
            kotlinx.coroutines.withTimeout(2500) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val callback = DohRequestCallback(continuation)
                        val requestBuilder = engine.newUrlRequestBuilder(
                            directIpUrl,
                            callback,
                            executor
                        )
                        
                        requestBuilder.setHttpMethod("POST")
                        requestBuilder.addHeader("Content-Type", "application/dns-message")
                        requestBuilder.addHeader("Accept", "application/dns-message")
                        
                        val uploadDataProvider = UploadDataProviders.create(query)
                        requestBuilder.setUploadDataProvider(uploadDataProvider, executor)
                        
                        val request = requestBuilder.build()
                        
                        continuation.invokeOnCancellation {
                            try {
                                request.cancel()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error canceling Cronet request", e)
                            }
                        }
                        
                        request.start()
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cronet DoH request failed or timed out (${e.message}). Triggering HttpURLConnection fallback...")
            resolveQueryHttpFallback(query, directIpUrl)
        }
    }

    private suspend fun resolveQueryHttpFallback(query: ByteArray, directIpUrl: String): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(directIpUrl)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")

            connection.outputStream.use { os ->
                os.write(query)
                os.flush()
            }

            if (connection.responseCode == 200) {
                connection.inputStream.use { inputStream ->
                    inputStream.readBytes()
                }
            } else {
                throw IOException("HttpURLConnection DoH returned HTTP code ${connection.responseCode}")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private class DohRequestCallback(
        private val continuation: CancellableContinuation<ByteArray>
    ) : UrlRequest.Callback() {

        private val responseBytes = ByteArrayOutputStream()
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(32 * 1024)

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            if (info.httpStatusCode != 200) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IOException("DoH3 server returned non-200 HTTP status code: ${info.httpStatusCode}"))
                }
                request.cancel()
                return
            }
            buffer.clear()
            request.read(buffer)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            if (byteBuffer.hasRemaining()) {
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                responseBytes.write(bytes)
            }
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            if (continuation.isActive) {
                continuation.resume(responseBytes.toByteArray())
            }
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("Cronet request failed: ${error.message}", error))
            }
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("Cronet request canceled"))
            }
        }
    }
}
