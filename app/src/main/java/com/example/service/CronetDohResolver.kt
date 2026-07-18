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
            Log.i(TAG, "Initializing CronetEngine for secure HTTP/3 and QUIC...")
            
            // Proactively install Google Play Services Cronet Provider
            try {
                com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
                Log.i(TAG, "Google Play Services Cronet provider installed successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install Play Services Cronet provider: ${e.message}. Attempting default initialization.")
            }

            val cacheDir = File(context.cacheDir, "cronet_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val builder = CronetEngine.Builder(context)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024) // 10MB Disk Cache
                .setStoragePath(cacheDir.absolutePath)

            cronetEngine = builder.build()
            Log.i(TAG, "CronetEngine initialized successfully with HTTP/3 support.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CronetEngine.", e)
        }
    }

    suspend fun resolveQuery(query: ByteArray, serverUrl: String): ByteArray {
        val engine = cronetEngine ?: throw IOException("Cronet engine not initialized")
        
        return suspendCancellableCoroutine { continuation ->
            try {
                val callback = DohRequestCallback(continuation)
                val requestBuilder = engine.newUrlRequestBuilder(
                    serverUrl,
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
