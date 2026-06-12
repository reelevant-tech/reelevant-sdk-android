package com.reelevant.analytics_android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Secure
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ReelevantLogger {
    companion object {
        fun debug(message: String) {
            Log.d("REELEVANT SDK", message)
        }
    }
}

class ReelevantAnalytics {
    companion object {
        const val USER_ID: String = "UserId"
        const val TEMPORARY_USER_ID: String = "TemporaryUserId"
        const val FAIL_QUEUE: String = "FailQueue"
    }
}

class Configuration(var companyId: String, var endpoint: String) {
    var retry: Long = 60
    var currentUrl: String? = null
}

class Event(val name: String, val payload: Map<String, Any>)

class ReelevantSDK(
    private var context: Context,
    companyId: String,
    datasourceId: String,
    private val runnerUrl: String = DEFAULT_RUNNER_URL,
    private val personalizationTimeout: Long = DEFAULT_TIMEOUT,
    private val fallback: FallbackStrategy = FallbackStrategy.Empty
) {
    private val sharedPreferencesId = "reelevant-analytics"
    private val configuration: Configuration = Configuration(
        companyId,
        "https://collector.reelevant.com/collect/$datasourceId/rlvt"
    )
    private val temporaryUserIdFallback = this.randomIdentifier()

    init {
        val mainHandler = Handler(Looper.getMainLooper())
        val delay = this.configuration.retry
        mainHandler.post(object : Runnable {
            override fun run() {
                CoroutineScope(Dispatchers.IO).launch {
                    handleFailQueue()
                }
                mainHandler.postDelayed(this, delay)
            }
        })
    }

    /**
    Set the current user (`clientId` property)
     */
    suspend fun setUser(userId: String) {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        val currentValue = sharedPreferences.getString(ReelevantAnalytics.USER_ID, null)
        if (currentValue != userId) {
            val edit = sharedPreferences.edit()
            edit.putString(ReelevantAnalytics.USER_ID, userId)
            edit.apply()
            this.send(this.custom("identify", emptyMap()))
        }
    }

    /**
    Set the current URL
     */
    fun setCurrentURL(url: String) {
        this.configuration.currentUrl = url
    }

    /**
    Return a `page_view` event.
     */
    fun pageView(labels: Map<String, String>): Event {
        return Event("page_view", labels)
    }

    /**
    Return a `add_cart` event.
     */
    fun addCart(ids: List<String>, labels: Map<String, String>): Event {
        val map = mapOf("ids" to ids)
        return Event("add_cart", map + labels)
    }

    /**
    Return a `purchase` event.
     */
    fun purchase(
        ids: List<String>,
        totalAmount: Double,
        transId: String,
        labels: Map<String, String>
    ): Event {
        val map = mapOf(
            "ids" to ids,
            "value" to totalAmount,
            "transId" to transId
        )
        return Event("purchase", map + labels)
    }

    /**
    Return a `product_page` event.
     */
    fun productPage(id: String, labels: Map<String, String>): Event {
        val map = mapOf("id" to id)
        return Event("product_page", map + labels)
    }

    /**
    Return a `category_view` event.
     */
    fun categoryView(id: String, labels: Map<String, String>): Event {
        val map = mapOf("id" to id)
        return Event("category_view", map + labels)
    }

    /**
    Return a `brand_view` event.
     */
    fun brandView(id: String, labels: Map<String, String>): Event {
        val map = mapOf("id" to id)
        return Event("brand_view", map + labels)
    }

    /**
    Return a `product_hover` event.
     */
    fun productHover(id: String, labels: Map<String, String>): Event {
        val map = mapOf("id" to id)
        return Event("product_hover", map + labels)
    }

    /**
    Return a `<name>` event.
     */
    fun custom(name: String, labels: Map<String, String>): Event {
        return Event(name, labels)
    }

    /**
    Use this method to trigger an event with the associated payload and labels
    You should build event with the `EventBuilder` class:
    ```
    let event = EventBuilder.page_view(labels=[:])
    sdk.send(event)
    ```
     */
    suspend fun send(event: Event) {
        this.publishEvent(event.name, event.payload)
    }

    private fun getFailQueue(): JSONArray {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        val failedQueueString = sharedPreferences.getString(ReelevantAnalytics.FAIL_QUEUE, "[]")
        val json = JSONArray(failedQueueString)
        return json
    }

    private fun pushToFailQueue(data: JSONObject) {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        val failQueueString = sharedPreferences.getString(ReelevantAnalytics.FAIL_QUEUE, "[]")
        val json = JSONArray(failQueueString)
        json.put(data)
        val editor = sharedPreferences.edit()
        editor.putString(ReelevantAnalytics.FAIL_QUEUE, json.toString())
        editor.apply()
    }

    private fun clearFailQueue() {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.remove(ReelevantAnalytics.FAIL_QUEUE)
        editor.apply()
    }

    private suspend fun handleFailQueue() {
        val failQueue = this.getFailQueue()
        this.clearFailQueue()
        for (index in 0 until failQueue.length()) {
            val data = failQueue.getJSONObject(index)
            val timestamp = data.getLong("timestamp")
            if (timestamp >= (this.getCurrentTimeMillis() - 15 * 60 * 1000)) {
                this.sendToNetwork(data)
            }
        }
    }

    /**
    Build and send the event to the network
     */
    private suspend fun publishEvent(name: String, payload: Map<String, Any>) {
        val builtEvent = this.buildEventPayload(name, payload)
        val data = JSONObject(builtEvent)
        this.sendToNetwork(data)
    }

    /**
    Send built event to the network
     */
    private suspend fun sendToNetwork(body: JSONObject) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(this.configuration.endpoint)
            connection = withContext(Dispatchers.IO) {
                url.openConnection()
            } as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val outputStream = connection.outputStream
            withContext(Dispatchers.IO) {
                outputStream.write(body.toString().toByteArray())
                outputStream.close()
            }
            connection.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("REELEVANT_SDK", e.message.toString())
            this.pushToFailQueue(body)
        } finally {
            connection?.disconnect()
        }
    }

    /**
    Build the event payload
     */
    private suspend fun buildEventPayload(
        name: String,
        payload: Map<String, Any>
    ): Map<String, Any?> {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        val temporaryUserId = this.getTemporaryUserId()
        val userId = sharedPreferences.getString(ReelevantAnalytics.USER_ID, null)
        val currentUrl = this.configuration.currentUrl ?: "unknown"
        return mapOf(
            "key" to this.configuration.companyId,
            "name" to name,
            "url" to currentUrl,
            "tmpId" to temporaryUserId,
            "clientId" to userId,
            "data" to payload,
            "eventId" to this.randomIdentifier(),
            "v" to 1,
            "timestamp" to this.getCurrentTimeMillis()
        )
    }

    /**
    Generate random identifier (used for `tmpId` when unable to get IOS uuid, or for eventId)
     */
    private fun randomIdentifier(): String {
        val allowedCharacters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..25)
            .map { allowedCharacters.random() }
            .joinToString("")
    }

    private fun getCurrentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    private fun getGooglePlayServicesAdvertisingID(): String? {
        val advertisingInfo =
            Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
                .getMethod("getAdvertisingIdInfo", Context::class.java)
                .invoke(null, this.context)
        val isLimitAdTrackingEnabled =
            advertisingInfo
                .javaClass
                .getMethod("isLimitAdTrackingEnabled")
                .invoke(advertisingInfo) as Boolean
        if (isLimitAdTrackingEnabled) {
            ReelevantLogger.debug("Not collecting advertising ID because isLimitAdTrackingEnabled (Google Play Services) is true.")
            return null
        }
        val advertisingId =
            advertisingInfo.javaClass.getMethod("getId").invoke(advertisingInfo) as String
        return advertisingId
    }

    private fun getAmazonFireAdvertisingID(context: Context): String? {
        val contentResolver = context.contentResolver

        // Ref: http://prateeks.link/2uGs6bf
        // limit_ad_tracking != 0 indicates user wants to limit ad tracking.
        val limitAdTracking = Secure.getInt(contentResolver, "limit_ad_tracking") != 0

        if (limitAdTracking) {
            ReelevantLogger.debug("Not collecting advertising ID because limit_ad_tracking (Amazon Fire OS) is true.")
            return null
        }

        val advertisingId = Secure.getString(contentResolver, "advertising_id")
        return advertisingId
    }

    private suspend fun getAdvertisingID(): String? {
        val instance = this
        try {
            return withContext(Dispatchers.IO) {
                instance.getGooglePlayServicesAdvertisingID()
            }
        } catch (e: java.lang.Exception) {
            ReelevantLogger.debug("Unable to collect advertising ID from Google Play Services.")
        }
        try {
            return withContext(Dispatchers.IO) {
                instance.getAmazonFireAdvertisingID(context)
            }
        } catch (e: java.lang.Exception) {
            ReelevantLogger.debug("Unable to collect advertising ID from Amazon Fire OS.")
        }
        ReelevantLogger.debug("Unable to collect advertising ID from Amazon Fire OS and Google Play Services.")
        return this.randomIdentifier()
    }

    // ---------------------------------------------------------------------------
    // Personalization API
    // ---------------------------------------------------------------------------

    /**
    Execute a single workflow run.
    Returns a typed RunResult with a discriminated `body` union.
    userId is auto-resolved from stored identity (setUser / tmpId) unless overridden in options.
     */
    suspend fun run(options: RunOptions): RunResult {
        try {
            val effectiveUserId = options.userId ?: resolveUserId()
            val result = executeRunnerCall(options, runnerUrl, personalizationTimeout, effectiveUserId)
            return result
        } catch (e: Exception) {
            return handleRunError(options, e)
        }
    }

    /**
    Execute multiple workflow runs in parallel.
    Returns results in the same order as the input options.
     */
    suspend fun runAll(optionsList: List<RunOptions>): List<RunResult> {
        return coroutineScope {
            optionsList.map { opts -> async { run(opts) } }.awaitAll()
        }
    }

    private suspend fun resolveUserId(): String {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        return sharedPreferences.getString(ReelevantAnalytics.USER_ID, null)
            ?: this.getTemporaryUserId()
    }

    private suspend fun handleRunError(options: RunOptions, err: Exception): RunResult {
        return when (fallback) {
            is FallbackStrategy.Error -> throw err
            is FallbackStrategy.Custom -> fallback.handler(options, err)
            is FallbackStrategy.Empty -> RunResult(
                status = 0,
                source = RunSource.FALLBACK,
                body = RunContent.Empty,
                metadata = emptyMap(),
                properties = emptyMap(),
                runId = null,
                executionPath = emptyList(),
                redirectionUrl = "",
                onTrackClick = {}
            )
        }
    }

    private suspend fun getTemporaryUserId(): String {
        val sharedPreferences =
            this.context.getSharedPreferences(this.sharedPreferencesId, Context.MODE_PRIVATE)
        var temporaryUserId =
            sharedPreferences.getString(ReelevantAnalytics.TEMPORARY_USER_ID, null)
        if (temporaryUserId != null) {
            return temporaryUserId
        }
        val advertisingId = this.getAdvertisingID()
        temporaryUserId = advertisingId ?: this.temporaryUserIdFallback
        val editor = sharedPreferences.edit()
        editor.putString(ReelevantAnalytics.TEMPORARY_USER_ID, temporaryUserId)
        editor.apply()
        return temporaryUserId
    }
}
