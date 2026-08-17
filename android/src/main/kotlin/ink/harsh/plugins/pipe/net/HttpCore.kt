package ink.harsh.plugins.pipe.net

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Engine-agnostic HTTP.
 *
 * Deliberately references no extractor type. Each engine has its own
 * `Downloader` subclass and its own incompatible `Response` — the
 * two forks' constructors differ (PipePipe carries a raw byte[] body for SABR's
 * binary UMP payloads, NewPipe does not) — so the only thing that can be shared
 * between them is this layer.
 */
object HttpCore {

    /**
     * NewPipe's User-Agent. YouTube tailors its response to the client it
     * believes it is talking to, so this is load-bearing, not cosmetic.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    /** Opts out of YouTube's restricted mode. */
    private const val YOUTUBE_COOKIE = "PREF=f2=8000000"

    private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

    @Volatile
    private var sharedClient: OkHttpClient? = null

    /**
     * One client for the whole plugin, so the connection pool and DNS cache are
     * reused across engines and across SABR sessions.
     */
    @JvmStatic
    fun client(): OkHttpClient {
        var local = sharedClient
        if (local == null) {
            synchronized(HttpCore::class.java) {
                local = sharedClient
                if (local == null) {
                    local = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        // SABR responses can be large and slow to drain; the
                        // session applies its own bounded backoff on top.
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()
                    sharedClient = local
                }
            }
        }
        return local!!
    }

    /** A response, decoupled from either extractor's Response type. */
    class Result internal constructor(
        @JvmField val code: Int,
        @JvmField val message: String,
        @JvmField val headers: Map<String, List<String>>,
        @JvmField val body: ByteArray?,
        @JvmField val latestUrl: String,
    ) {

        /** The body decoded as UTF-8. SABR callers want [body] instead. */
        fun text(): String = if (body == null) "" else String(body, StandardCharsets.UTF_8)
    }

    /** Build a call without executing it, so async callers can keep the handle to cancel it. */
    @JvmStatic
    fun newCall(
        httpMethod: String,
        url: String,
        headers: Map<String, List<String?>?>?,
        dataToSend: ByteArray?,
        followRedirects: Boolean,
    ): Call {
        val builder = Request.Builder().url(url)

        /*
         * The body carries NO MediaType, deliberately.
         *
         * okhttp's BridgeInterceptor writes RequestBody.contentType() over any
         * Content-Type header already on the request. Attaching
         * "application/json; charset=utf-8" here therefore silently replaced the
         * caller's Content-Type — which broke Google's gRPC-web attestation
         * endpoint: it answers 200 for "application/json+protobuf" and 404 for
         * "application/json". Verified on device across both HTTP/1.1 and h2.
         *
         * Leaving it null lets the caller's header stand, which is what
         * PipePipeClient's downloader does too.
         */
        if (dataToSend != null) {
            builder.method(httpMethod, dataToSend.toRequestBody(null))
        } else if ("POST".equals(httpMethod, ignoreCase = true)) {
            builder.method(httpMethod, ByteArray(0).toRequestBody(null))
        } else {
            builder.method(httpMethod, null)
        }

        var callerSetUserAgent = false
        if (headers != null) {
            for (header in headers.entries) {
                val name = header.key
                val values = header.value ?: continue
                // The extractor sometimes sets its own UA per InnerTube client
                // profile; that choice must win over our default.
                if ("User-Agent".equals(name, ignoreCase = true)) {
                    callerSetUserAgent = true
                }
                builder.removeHeader(name)
                for (value in values) {
                    if (value != null) {
                        builder.addHeader(name, value)
                    }
                }
            }
        }

        if (!callerSetUserAgent) {
            builder.header("User-Agent", USER_AGENT)
        }
        // Since the body no longer carries one, supply a default for callers
        // that send data without stating a type.
        if (dataToSend != null && (headers == null || !hasHeader(headers, "Content-Type"))) {
            builder.header("Content-Type", JSON_CONTENT_TYPE)
        }
        if (url.contains("youtube.com") && (headers == null || !headers.containsKey("Cookie"))) {
            builder.header("Cookie", YOUTUBE_COOKIE)
        }

        val callClient = if (followRedirects) {
            client()
        } else {
            client().newBuilder().followRedirects(false).followSslRedirects(false).build()
        }

        return callClient.newCall(builder.build())
    }

    private fun hasHeader(headers: Map<String, List<String?>?>, name: String): Boolean {
        for (key in headers.keys) {
            if (name.equals(key, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /** Execute synchronously and fully buffer the body. */
    @JvmStatic
    @Throws(IOException::class)
    fun execute(
        httpMethod: String,
        url: String,
        headers: Map<String, List<String?>?>?,
        dataToSend: ByteArray?,
        followRedirects: Boolean,
    ): Result = toResult(newCall(httpMethod, url, headers, dataToSend, followRedirects).execute())

    /** Convert an okhttp response, consuming and closing its body. */
    @JvmStatic
    @Throws(IOException::class)
    fun toResult(response: Response): Result {
        response.use { closeable ->
            val responseHeaders = closeable.headers
            val mapped = HashMap<String, List<String>>()
            for (name in responseHeaders.names()) {
                mapped[name] = responseHeaders.values(name)
            }

            val bytes = closeable.body.bytes()

            return Result(
                closeable.code,
                closeable.message,
                Collections.unmodifiableMap(mapped),
                bytes,
                closeable.request.url.toString(),
            )
        }
    }
}
