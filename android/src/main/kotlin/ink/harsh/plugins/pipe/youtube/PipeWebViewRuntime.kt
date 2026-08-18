/*
 * Ported from PipePipeClient's SharedWebViewRuntime.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/SharedWebViewRuntime.java
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 * Converted from Java to Kotlin; behaviour unchanged.
 *
 * A single headless WebView, kept alive for the process, exposing a stable JS
 * bridge. BotGuard only runs in a real DOM with a real JavaScript engine —
 * there is no headless substitute, which is why a plugin that otherwise does no
 * UI work has to own a WebView.
 *
 * Modifications from upstream:
 *   - renamed and repackaged
 *   - BuildConfig.DEBUG replaced with a local constant (a library module has no
 *     app BuildConfig to read)
 *
 * The JS bridge name and the pipepipeSabr* entry points are deliberately
 * UNCHANGED so assets/sabr_po_token.js can be updated from upstream verbatim.
 */
package ink.harsh.plugins.pipe.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single headless WebView used by local JavaScript services.
 *
 * The runtime loads one blank first-party page, keeps it alive for the app process, and exposes
 * a stable JavaScript bridge. Callers keep their own JS namespaces and serialize their public entry
 * points on the caller side.
 */
class PipeWebViewRuntime private constructor(context: Context) {

    interface InitializationFailureCallback {
        fun onInitializationFailure(throwable: Throwable)
    }

    interface SabrLocalDomCallbacks {
        fun onJsInitializationError(error: String)

        fun onRunBotguardResult(botguardResponse: String)

        fun onMinterReady()

        fun onObtainPoTokenResult(identifier: String, poTokenU8: String)

        fun onObtainPoTokenError(identifier: String, error: String)
    }

    private val appContext: Context = context.applicationContext
    private val webViewContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()
    private val sabrLocalDomCallbacks = ConcurrentHashMap<String, SabrLocalDomCallbacks>()

    private var initLatch: CountDownLatch? = null
    private var initError: AtomicReference<Throwable>? = null
    private var activeInitializationAttempt: InitializationAttempt? = null
    private var nextInitializationAttemptId: Long = 0
    private var initializationFailureCallback: InitializationFailureCallback? = null
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    init {
        val configuration = Configuration(appContext.resources.configuration)
        webViewContext = appContext.createConfigurationContext(configuration)
    }

    @JvmOverloads
    fun warmUp(failureCallback: InitializationFailureCallback? = null) {
        val existingFailure: Throwable?
        synchronized(initLock) {
            if (failureCallback != null) {
                initializationFailureCallback = failureCallback
            }
            if (ready || initLatch != null) {
                existingFailure = initError?.get()
                if (existingFailure == null) {
                    return
                }
            } else {
                startInitializationLocked()
                return
            }
        }
        if (failureCallback != null && existingFailure != null) {
            failureCallback.onInitializationFailure(existingFailure)
        }
    }

    @Throws(Exception::class)
    fun ensureReady(timeoutMs: Long, operation: String) {
        val latch: CountDownLatch?
        val error: AtomicReference<Throwable>?
        synchronized(initLock) {
            if (ready) {
                return
            }
            if (initLatch == null) {
                startInitializationLocked()
            }
            latch = initLatch
            error = initError
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("$operation cannot wait on the main thread")
        }
        if (latch == null || error == null) {
            throw IllegalStateException("$operation did not start WebView initialization")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("$operation timed out waiting for WebView runtime")
        }
        val failure = error.get()
        if (failure != null) {
            throw IllegalStateException(
                "$operation failed to initialize WebView runtime",
                failure,
            )
        }
    }

    @Throws(Exception::class)
    fun evaluateJavascriptBlocking(
        script: String,
        timeoutMs: Long,
        operation: String,
    ): String {
        ensureReady(timeoutMs, operation)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("$operation cannot wait on the main thread")
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        if (!mainHandler.post {
                try {
                    val view = webView ?: throw IllegalStateException(
                        "WebView runtime is not initialized",
                    )
                    view.evaluateJavascript(script) { value ->
                        result.set(value)
                        latch.countDown()
                    }
                } catch (throwable: Throwable) {
                    error.set(throwable)
                    latch.countDown()
                }
            }
        ) {
            throw IllegalStateException("$operation could not post JavaScript evaluation")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("$operation timed out")
        }
        val failure = error.get()
        if (failure != null) {
            throw IllegalStateException("$operation failed", failure)
        }
        return result.get()
    }

    fun evaluateJavascript(
        script: String,
        callback: ValueCallback<String>?,
        errorCallback: ValueCallback<Throwable>?,
    ): Boolean {
        try {
            ensureReady(DEFAULT_TIMEOUT_MS, "async JavaScript evaluation")
        } catch (throwable: Throwable) {
            errorCallback?.onReceiveValue(throwable)
            return false
        }
        return mainHandler.post {
            try {
                val view = webView ?: throw IllegalStateException(
                    "WebView runtime is not initialized",
                )
                view.evaluateJavascript(script, callback)
            } catch (throwable: Throwable) {
                errorCallback?.onReceiveValue(throwable)
            }
        }
    }

    fun loadAsset(path: String): String {
        try {
            // kotlin.io, replacing a hand-written buffer loop. Same UTF-8
            // decode, minus the deprecated charset-name overload of toString.
            return appContext.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalStateException("Could not load asset $path", e)
        }
    }

    fun registerSabrLocalDomCallbacks(callbacks: SabrLocalDomCallbacks): String {
        val id = UUID.randomUUID().toString()
        sabrLocalDomCallbacks[id] = callbacks
        return id
    }

    fun unregisterSabrLocalDomCallbacks(id: String) {
        sabrLocalDomCallbacks.remove(id)
    }

    private fun startInitializationLocked() {
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val attempt = InitializationAttempt(++nextInitializationAttemptId, 1, latch, error)
        initLatch = latch
        initError = error
        activeInitializationAttempt = attempt
        if (!mainHandler.post { createWebView(attempt) }) {
            val exception = IllegalStateException("Could not post WebView creation")
            activeInitializationAttempt = null
            error.set(exception)
            latch.countDown()
            notifyInitializationFailure(exception)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(attempt: InitializationAttempt) {
        if (!isActiveInitializationAttempt(attempt)) {
            return
        }
        try {
            Log.i(
                TAG,
                "creating WebView attempt=" + attempt.number + " elapsedMs=" + attempt.elapsedMs(),
            )
            val view = WebView(webViewContext)
            attempt.view = view
            Log.i(
                TAG,
                "created WebView attempt=" + attempt.number + " elapsedMs=" + attempt.elapsedMs(),
            )
            if (!isActiveInitializationAttempt(attempt)) {
                destroyWebView(view)
                return
            }
            if (DEBUG_LOGGING) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.userAgentString = USER_AGENT
            settings.blockNetworkLoads = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
            }
            view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (DEBUG_LOGGING) {
                        Log.d(
                            TAG,
                            "console " + message.messageLevel() + ' ' +
                                message.message() + " @" + message.sourceId() +
                                ':' + message.lineNumber(),
                        )
                    }
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // WebView 44/83 occasionally misses this callback for a headless local page.
                    // Readiness is therefore determined only by the local document's bridge call.
                    Log.i(
                        TAG,
                        "page finished url=" + url + " attempt=" + attempt.number +
                            " elapsedMs=" + attempt.elapsedMs(),
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    webError: WebResourceError,
                ) {
                    super.onReceivedError(view, request, webError)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame) {
                        retryOrFail(
                            attempt,
                            IllegalStateException(
                                "WebView runtime main frame error " + webError.errorCode +
                                    ": " + webError.description,
                            ),
                        )
                    }
                }
            }
            view.loadDataWithBaseURL(
                "https://www.youtube.com/",
                runtimeDocument(attempt.id),
                "text/html",
                "UTF-8",
                null,
            )
            Log.i(
                TAG,
                "load dispatched attempt=" + attempt.number + " elapsedMs=" + attempt.elapsedMs(),
            )
            mainHandler.postDelayed(
                {
                    retryOrFail(
                        attempt,
                        IllegalStateException(
                            "WebView runtime ready callback timed out after " +
                                READY_CALLBACK_ATTEMPT_TIMEOUT_MS + " ms",
                        ),
                    )
                },
                READY_CALLBACK_ATTEMPT_TIMEOUT_MS,
            )
        } catch (throwable: Throwable) {
            retryOrFail(attempt, throwable)
        }
    }

    private fun completeInitialization(attempt: InitializationAttempt) {
        if (!attempt.completed.compareAndSet(false, true)) {
            return
        }
        val stale: Boolean
        synchronized(initLock) {
            stale = activeInitializationAttempt !== attempt || ready
            if (!stale) {
                webView = attempt.view
                ready = true
                activeInitializationAttempt = null
            }
        }
        if (stale) {
            destroyWebView(attempt.view)
            return
        }
        Log.i(
            TAG,
            "ready source=bridge attempt=" + attempt.number + " elapsedMs=" +
                attempt.elapsedMs() + " mainThread=" +
                (Looper.myLooper() == Looper.getMainLooper()),
        )
        attempt.latch.countDown()
    }

    private fun retryOrFail(attempt: InitializationAttempt, throwable: Throwable) {
        if (!attempt.completed.compareAndSet(false, true)) {
            return
        }
        if (!isActiveInitializationAttempt(attempt)) {
            destroyWebView(attempt.view)
            return
        }
        destroyWebView(attempt.view)
        if (attempt.number < MAX_READY_CALLBACK_ATTEMPTS) {
            val retry: InitializationAttempt
            synchronized(initLock) {
                if (activeInitializationAttempt !== attempt || ready) {
                    return
                }
                retry = InitializationAttempt(
                    ++nextInitializationAttemptId,
                    attempt.number + 1,
                    attempt.latch,
                    attempt.error,
                )
                activeInitializationAttempt = retry
            }
            Log.w(
                TAG,
                "retrying WebView runtime ready callback after attempt " + attempt.number +
                    " elapsedMs=" + attempt.elapsedMs(),
                throwable,
            )
            if (!mainHandler.post { createWebView(retry) }) {
                retryOrFail(retry, IllegalStateException("Could not post WebView retry"))
            }
            return
        }
        synchronized(initLock) {
            if (activeInitializationAttempt !== attempt || ready) {
                return
            }
            activeInitializationAttempt = null
            attempt.error.compareAndSet(null, throwable)
        }
        Log.e(
            TAG,
            "WebView runtime ready callback failed attempt=" + attempt.number +
                " elapsedMs=" + attempt.elapsedMs(),
            throwable,
        )
        attempt.latch.countDown()
        notifyInitializationFailure(throwable)
    }

    private fun isActiveInitializationAttempt(attempt: InitializationAttempt): Boolean {
        synchronized(initLock) {
            return activeInitializationAttempt === attempt && initLatch === attempt.latch &&
                initError === attempt.error && !ready
        }
    }

    private fun notifyInitializationFailure(throwable: Throwable) {
        val callback: InitializationFailureCallback?
        synchronized(initLock) {
            callback = initializationFailureCallback
        }
        callback?.onInitializationFailure(throwable)
    }

    private class InitializationAttempt(
        val id: Long,
        val number: Int,
        val latch: CountDownLatch,
        val error: AtomicReference<Throwable>,
    ) {
        val completed = AtomicBoolean()
        private val startedAtMs = SystemClock.elapsedRealtime()

        var view: WebView? = null

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAtMs
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onRuntimeDocumentReady(attemptId: String?) {
            mainHandler.post {
                val attempt = synchronized(initLock) {
                    val active = activeInitializationAttempt
                    if (active == null || active.id.toString() != attemptId) {
                        null
                    } else {
                        active
                    }
                } ?: return@post
                completeInitialization(attempt)
            }
        }

        @JavascriptInterface
        fun onSabrLocalDomJsInitializationError(sessionId: String?, error: String?) {
            val callbacks = sabrLocalDomCallbacks[sessionId]
            callbacks?.onJsInitializationError(error ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomRunBotguardResult(sessionId: String?, botguardResponse: String?) {
            val callbacks = sabrLocalDomCallbacks[sessionId]
            callbacks?.onRunBotguardResult(botguardResponse ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomMinterReady(sessionId: String?) {
            val callbacks = sabrLocalDomCallbacks[sessionId]
            callbacks?.onMinterReady()
        }

        @JavascriptInterface
        fun onSabrLocalDomObtainPoTokenResult(
            sessionId: String?,
            identifier: String?,
            poTokenU8: String?,
        ) {
            val callbacks = sabrLocalDomCallbacks[sessionId]
            callbacks?.onObtainPoTokenResult(identifier ?: "", poTokenU8 ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomObtainPoTokenError(
            sessionId: String?,
            identifier: String?,
            error: String?,
        ) {
            val callbacks = sabrLocalDomCallbacks[sessionId]
            callbacks?.onObtainPoTokenError(identifier ?: "", error ?: "")
        }
    }

    companion object {
        /** Verbose WebView/console logging. Off by default; this runs in consumers' apps. */
        private const val DEBUG_LOGGING = false

        const val BRIDGE_NAME = "PipePipeWebViewBridge"

        private const val TAG = "PipeWebViewRuntime"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val READY_CALLBACK_ATTEMPT_TIMEOUT_MS = 5_000L
        private const val MAX_READY_CALLBACK_ATTEMPTS = 2

        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        @Volatile
        private var instance: PipeWebViewRuntime? = null

        @JvmStatic
        fun get(context: Context): PipeWebViewRuntime {
            var runtime = instance
            if (runtime == null) {
                synchronized(PipeWebViewRuntime::class.java) {
                    runtime = instance
                    if (runtime == null) {
                        runtime = PipeWebViewRuntime(context)
                        instance = runtime
                    }
                }
            }
            return runtime!!
        }

        @JvmStatic
        fun warmUp(context: Context) {
            get(context).warmUp()
        }

        private fun runtimeDocument(attemptId: Long): String =
            "<!doctype html><html><head><script>" +
                "PipePipeWebViewBridge.onRuntimeDocumentReady('" + attemptId + "');" +
                "</script><title></title></head><body></body></html>"

        private fun destroyWebView(view: WebView?) {
            if (view == null) {
                return
            }
            try {
                view.stopLoading()
                view.destroy()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Could not destroy failed WebView initialization attempt", throwable)
            }
        }
    }
}
