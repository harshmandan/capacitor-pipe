package ink.harsh.plugins.pipe.youtube

import ink.harsh.plugins.pipe.net.HttpCore
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException

/**
 * HTTP for the attestation flow.
 *
 * <p>Upstream reaches for PipePipeClient's `DownloaderImpl` singleton, which is
 * app-scoped state we do not have. These calls also must not go through the
 * extractor's `Downloader`: they are Google attestation endpoints, not YouTube
 * extraction, and the extractor's downloader applies YouTube cookies and
 * localisation headers that have no business on them.
 */
internal object PipeAttestationHttp {

    /** The BotGuard/GenerateIT endpoints speak this rather than plain JSON. */
    const val PROTOBUF_JSON = "application/json+protobuf"

    fun get(url: String, headers: Map<String, List<String>>): String {
        val result = HttpCore.execute("GET", url, headers, null, true)
        if (result.code != 200) {
            throw SabrProtocolException("Attestation GET failed: ${result.code} for $url")
        }
        return result.text()
    }

    fun post(
        url: String,
        headers: Map<String, List<String>>,
        body: ByteArray,
    ): String {
        val result = HttpCore.execute("POST", url, headers, body, true)
        if (result.code != 200) {
            throw SabrProtocolException("Attestation POST failed: ${result.code} for $url")
        }
        return result.text()
    }
}
