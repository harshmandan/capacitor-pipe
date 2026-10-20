/*
 * Ported from PipePipeClient's YoutubePageAttestationBootstrap.kt.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/youtube/YoutubePageAttestationBootstrap.kt
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 *
 * Kept in Kotlin, and kept as close to upstream as possible on purpose: this
 * scrapes YouTube's home HTML for the ytcfg blocks and the initial BotGuard
 * attestation challenge, so it breaks whenever YouTube reshapes that page.
 * Staying near-verbatim means an upstream fix arrives as a diff rather than a
 * re-translation. Only the package declaration differs.
 */
package ink.harsh.plugins.pipe.youtube

import ink.harsh.plugins.pipe.util.PipeObf

import com.grack.nanojson.JsonParser
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException

internal enum class YoutubePoTokenBinding {
    CONTENT,
    SESSION,
    NONE,
}

internal data class PageAttestationBootstrap(
    val visitorData: String,
    val dataSyncId: String?,
    val clientName: String,
    val clientVersion: String,
    val binding: YoutubePoTokenBinding,
    val eventId: String,
    val challenge: SabrAttChallengeData,
)

internal data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String?,
    val interpreterUrl: String?,
)

internal fun parsePageAttestationBootstrap(
    pageHtml: String,
): PageAttestationBootstrap {
    val configCalls = extractObjectCallArguments(pageHtml, YTCFG_CALLEE)
        .mapNotNull { call ->
            try {
                call to JsonParser.`object`().from(call.argument)
            } catch (_: Exception) {
                null
            }
        }
    val challengeCall = extractObjectCallArguments(pageHtml, INITIAL_ATTESTATION_CALLEE)
        .asSequence()
        .mapNotNull { call -> parseInitialAttestationChallenge(call.argument)?.let { call to it } }
        .firstOrNull()
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u0006\u001e\u0018\u0006\u001a\u0015\u0019\u0056\u0016\u000c\u000d\u001f\u0008\u0008\u001c\u000a\u0016\u00ef\u00ef\u00a2\u00e0\u00ec\u00e4\u00ea\u00eb\u00ed\u00e7\u00ed\u00ee"))
    val configs = configCalls
        .filter { (call) -> call.start < challengeCall.first.start }
        .map { (_, config) -> config }
    val clientConfig = configs.lastOrNull { it.has("INNERTUBE_CONTEXT") }
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0015\u0018\u0016\u000d\u001f\u0003\u0008"))
    val eventId = configs.asSequence()
        .mapNotNull { it.getString("EVENT_ID")?.takeIf(String::isNotEmpty) }
        .lastOrNull()
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u002a\u0026\u0034\u003c\u0027\u002b\u003c\u0032"))
    val visitorData = (
        clientConfig.getString("EOM_VISITOR_DATA")?.takeIf(String::isNotEmpty)
            ?: clientConfig.getString("VISITOR_DATA")?.takeIf(String::isNotEmpty)
        )?.replace("%3D", "=", ignoreCase = true)
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000e\u001e\u001e\u001c\u000a\u0019\u001a\u0003\u0004\u0058\u000f\u0013\u0008\u0015\u0009\u0011\u000d\u00a0\u00e5\u00e3\u00f7\u00e5"))
    val dataSyncId = configs.asSequence()
        .mapNotNull { it.getString("DATASYNC_ID")?.takeIf(String::isNotEmpty) }
        .lastOrNull()
    val client = clientConfig.getObject("INNERTUBE_CONTEXT")?.getObject("client")
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u0026\u001e\u001f\u0017\u0001\u0000\u0000\u0014\u0012\u0058\u001a\u0016\u0012\u0019\u0013\u000a\u005f\u00e3\u00ee\u00ec\u00f7\u00e1\u00fd\u00f2"))
    val clientName = client.getString("clientName")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0018\u0016\u0015\u001c"))
    if (clientName != "WEB") {
        throw SabrProtocolException(PipeObf.d("\u000e\u0032\u002e\u002b\u002f\u0010\u000e\u0010\u0017\u0001\u0001\u0046\u003e\u0007\u001c\u003e\u001e\u000e\u0008\u004e\u0007\u001f\u001c\u0017\u0053\u0017\u0019\u001f\u0012\u0016\u000d\u0040\u005b") + clientName)
    }
    val clientVersion = client.getString("clientVersion")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException(PipeObf.d("\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0000\u0012\u000a\u000a\u0013\u0014\u0012"))
    val watchConfig = clientConfig.getObject("WEB_PLAYER_CONTEXT_CONFIGS")
        ?.getObject("WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH")
    val experimentFlags = watchConfig?.getString("serializedExperimentFlags")
        ?.let(::parseExperimentFlags)
        .orEmpty()
    val binding = when {
        experimentFlags["html5_generate_content_po_token"] == "true" -> {
            YoutubePoTokenBinding.CONTENT
        }
        experimentFlags["html5_generate_session_po_token"] == "true" -> {
            YoutubePoTokenBinding.SESSION
        }
        watchConfig == null -> YoutubePoTokenBinding.CONTENT
        else -> YoutubePoTokenBinding.NONE
    }
    return PageAttestationBootstrap(
        visitorData,
        dataSyncId,
        clientName,
        clientVersion,
        binding,
        eventId,
        challengeCall.second,
    )
}

private fun parseInitialAttestationChallenge(argument: String): SabrAttChallengeData? {
    val responseProperty = INITIAL_ATTESTATION_RESPONSE.find(argument) ?: return null
    val quote = responseProperty.groupValues[1].single()
    val rawChallenge = try {
        decodeJavascriptString(argument, responseProperty.range.last + 1, quote)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return try {
        parseSabrAttChallengeData(rawChallenge)
    } catch (_: Exception) {
        null
    }
}

internal fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = JsonParser.`object`().from(rawAttestationData).getObject("bgChallenge")
        ?: throw SabrProtocolException("Attestation response has no BotGuard challenge")
    val interpreterJavascript = challenge.getObject("interpreterJavascript")
        ?.getString("privateDoNotAccessOrElseSafeScriptWrappedValue")
        ?.takeIf(String::isNotEmpty)
    val rawInterpreterUrl = challenge.getObject("interpreterUrl")
        ?.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
        ?.takeIf(String::isNotEmpty)
    val interpreterUrl = rawInterpreterUrl?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    if (interpreterJavascript == null && interpreterUrl == null) {
        throw SabrProtocolException("Attestation challenge has no interpreter script or URL")
    }
    val program = challenge.getString("program")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("Attestation challenge has no program")
    val globalName = challenge.getString("globalName")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("Attestation challenge has no global name")
    return SabrAttChallengeData(program, globalName, interpreterJavascript, interpreterUrl)
}

private data class JavascriptObjectCall(
    val start: Int,
    val argument: String,
)

private fun extractObjectCallArguments(
    source: String,
    callee: String,
): List<JavascriptObjectCall> {
    val arguments = ArrayList<JavascriptObjectCall>()
    var searchFrom = 0
    while (true) {
        val callStart = source.indexOf(callee, searchFrom)
        if (callStart < 0) return arguments
        var openingParenthesis = callStart + callee.length
        while (openingParenthesis < source.length && source[openingParenthesis].isWhitespace()) {
            openingParenthesis++
        }
        if (openingParenthesis >= source.length || source[openingParenthesis] != '(') {
            searchFrom = callStart + callee.length
            continue
        }
        var objectStart = openingParenthesis + 1
        while (objectStart < source.length && source[objectStart].isWhitespace()) objectStart++
        if (objectStart >= source.length || source[objectStart] != '{') {
            searchFrom = openingParenthesis + 1
            continue
        }
        val objectEnd = findJavascriptObjectEnd(source, objectStart)
        if (objectEnd < 0) {
            searchFrom = objectStart + 1
            continue
        }
        arguments.add(JavascriptObjectCall(callStart, source.substring(objectStart, objectEnd + 1)))
        searchFrom = objectEnd + 1
    }
}

private fun findJavascriptObjectEnd(source: String, start: Int): Int {
    var depth = 0
    var quote = '\u0000'
    var escaped = false
    for (index in start until source.length) {
        val character = source[index]
        if (quote != '\u0000') {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                quote = '\u0000'
            }
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun parseExperimentFlags(serializedFlags: String): Map<String, String> {
    return serializedFlags.split('&').associate { part ->
        val separator = part.indexOf('=')
        if (separator < 0) part to "true"
        else part.substring(0, separator) to part.substring(separator + 1)
    }
}

private fun decodeJavascriptString(source: String, start: Int, quote: Char): String {
    val result = StringBuilder()
    var index = start
    while (index < source.length) {
        val character = source[index++]
        if (character == quote) return result.toString()
        if (character != '\\') {
            result.append(character)
            continue
        }
        require(index < source.length) { "Incomplete JavaScript string escape" }
        when (val escaped = source[index++]) {
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'v' -> result.append('\u000B')
            'x' -> {
                result.append(readJavascriptHex(source, index, 2).toChar())
                index += 2
            }
            'u' -> {
                result.append(readJavascriptHex(source, index, 4).toChar())
                index += 4
            }
            '\n' -> Unit
            '\r' -> if (index < source.length && source[index] == '\n') index++
            else -> result.append(escaped)
        }
    }
    throw IllegalArgumentException("Unterminated JavaScript string")
}

private fun readJavascriptHex(source: String, start: Int, length: Int): Int {
    require(start + length <= source.length) { "Incomplete hexadecimal escape" }
    var value = 0
    repeat(length) { offset ->
        val digit = source[start + offset].digitToIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid hexadecimal escape")
        value = value * 16 + digit
    }
    return value
}

private const val YTCFG_CALLEE = "ytcfg.set"
private const val INITIAL_ATTESTATION_CALLEE = "window.ytAtN"
private val INITIAL_ATTESTATION_RESPONSE = Regex("['\"]R['\"]\\s*:\\s*(['\"])")
