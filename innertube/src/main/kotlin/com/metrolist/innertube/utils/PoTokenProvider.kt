package com.metrolist.innertube.utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Provider for YouTube Proof of Origin (PO) Tokens.
 * PO Tokens are required for some YouTube clients to access age-restricted content
 * and to avoid bot detection.
 */
object PoTokenProvider {
    
    @Serializable
    data class PoTokenResponse(
        val poToken: String,
        val visitorData: String,
        val streamingPoToken: String? = null,
        val expiresAt: Long? = null
    )
    
    @Serializable
    data class PoTokenRequest(
        val visitorData: String? = null,
        val videoId: String? = null
    )
    
    /**
     * Interface for internal poToken generation using WebView.
     * This should be implemented in the app module.
     */
    interface InternalPoTokenGenerator {
        fun generatePoToken(videoId: String, sessionId: String): Pair<String, String>?
    }
    
    private val mutex = Mutex()
    private var cachedToken: PoTokenResponse? = null
    private var cacheExpiry: Long = 0
    
    // Default token validity: 6 hours (YouTube tokens typically last longer)
    private const val DEFAULT_TOKEN_VALIDITY_MS = 6 * 60 * 60 * 1000L
    
    // External server URL for poToken generation
    // Users can set up their own BgUtils server or use a public one
    var serverUrl: String? = null
    
    // Manual poToken override (for users who want to provide their own token)
    var manualPoToken: String? = null
    var manualVisitorData: String? = null
    
    // Internal WebView-based poToken generator
    var internalGenerator: InternalPoTokenGenerator? = null
    
    // Enable internal poToken generation (default: true)
    var useInternalGenerator: Boolean = true
    
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }
    
    /**
     * Get a valid poToken, either from cache, manual override, internal generator, or external server.
     * @param visitorData The visitor data to use for token generation
     * @param videoId Optional video ID for video-specific tokens
     * @return PoTokenResponse or null if unavailable
     */
    suspend fun getPoToken(visitorData: String?, videoId: String? = null): PoTokenResponse? {
        // Check manual override first
        manualPoToken?.let { token ->
            return PoTokenResponse(
                poToken = token,
                visitorData = manualVisitorData ?: visitorData ?: ""
            )
        }
        
        // Try internal WebView-based generator
        if (useInternalGenerator && videoId != null) {
            val sessionId = visitorData ?: ""
            try {
                internalGenerator?.generatePoToken(videoId, sessionId)?.let { (playerPot, streamingPot) ->
                    return PoTokenResponse(
                        poToken = playerPot,
                        visitorData = visitorData ?: "",
                        streamingPoToken = streamingPot
                    )
                }
            } catch (e: Exception) {
                // Fall through to external server
            }
        }
        
        return mutex.withLock {
            // Check cache
            val now = System.currentTimeMillis()
            cachedToken?.let { cached ->
                if (now < cacheExpiry) {
                    return@withLock cached
                }
            }
            
            // Fetch from external server
            val url = serverUrl ?: return@withLock null
            
            try {
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(PoTokenRequest(
                        visitorData = visitorData,
                        videoId = videoId
                    ))
                }.body<PoTokenResponse>()
                
                // Cache the token
                cachedToken = response
                cacheExpiry = response.expiresAt ?: (now + DEFAULT_TOKEN_VALIDITY_MS)
                
                response
            } catch (e: Exception) {
                // Log error but don't crash - poToken is optional for some clients
                null
            }
        }
    }
    
    /**
     * Clear the cached token.
     */
    fun clearCache() {
        cachedToken = null
        cacheExpiry = 0
    }
    
    /**
     * Set manual poToken and visitorData.
     * This is useful for users who want to provide their own token from browser.
     */
    fun setManualToken(poToken: String?, visitorData: String?) {
        manualPoToken = poToken
        manualVisitorData = visitorData
        clearCache()
    }
    
    /**
     * Check if poToken is available (either manual, internal generator, or from server).
     */
    fun isAvailable(): Boolean {
        return manualPoToken != null || (useInternalGenerator && internalGenerator != null) || serverUrl != null
    }
}
