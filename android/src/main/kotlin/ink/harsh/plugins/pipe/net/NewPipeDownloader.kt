package ink.harsh.plugins.pipe.net

import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.downloader.Downloader
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.downloader.Request
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.downloader.Response
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * [Downloader] for the NewPipe fallback engine.
 *
 * The imports above are the relocated namespace produced by
 * `tools/shade`. They are unrelated types to the identically-named ones
 * PipePipe uses — that separation is the whole reason two engines can be loaded
 * at once. If the relocation prefix in `tools/shade/build.gradle` ever
 * changes, these imports must change with it.
 *
 * Simpler than the PipePipe downloader: this contract has no async path and
 * its `Response` carries no raw byte[], because NewPipe does not implement
 * SABR.
 */
class NewPipeDownloader : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val result = HttpCore.execute(
            request.httpMethod(),
            request.url(),
            request.headers(),
            request.dataToSend(),
            // NewPipe's Request has no followRedirects(); its extractors assume
            // redirects are followed.
            true,
        )

        if (result.code == 429) {
            throw ReCaptchaException("reCaptcha challenge requested", request.url())
        }

        return toResponse(result)
    }

    companion object {
        private fun toResponse(result: HttpCore.Result): Response = Response(
            result.code,
            result.message,
            result.headers,
            result.text(),
            result.latestUrl,
        )
    }
}
