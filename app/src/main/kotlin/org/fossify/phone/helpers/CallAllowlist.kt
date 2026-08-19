package org.fossify.phone.helpers

import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import org.fossify.phone.R

/**
 * Single gate for outgoing and incoming calls.
 *
 * Spike: hardcoded numbers plus emergency. Later, prefer a pushed
 * per-device list (managed config / local cache from Mongo) when present.
 */
object CallAllowlist {
    private val hardcodedNumbers = listOf(
        "7046180435",
        "7047185661",
    )

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

        return allowedNumbers().any { matches(number, it) }
    }

    /** @return true if the call must not proceed. */
    fun denyOutgoingIfBlocked(context: Context, rawNumber: String?): Boolean {
        if (isNumberAllowed(context, rawNumber)) {
            return false
        }
        Toast.makeText(context, R.string.call_not_allowed, Toast.LENGTH_LONG).show()
        return true
    }

    private fun allowedNumbers(): List<String> {
        // Later: if a pushed per-device list exists, return that instead.
        return hardcodedNumbers
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
