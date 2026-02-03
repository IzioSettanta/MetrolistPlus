package com.metrolist.music.utils.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PoTokenGenerator(private val context: Context) {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false

    private val webPoTokenGenLock = Object()
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) {
            return null
        }

        return try {
            getWebClientPoTokenSync(videoId, forceRecreate = false)
        } catch (e: Exception) {
            when (e) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
                    webViewBadImpl = true
                    null
                }
                else -> {
                    Log.e(TAG, "Error getting poToken", e)
                    null
                }
            }
        }
    }

    private fun getWebClientPoTokenSync(videoId: String, forceRecreate: Boolean): PoTokenResult {
        Log.d(TAG, "Web poToken requested: $videoId")

        data class GeneratorState(
            val generator: PoTokenWebView,
            val visitorData: String,
            val streamingPot: String,
            val hasBeenRecreated: Boolean
        )

        val state: GeneratorState = synchronized(webPoTokenGenLock) {
            val shouldRecreate = forceRecreate || 
                webPoTokenGenerator == null || 
                webPoTokenVisitorData == null ||
                webPoTokenStreamingPot == null ||
                webPoTokenGenerator!!.isExpired

            if (shouldRecreate) {
                // Get fresh visitorData from YouTube API
                webPoTokenVisitorData = try {
                    runBlocking { YouTube.visitorData().getOrNull() } ?: YouTube.visitorData
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get fresh visitorData, using existing", e)
                    YouTube.visitorData
                }
                
                Log.d(TAG, "Using visitorData: $webPoTokenVisitorData")

                // Close the current webPoTokenGenerator on the main thread
                webPoTokenGenerator?.let { oldGenerator ->
                    val closeLatch = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            oldGenerator.close()
                        } finally {
                            closeLatch.countDown()
                        }
                    }
                    closeLatch.await(3, TimeUnit.SECONDS)
                }

                // Create a new webPoTokenGenerator
                webPoTokenGenerator = PoTokenWebView.newPoTokenGenerator(context)
                
                // The streaming poToken needs to be generated exactly once before generating
                // any other (player) tokens. Use visitorData as the identifier.
                webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenVisitorData!!)
            }

            GeneratorState(
                webPoTokenGenerator!!,
                webPoTokenVisitorData!!,
                webPoTokenStreamingPot!!,
                shouldRecreate
            )
        }

        val playerPot = try {
            if (videoId.isEmpty()) "" else state.generator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (state.hasBeenRecreated) {
                throw throwable
            } else {
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoTokenSync(videoId = videoId, forceRecreate = true)
            }
        }

        Log.d(TAG, "[$videoId] playerPot=$playerPot, streamingPot=${state.streamingPot}, visitorData=${state.visitorData}")

        return PoTokenResult(playerPot, state.streamingPot)
    }
}
