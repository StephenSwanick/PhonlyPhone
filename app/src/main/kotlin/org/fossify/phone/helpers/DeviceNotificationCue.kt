package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls
import android.telecom.Call
import android.telecom.DisconnectCause
import android.util.Log
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.phone.extensions.isConference
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.services.DeviceNotificationPostService
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Local miss pile + fact reports to PhonlyAPI. No overlay, no AlarmManager, no Esper key.
 *
 * Recents = Call history tab in front. On AAAAY, launching Phone is that tab.
 * Recents open → POST /looked source=phone. Home / leave → LEFT: remaining unacked
 * → POST /pile; after we acked our misses → POST /clear (API will not touch messages).
 * Inbox LOOKED/LEFT is ignored. Extra pile while shown does not restart the wait.
 */
object DeviceNotificationCue {
    const val PERMISSION = "co.phonly.permission.NOTIFICATION"
    const val ACTION_LOOKED = "co.phonly.intent.NOTIFICATION_LOOKED"
    const val ACTION_LEFT = "co.phonly.intent.NOTIFICATION_LEFT"
    const val EXTRA_SOURCE = "source"

    private const val KEY_UNACKED = "unacked_count"
    private const val KEY_PILED = "piled_this_cycle"
    private const val KEY_LAST_MISSED_ID = "last_missed_call_id"
    private const val KEY_CARD_STATUS = "cardStatus"
    private const val KEY_REMINDER_STARTED_AT = "reminderStartedAt"
    private const val KEY_CARD_SHOWN_AT = "cardShownAt"
    private const val PILE_DEBOUNCE_MS = 8_000L
    private const val PILE_RETRY_MS = 15_000L
    private const val CALL_LOG_SWEEP_MS = 2_500L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonly-cue").apply { isDaemon = true }
    }
    private val callLogIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonly-cue-calllog").apply { isDaemon = true }
    }

    @Volatile
    private var recentsForeground = false

    @Volatile
    private var cardStatus = DeviceNotificationApi.CardStatus.IDLE

    @Volatile
    private var reminderStartedAt: String? = null

    @Volatile
    private var cardShownAt: String? = null

    @Volatile
    private var owedClear = false

    @Volatile
    private var pilePosted = false

    @Volatile
    private var pileDebouncePending = false

    @Volatile
    private var lookGeneration = 0

    private var pileApp: Context? = null
    private val pileRunnable = Runnable {
        val app = pileApp ?: return@Runnable
        postPile(app)
    }

    @Volatile
    private var networkWatching = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkMaybeUp()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                onNetworkMaybeUp()
            }
        }
    }

    private val answered = Collections.newSetFromMap(WeakHashMap<Call, Boolean>())

    @Volatile
    private var callLogWatching = false

    private val callLogObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val app = pileApp ?: return
            callLogIo.execute {
                try {
                    onCallLogChanged(app)
                } catch (t: Throwable) {
                    Log.w(DeviceNotificationApi.TAG, "call log ${t.message}")
                }
            }
        }
    }

    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        pileApp = app
        loadCache(app)
        watchCallLog(app)
        snapshotMissedCallLog(app)
        io.execute {
            try {
                syncAfterStart(app)
            } catch (t: Throwable) {
                Log.w(DeviceNotificationApi.TAG, "start ${t.message}")
            }
        }
    }

    fun onCallStateChanged(call: Call, state: Int) {
        if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
            answered.add(call)
        }
    }

    fun onCallRemoved(context: Context, call: Call) {
        val userAnswered = answered.remove(call)
        if (call.isOutgoing() || call.isConference()) {
            return
        }
        val app = context.applicationContext
        pileApp = app
        // VM often goes ACTIVE (we used to skip as "answered") and Recents
        // writes MISSED a beat later. Always sweep CallLog after incoming ends.
        mainHandler.postDelayed({
            callLogIo.execute {
                try {
                    onCallLogChanged(app)
                } catch (t: Throwable) {
                    Log.w(DeviceNotificationApi.TAG, "call log sweep ${t.message}")
                }
            }
        }, CALL_LOG_SWEEP_MS)

        if (userAnswered) {
            Log.i(DeviceNotificationApi.TAG, "telecom skip: answered; waiting on Recents MISSED/VOICEMAIL")
            return
        }
        val disconnect = call.details.disconnectCause?.code
        if (disconnect == DisconnectCause.REJECTED) {
            Log.i(DeviceNotificationApi.TAG, "call skipped: hang-up")
            return
        }

        val number = call.details.handle?.schemeSpecificPart?.trim().orEmpty()
        if (number.isEmpty() || !CallAllowlist.isNumberAllowed(context, number)) {
            Log.i(DeviceNotificationApi.TAG, "call skipped: not allowlisted disconnect=$disconnect")
            return
        }

        Log.i(
            DeviceNotificationApi.TAG,
            "telecom miss disconnect=$disconnect connect=${call.details.connectTimeMillis > 0L}"
        )
        noteMiss(context)
    }

    fun setRecentsVisible(context: Context, visible: Boolean) {
        val app = context.applicationContext
        val changed: Boolean
        synchronized(lock) {
            changed = recentsForeground != visible
            recentsForeground = visible
        }
        if (!changed) {
            return
        }
        if (visible) {
            onLooked(app, postLooked = true, ackMisses = true)
        } else {
            onLeft(app)
        }
    }

    fun onPeerLooked() {
        Log.i(DeviceNotificationApi.TAG, "ignore peer LOOKED (inbox is not Recents)")
    }

    fun onPeerLeft() {
        Log.i(DeviceNotificationApi.TAG, "ignore peer LEFT (inbox is not Recents)")
    }

    private fun onLooked(
        context: Context,
        postLooked: Boolean,
        ackMisses: Boolean,
    ) {
        lookGeneration += 1
        cancelPileDebounce()
        synchronized(lock) {
            if (ackMisses) {
                val remaining = unackedCount(context)
                if (remaining > 0) {
                    owedClear = true
                }
                writeUnacked(context, 0)
            }
            writePiled(context, false)
        }
        applyIdle()
        persistCache(context)
        if (postLooked) {
            io.execute {
                try {
                    val state = DeviceNotificationApi.looked(context)
                    logRemote("LOOKED", state)
                    applyServer(context, state)
                } catch (t: Throwable) {
                    Log.w(DeviceNotificationApi.TAG, "LOOKED ${t.message}")
                }
            }
        }
    }

    private fun onLeft(context: Context) {
        if (hasUnacked(context)) {
            owedClear = false
            schedulePile(context)
            return
        }
        val shouldClear = owedClear
        owedClear = false
        if (!shouldClear) {
            return
        }
        applyIdle()
        persistCache(context)
        io.execute {
            try {
                val state = DeviceNotificationApi.clear(context)
                logRemote("CLEAR", state)
                applyServer(context, state)
            } catch (t: Throwable) {
                Log.w(DeviceNotificationApi.TAG, "CLEAR ${t.message}")
            }
        }
    }

    private fun syncAfterStart(context: Context) {
        val gen = lookGeneration
        if (!hasUnacked(context)) {
            Log.i(DeviceNotificationApi.TAG, "start: no local miss")
            return
        }
        val state = DeviceNotificationApi.getState(context)
        logRemote("GET", state)
        if (state != null) {
            applyServer(context, state)
        }
        if (gen != lookGeneration) {
            Log.i(DeviceNotificationApi.TAG, "boot GET stale; looked won")
            return
        }
        if (recentsForeground) {
            return
        }
        if (!hasUnacked(context)) {
            return
        }
        // shown → boot stays quiet. waiting → reminder already open.
        // idle → may pile. GET miss keeps the local cache (extra pile is
        // harmless if the API is already waiting or shown).
        when (cardStatus) {
            DeviceNotificationApi.CardStatus.SHOWN -> {
                Log.i(DeviceNotificationApi.TAG, "boot: shown; stay quiet")
            }
            DeviceNotificationApi.CardStatus.WAITING -> {
                Log.i(DeviceNotificationApi.TAG, "boot: already waiting")
            }
            DeviceNotificationApi.CardStatus.IDLE -> {
                if (state == null) {
                    Log.i(DeviceNotificationApi.TAG, "boot: GET miss; pile if Recents is not in front")
                }
                schedulePile(context)
            }
        }
    }

    private fun noteMiss(context: Context) {
        recordUnacked(context)
        if (recentsForeground) {
            Log.i(DeviceNotificationApi.TAG, "miss while Recents open; wait for LEFT")
            return
        }
        schedulePile(context)
    }

    private fun watchCallLog(context: Context) {
        if (callLogWatching) {
            return
        }
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            Log.w(DeviceNotificationApi.TAG, "no READ_CALL_LOG; Recents miss observer off")
            return
        }
        try {
            context.contentResolver.registerContentObserver(Calls.CONTENT_URI, true, callLogObserver)
            callLogWatching = true
        } catch (t: Throwable) {
            Log.w(DeviceNotificationApi.TAG, "call log observer ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun snapshotMissedCallLog(context: Context) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            return
        }
        val last = maxMissedCallId(context)
        if (last > lastMissedId(context)) {
            writeLastMissedId(context, last)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onCallLogChanged(context: Context) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            return
        }
        val after = lastMissedId(context)
        val found = ArrayList<Pair<Long, String>>()
        context.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls._ID, Calls.NUMBER, Calls.TYPE),
            "(${Calls.TYPE}=? OR ${Calls.TYPE}=?) AND ${Calls._ID}>?",
            arrayOf(
                Calls.MISSED_TYPE.toString(),
                Calls.VOICEMAIL_TYPE.toString(),
                after.toString(),
            ),
            "${Calls._ID} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Calls._ID)
            val numberIdx = cursor.getColumnIndexOrThrow(Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(Calls.TYPE)
            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeIdx)
                Log.i(DeviceNotificationApi.TAG, "call log row type=$type")
                found.add(cursor.getLong(idIdx) to cursor.getString(numberIdx).orEmpty())
            }
        }
        if (found.isEmpty()) {
            logNewestCallLogRows(context, after)
            return
        }
        writeLastMissedId(context, found.last().first)
        var noted = false
        for ((_, number) in found) {
            if (number.isBlank() || !CallAllowlist.isNumberAllowed(context, number)) {
                continue
            }
            noted = true
            recordUnacked(context)
        }
        if (!noted) {
            return
        }
        Log.i(DeviceNotificationApi.TAG, "call log miss unacked=${unackedCount(context)}")
        if (recentsForeground) {
            Log.i(DeviceNotificationApi.TAG, "miss while Recents open; wait for LEFT")
            return
        }
        schedulePile(context)
    }

    @SuppressLint("MissingPermission")
    private fun maxMissedCallId(context: Context): Long {
        context.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls._ID),
            "${Calls.TYPE}=? OR ${Calls.TYPE}=?",
            arrayOf(Calls.MISSED_TYPE.toString(), Calls.VOICEMAIL_TYPE.toString()),
            "${Calls._ID} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 0L
    }

    @SuppressLint("MissingPermission")
    private fun logNewestCallLogRows(context: Context, after: Long) {
        context.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls._ID, Calls.TYPE),
            null,
            null,
            "${Calls._ID} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Calls._ID)
            val typeIdx = cursor.getColumnIndexOrThrow(Calls.TYPE)
            var n = 0
            while (cursor.moveToNext() && n < 3) {
                val id = cursor.getLong(idIdx)
                Log.i(
                    DeviceNotificationApi.TAG,
                    "call log newest type=${cursor.getInt(typeIdx)} id>$after=${id > after}"
                )
                n += 1
            }
            if (n == 0) {
                Log.i(DeviceNotificationApi.TAG, "call log empty after=$after")
            }
        }
    }

    private fun recordUnacked(context: Context) {
        synchronized(lock) {
            writeUnacked(context, unackedCount(context) + 1)
        }
        Log.i(DeviceNotificationApi.TAG, "unacked=${unackedCount(context)}")
    }

    private fun schedulePile(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (pilePosted) {
                Log.i(DeviceNotificationApi.TAG, "pile skipped (POST in flight)")
                return
            }
            pileApp = app
            // Call-log / network ticks must not restart the 8s timer. Extra
            // POSTs while shown are harmless; the API does not restart the
            // phone wait. Do not skip on VALIDATED.
            if (pileDebouncePending) {
                Log.i(DeviceNotificationApi.TAG, "pile debounce kept")
                return
            }
            pileDebouncePending = true
            mainHandler.postDelayed(pileRunnable, PILE_DEBOUNCE_MS)
        }
        startPostForeground(app)
        Log.i(DeviceNotificationApi.TAG, "pile debounce ${PILE_DEBOUNCE_MS}ms")
    }

    private fun cancelPileDebounce() {
        synchronized(lock) {
            pilePosted = false
            pileDebouncePending = false
            mainHandler.removeCallbacks(pileRunnable)
            pileApp?.let { app ->
                unwatchNetwork(app)
                stopPostForeground(app)
            }
        }
    }

    private fun onNetworkMaybeUp() {
        val app = pileApp ?: return
        if (recentsForeground || !hasUnacked(app)) {
            return
        }
        synchronized(lock) {
            if (pilePosted || pileDebouncePending) {
                Log.i(DeviceNotificationApi.TAG, "network up; pile wait kept")
                return
            }
        }
        Log.i(DeviceNotificationApi.TAG, "network up; pile debounce")
        schedulePile(app)
    }

    private fun watchNetwork(context: Context) {
        if (networkWatching) {
            return
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkWatching = true
        } catch (t: Throwable) {
            Log.w(DeviceNotificationApi.TAG, "network watch ${t.message}")
        }
    }

    private fun unwatchNetwork(context: Context) {
        if (!networkWatching) {
            return
        }
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        networkWatching = false
    }

    private fun schedulePileRetry(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (recentsForeground || !hasUnacked(context)) {
                unwatchNetwork(app)
                stopPostForeground(app)
                return
            }
            pileApp = app
            watchNetwork(app)
            pileDebouncePending = true
            mainHandler.removeCallbacks(pileRunnable)
            mainHandler.postDelayed(pileRunnable, PILE_RETRY_MS)
        }
        startPostForeground(app)
        Log.i(DeviceNotificationApi.TAG, "pile retry ${PILE_RETRY_MS}ms (until looked or POST)")
    }

    private fun postPile(context: Context) {
        synchronized(lock) {
            if (pilePosted) {
                return
            }
            pilePosted = true
            pileDebouncePending = false
        }
        io.execute {
            try {
                if (!hasUnacked(context)) {
                    cancelPileDebounce()
                    return@execute
                }
                if (recentsForeground) {
                    Log.i(DeviceNotificationApi.TAG, "pile aborted; Recents is looking")
                    synchronized(lock) {
                        pilePosted = false
                    }
                    stopPostForeground(context)
                    return@execute
                }
                val gen = lookGeneration
                val attempt = DeviceNotificationApi.pile(context)
                if (gen != lookGeneration) {
                    Log.i(DeviceNotificationApi.TAG, "PILE stale; looked won")
                    synchronized(lock) {
                        pilePosted = false
                    }
                    if (attempt.state != null) {
                        try {
                            val looked = DeviceNotificationApi.looked(context)
                            logRemote("LOOKED", looked)
                            applyServer(context, looked)
                        } catch (t: Throwable) {
                            Log.w(DeviceNotificationApi.TAG, "LOOKED ${t.message}")
                        }
                    }
                    return@execute
                }
                logRemote("PILE", attempt.state)
                applyServer(context, attempt.state)
                synchronized(lock) {
                    pilePosted = false
                    writePiled(context, attempt.state != null)
                    if (attempt.state != null) {
                        unwatchNetwork(context)
                        stopPostForeground(context)
                    }
                }
                if (attempt.state == null && attempt.retryable) {
                    schedulePileRetry(context)
                }
            } catch (t: Throwable) {
                Log.w(DeviceNotificationApi.TAG, "PILE ${t.message}")
                synchronized(lock) {
                    pilePosted = false
                    writePiled(context, false)
                }
                schedulePileRetry(context)
            }
        }
    }

    private fun startPostForeground(context: Context) {
        val app = context.applicationContext
        mainHandler.post { DeviceNotificationPostService.start(app) }
    }

    private fun stopPostForeground(context: Context) {
        val app = context.applicationContext
        mainHandler.post { DeviceNotificationPostService.stop(app) }
    }

    private fun hasUnacked(context: Context) = unackedCount(context) > 0

    private fun unackedCount(context: Context) = cuePrefs(context).getInt(KEY_UNACKED, 0)

    private fun lastMissedId(context: Context) = cuePrefs(context).getLong(KEY_LAST_MISSED_ID, 0L)

    private fun writeLastMissedId(context: Context, id: Long) {
        cuePrefs(context).edit().putLong(KEY_LAST_MISSED_ID, id).apply()
    }

    private fun writeUnacked(context: Context, count: Int) {
        cuePrefs(context).edit().putInt(KEY_UNACKED, count.coerceAtLeast(0)).apply()
    }

    private fun writePiled(context: Context, value: Boolean) {
        cuePrefs(context).edit().putBoolean(KEY_PILED, value).apply()
    }

    private fun applyIdle() {
        cardStatus = DeviceNotificationApi.CardStatus.IDLE
        reminderStartedAt = null
    }

    private fun applyServer(context: Context, state: DeviceNotificationApi.RemoteState?) {
        if (state == null) {
            return
        }
        cardStatus = state.cardStatus
        reminderStartedAt = if (state.cardStatus == DeviceNotificationApi.CardStatus.WAITING) {
            state.reminderStartedAt
        } else {
            null
        }
        cardShownAt = state.cardShownAt
        persistCache(context)
    }

    private fun loadCache(context: Context) {
        val prefs = cuePrefs(context)
        cardStatus = DeviceNotificationApi.CardStatus.fromWire(prefs.getString(KEY_CARD_STATUS, null))
        reminderStartedAt = prefs.getString(KEY_REMINDER_STARTED_AT, null)
        cardShownAt = prefs.getString(KEY_CARD_SHOWN_AT, null)
        if (cardStatus != DeviceNotificationApi.CardStatus.WAITING) {
            reminderStartedAt = null
        }
    }

    private fun persistCache(context: Context) {
        cuePrefs(context).edit()
            .putString(KEY_CARD_STATUS, cardStatus.wire)
            .putString(KEY_REMINDER_STARTED_AT, reminderStartedAt)
            .putString(KEY_CARD_SHOWN_AT, cardShownAt)
            .remove("alreadySent")
            .remove("waitingSince")
            .remove("sentAt")
            .apply()
    }

    private fun logRemote(action: String, state: DeviceNotificationApi.RemoteState?) {
        if (state == null) {
            Log.w(DeviceNotificationApi.TAG, "WOULD_$action")
            return
        }
        Log.i(
            DeviceNotificationApi.TAG,
            "$action source=${DeviceNotificationApi.SOURCE} cardStatus=${state.cardStatus.wire} reminderStartedAt=${state.reminderStartedAt} cardShownAt=${state.cardShownAt}"
        )
    }
}
