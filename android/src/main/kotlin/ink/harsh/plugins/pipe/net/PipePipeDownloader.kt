package ink.harsh.plugins.pipe.net

import okhttp3.Call
import okhttp3.Callback
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException

/**
 * [Downloader] for the PipePipe engine.
 *
 * Unlike NewPipe's, this contract requires an async path — the YouTube
 * extractor fans several InnerTube client requests out in parallel and merges
 * the results, and SABR needs raw bytes rather than a decoded string. Both are
 * why `CancellableCall` wraps an `okhttp3.Call` directly, and why
 * this class cannot share an implementation with [NewPipeDownloader].
 */
class PipePipeDownloader : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val result = HttpCore.execute(
            request.httpMethod(),
            request.url(),
            request.headers(),
            request.dataToSend(),
            request.followRedirects(),
        )
        return toResponse(request, result)
    }

    /*
     * The streaming trio below MUST be overridden, not inherited.
     *
     * The base-class defaults funnel getStreaming/postStreaming through the
     * buffered get()/post() and wrap the result in a ByteArrayInputStream —
     * "an implementation that can stream (e.g. over okhttp) should override
     * this", per their own javadoc — and the timeoutMs overload silently
     * discards its deadline. SABR rounds carry multi-megabyte media payloads,
     * so inheriting the defaults meant one whole-payload byte[] per round and
     * a stalled round hanging on HttpCore's global 30s timeouts instead of the
     * per-round deadline the session asked for. Mirrors PipePipeClient's
     * DownloaderImpl.
     */
    @Throws(IOException::class, ReCaptchaException::class)
    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
    ): StreamingResponse = executeStreaming(
        Request.newBuilder().get(url).headers(headers).localization(localization).build(),
        0L,
    )

    @Throws(IOException::class, ReCaptchaException::class)
    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
        timeoutMs: Long,
    ): StreamingResponse = executeStreaming(
        Request.newBuilder().get(url).headers(headers).localization(localization).build(),
        timeoutMs,
    )

    @Throws(IOException::class, ReCaptchaException::class)
    override fun postStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?,
    ): StreamingResponse = executeStreaming(
        Request.newBuilder().post(url, dataToSend).headers(headers)
            .localization(localization).build(),
        0L,
    )

    @Throws(IOException::class, ReCaptchaException::class)
    private fun executeStreaming(request: Request, timeoutMs: Long): StreamingResponse {
        val response = HttpCore.newStreamingCall(
            request.httpMethod(),
            request.url(),
            request.headers(),
            request.dataToSend(),
            request.followRedirects(),
            timeoutMs,
        ).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha challenge requested", request.url())
        }
        // Closing the StreamingResponse closes this stream, which releases the
        // okhttp body and its connection. That contract is the caller's.
        return StreamingResponse(
            response.code,
            response.headers.toMultimap(),
            response.body.byteStream(),
        )
    }

    override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
        val call = HttpCore.newCall(
            request.httpMethod(),
            request.url(),
            request.headers(),
            request.dataToSend(),
            request.followRedirects(),
        )
        val cancellable = CancellableCall(call)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Cancellation is a normal control-flow event here: the
                // extractor races several clients and drops the losers.
                try {
                    if (!call.isCanceled()) {
                        callback.onError(e)
                    }
                } finally {
                    cancellable.setFinished()
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    callback.onSuccess(toResponse(request, HttpCore.toResult(response)))
                } catch (e: Exception) {
                    callback.onError(e)
                } finally {
                    cancellable.setFinished()
                }
            }
        })

        return cancellable
    }

    companion object {
        @Throws(ReCaptchaException::class)
        private fun toResponse(request: Request, result: HttpCore.Result): Response {
            if (result.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            // The raw body matters: SABR responses are binary UMP, and decoding
            // them as text would corrupt them.
            return Response(
                result.code,
                result.message,
                result.headers,
                result.text(),
                result.body,
                result.latestUrl,
            )
        }
    }
}
