package org.fossify.phone.helpers

import android.content.Context
import android.content.RestrictionsManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import org.fossify.phone.R
import org.json.JSONArray

/**
 * Single gate for outgoing and incoming calls.
 *
 * Source of numbers: Esper managed config key [ALLOWLIST_JSON_KEY].
 * Missing, blank, or invalid JSON → empty list (emergency only).
 * A present `[]` is also emergency only.
 *
 * Incoming: blocked numbers are answered and hung up in [IncomingAllowlistDrop]
 * so the carrier usually skips voicemail. Do not reject them in screening.
 */
object CallAllowlist {
    const val ALLOWLIST_JSON_KEY = "allowlist_json"

    fun isNumberAllowed(context: Context, rawNumber: String?): Boolean {
        if (rawNumber.isNullOrBlank()) {
            return false
        }

        val number = rawNumber.removePrefix("tel:").trim()
        if (number.isEmpty()) {
            return false
        }

        if (isEmergencyNumber(context, number)) {
            return true
        }

        return allowedNumbers(context).any { matches(number, it) }
    }

    /** @return true if the call must not proceed. */
    fun denyOutgoingIfBlocked(context: Context, rawNumber: String?): Boolean {
        if (isNumberAllowed(context, rawNumber)) {
            return false
        }
        Toast.makeText(context, R.string.call_not_allowed, Toast.LENGTH_LONG).show()
        return true
    }

    private fun allowedNumbers(context: Context): List<String> {
        return readManagedE164s(context)
    }

    /**
     * Voice numbers from AppConfig. Empty if unset, blank, `[]`, or invalid JSON.
     */
    private fun readManagedE164s(context: Context): List<String> {
        val restrictions = context.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions ?: return emptyList()
        if (!restrictions.containsKey(ALLOWLIST_JSON_KEY)) {
            return emptyList()
        }
        val raw = restrictions.getString(ALLOWLIST_JSON_KEY)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (!obj.optBoolean("voice", true)) {
                        continue
                    }
                    val e164 = obj.optString("e164").trim()
                    if (e164.isNotEmpty()) {
                        add(e164)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isEmergencyNumber(context: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (digits == "911" || digits == "112") {
            return true
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telephony = context.getSystemService(TelephonyManager::class.java)
                telephony?.isEmergencyNumber(number) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(number)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun matches(candidate: String, allowed: String): Boolean {
        if (PhoneNumberUtils.compare(candidate, allowed)) {
            return true
        }

        val candidateDigits = candidate.filter { it.isDigit() }
        val allowedDigits = allowed.filter { it.isDigit() }
        if (candidateDigits.isEmpty() || allowedDigits.isEmpty()) {
            return false
        }
        if (candidateDigits == allowedDigits) {
            return true
        }

        val length = minOf(10, candidateDigits.length, allowedDigits.length)
        return length == 10 &&
            candidateDigits.takeLast(10) == allowedDigits.takeLast(10)
    }
}
