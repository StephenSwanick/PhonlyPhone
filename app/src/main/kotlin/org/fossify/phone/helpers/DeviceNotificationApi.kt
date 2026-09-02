package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.RestrictionsManager
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
 */
internal object DeviceNotificationApi {
    const val TAG = "PhonlyCue"
    const val SOURCE = "phone"

    data class RemoteState(
        val alreadySent: Boolean,
        val waitingSince: String?,
        val sentAt: String?,
    )

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
        return try {
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
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                useCaches = false
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject()
                        .put("imei", imei)
                        .put("source", SOURCE)
                        .toString()
                    OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { it.write(body) }
                }
            }

            val code = connection.responseCode
            val raw = try {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                connection.disconnect()
            }

            if (code == 429 || code == 503 || code >= 500) {
                Log.w(TAG, "$would HTTP $code $raw")
                return Attempt(state = null, retryable = true)
            }
            if (code !in 200..299) {
                Log.w(TAG, "$would HTTP $code $raw")
                return Attempt(state = null, retryable = false)
            }

            Attempt(state = parseState(raw), retryable = false)
        } catch (t: Throwable) {
            Log.w(TAG, "$would ${t.javaClass.simpleName}: ${t.message}")
            Attempt(state = null, retryable = isRetryable(t))
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
            return RemoteState(alreadySent = false, waitingSince = null, sentAt = null)
        }
        return try {
            val root = JSONObject(raw)
            if (root.isNull("notification")) {
                return RemoteState(alreadySent = false, waitingSince = null, sentAt = null)
            }
            val notification = root.optJSONObject("notification")
                ?: return RemoteState(alreadySent = false, waitingSince = null, sentAt = null)
            RemoteState(
                alreadySent = notification.optBoolean("alreadySent", false),
                waitingSince = notification.optString("waitingSince").takeIf { it.isNotBlank() && it != "null" },
                sentAt = notification.optString("sentAt").takeIf { it.isNotBlank() && it != "null" },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "parse ${t.message}")
            null
        }
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
