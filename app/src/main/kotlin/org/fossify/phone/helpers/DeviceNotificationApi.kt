package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.RestrictionsManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import org.fossify.phone.BuildConfig
import org.json.JSONObject
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/**
 * Phone reports pile / looked / clear only. No caller id on the wire.
 * PhonlyAPI owns the wait and NOTIFY_DEVICE.
 * Slot JSON is cardStatus / reminderStartedAt / cardShownAt only.
 */
internal object DeviceNotificationApi {
    const val TAG = "PhonlyCue"
    const val SOURCE = "phone"

    enum class CardStatus {
        IDLE, WAITING, SHOWN;

        val wire: String
            get() = when (this) {
                IDLE -> "idle"
                WAITING -> "waiting"
                SHOWN -> "shown"
            }

        companion object {
            fun fromWire(raw: String?): CardStatus = when (raw?.trim()) {
                "waiting" -> WAITING
                "shown" -> SHOWN
                else -> IDLE
            }
        }
    }

    data class RemoteState(
        val cardStatus: CardStatus,
        val reminderStartedAt: String?,
        val cardShownAt: String?,
    ) {
        companion object {
            fun idle(cardShownAt: String? = null) = RemoteState(
                cardStatus = CardStatus.IDLE,
                reminderStartedAt = null,
                cardShownAt = cardShownAt,
            )
        }
    }

    data class Attempt(
        val state: RemoteState?,
        val retryable: Boolean,
    )

    fun getState(context: Context): RemoteState? {
        return request(context, "GET", "/api/device/notification", would = "WOULD_GET").state
    }

    fun pile(context: Context): Attempt {
        return request(context, "POST", "/api/device/notification/pile", would = "WOULD_PILE")
    }

    fun looked(context: Context): RemoteState? {
        return request(context, "POST", "/api/device/notification/looked", would = "WOULD_LOOKED").state
    }

    fun clear(context: Context): RemoteState? {
        return request(context, "POST", "/api/device/notification/clear", would = "WOULD_CLEAR").state
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        would: String,
    ): Attempt {
        val imei = deviceImei(context)
        val token = BuildConfig.NOTIFICATION_TOKEN.trim()
        if (imei.isEmpty()) {
            Log.w(TAG, "$would skip: no IMEI")
            return Attempt(state = null, retryable = false)
        }
        if (token.isEmpty()) {
            Log.w(TAG, "$would skip: no token (operator sets DEVICE_NOTIFICATION_LAB_TOKEN after Render env)")
            return Attempt(state = null, retryable = false)
        }
        val base = BuildConfig.NOTIFICATION_API_URL.trim().trimEnd('/')
        val encodedImei = URLEncoder.encode(imei, "UTF-8")
        val url = URL("$base$path?imei=$encodedImei&source=$SOURCE")
        val body = if (method == "POST") {
            JSONObject()
                .put("imei", imei)
                .put("source", SOURCE)
                .toString()
        } else {
            null
        }

        // After a voice call Android may leave this process on the call path,
        // which cannot look up hosts. POST on WiFi / mobile data instead.
        // Do not bindProcessToNetwork (would affect InCall). Do not go through Messages.
        var lastRetryable: Attempt? = null
        for (route in dataRoutes(context)) {
            val attempt = execute(url, method, token, body, would, route)
            if (attempt.state != null || !attempt.retryable) {
                return attempt
            }
            lastRetryable = attempt
        }
        return lastRetryable ?: Attempt(state = null, retryable = true)
    }

    private data class Route(val label: String, val network: Network?)

    private fun dataRoutes(context: Context): List<Route> {
        val fallback = Route("default", null)
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return listOf(fallback)
        val ranked = try {
            cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }
                Route(routeLabel(caps), network) to routeScore(caps)
            }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.network }
        } catch (t: Throwable) {
            Log.w(TAG, "routes ${t.message}")
            emptyList()
        }
        return ranked + fallback
    }

    private fun routeScore(caps: NetworkCapabilities): Int {
        var score = 100
        // VALIDATED is a bonus, not a gate (Phone 30 skipped pile during/after a call).
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 5
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
            score += 10
        }
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> score += 40
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> score += 30
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> score += 10
        }
        return score
    }

    private fun routeLabel(caps: NetworkCapabilities): String {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "net"
        }
        val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "ok"
        } else {
            "novalid"
        }
        return "$transport/$validated"
    }

    private fun execute(
        url: URL,
        method: String,
        token: String,
        body: String?,
        would: String,
        route: Route,
    ): Attempt {
        var connection: HttpURLConnection? = null
        return try {
            val raw = if (route.network != null) {
                route.network.openConnection(url)
            } else {
                url.openConnection()
            }
            connection = (raw as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                useCaches = false
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { it.write(body) }
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val rawBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code == 429 || code == 503 || code >= 500) {
                Log.w(TAG, "$would via=${route.label} HTTP $code $rawBody")
                return Attempt(state = null, retryable = true)
            }
            if (code !in 200..299) {
                Log.w(TAG, "$would via=${route.label} HTTP $code $rawBody")
                return Attempt(state = null, retryable = false)
            }

            Log.i(TAG, "via=${route.label} HTTP $code")
            Attempt(state = parseState(rawBody), retryable = false)
        } catch (t: Throwable) {
            Log.w(TAG, "$would via=${route.label} ${t.javaClass.simpleName}: ${t.message}")
            Attempt(state = null, retryable = isRetryable(t))
        } finally {
            connection?.disconnect()
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketException,
                is InterruptedIOException -> return true
            }
            current = current.cause
        }
        return false
    }

    private fun parseState(raw: String): RemoteState? {
        if (raw.isBlank()) {
            return RemoteState.idle()
        }
        return try {
            val root = JSONObject(raw)
            if (root.isNull("notification")) {
                return RemoteState.idle()
            }
            val notification = root.optJSONObject("notification")
                ?: return RemoteState.idle()
            // GET ?source=phone returns the phone slot as `notification`. Nested
            // `notification.phone` is the permanent two-slot document.
            val slot = notification.optJSONObject(SOURCE) ?: notification
            parseSlot(slot)
        } catch (t: Throwable) {
            Log.w(TAG, "parse ${t.message}")
            null
        }
    }

    /** PhonlyAPI slot: cardStatus / reminderStartedAt / cardShownAt only. */
    private fun parseSlot(slot: JSONObject): RemoteState {
        val status = CardStatus.fromWire(jsonStringOrNull(slot, "cardStatus"))
        val cardShownAt = jsonStringOrNull(slot, "cardShownAt")
        val reminderStartedAt = jsonStringOrNull(slot, "reminderStartedAt")
        return when (status) {
            CardStatus.WAITING -> {
                if (reminderStartedAt == null) {
                    RemoteState.idle(cardShownAt)
                } else {
                    RemoteState(CardStatus.WAITING, reminderStartedAt, cardShownAt)
                }
            }
            CardStatus.SHOWN -> RemoteState(CardStatus.SHOWN, null, cardShownAt)
            CardStatus.IDLE -> RemoteState.idle(cardShownAt)
        }
    }

    private fun jsonStringOrNull(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) {
            return null
        }
        return obj.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun deviceImei(context: Context): String {
        val override = BuildConfig.DEVICE_IMEI_OVERRIDE.trim()
        if (override.matches(IMEI_RE)) {
            return override
        }

        val fromConfig = context.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions
            ?.getString("device_imei")
            ?.trim()
            .orEmpty()
        if (fromConfig.matches(IMEI_RE)) {
            return fromConfig
        }

        val cached = cuePrefs(context).getString(KEY_IMEI, "").orEmpty()
        if (cached.matches(IMEI_RE)) {
            return cached
        }

        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return ""
        val found = buildList {
            addImei(this) { telephony.imei }
            addImei(this) { telephony.getImei(0) }
            addImei(this) { telephony.getImei(1) }
        }.firstOrNull { it.matches(IMEI_RE) }.orEmpty()

        if (found.isNotEmpty()) {
            cuePrefs(context).edit().putString(KEY_IMEI, found).apply()
        }
        return found
    }

    private fun addImei(into: MutableList<String>, read: () -> String?) {
        try {
            val value = read()?.trim().orEmpty()
            if (value.isNotEmpty()) {
                into.add(value)
            }
        } catch (_: Exception) {
        }
    }
}

private val IMEI_RE = Regex("^\\d{14,16}$")
private const val KEY_IMEI = "imei"

internal fun cuePrefs(context: Context) =
    context.applicationContext.getSharedPreferences("phonly_device_notification", Context.MODE_PRIVATE)
