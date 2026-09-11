package org.fossify.phone.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object ContactsSyncScheduler {
    private const val TAG = "PhonlyPhone"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val retryDelaysMs = longArrayOf(2_000L, 5_000L, 15_000L, 45_000L)
    private const val WAKE_LOCK_MS = 90_000L

    @Volatile
    private var retryIndex = 0

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var appContext: Context? = null

    private val completes = CopyOnWriteArrayList<() -> Unit>()

    private val retryRunnable = Runnable {
        val app = appContext
        if (app != null) {
            request(app)
        }
    }

    fun request(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        appContext = app
        if (onComplete != null) {
            completes.add(onComplete)
        }
        hold(app)
        executor.execute {
            try {
                when (ContactsReconcile.run(app)) {
                    ReconcileResult.Applied -> {
                        EventBus.getDefault().post(Events.RefreshContacts)
                        cancelRetries()
                        release()
                        finishPending()
                    }
                    ReconcileResult.Invalid -> {
                        cancelRetries()
                        release()
                        finishPending()
                    }
                    ReconcileResult.Missing,
                    ReconcileResult.Denied,
                    ReconcileResult.Failed -> {
                        scheduleRetry(app)
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun scheduleRetry(context: Context) {
        val delays = retryDelaysMs
        val index = retryIndex
        if (index >= delays.size) {
            Log.i(TAG, "contacts sync still pending after ${delays.size} retries")
            cancelRetries()
            release()
            finishPending()
            return
        }
        val delay = delays[index]
        retryIndex = index + 1
        Log.i(TAG, "contacts retry ${retryIndex}/${delays.size} in ${delay}ms")
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delay)
        hold(context)
    }

    private fun cancelRetries() {
        retryIndex = 0
        main.removeCallbacks(retryRunnable)
    }

    private fun finishPending() {
        val cbs = completes.toList()
        completes.clear()
        cbs.forEach { cb ->
            try {
                cb()
            } catch (error: Exception) {
                Log.w(TAG, "onComplete: ${error.message}")
            }
        }
    }

    private fun hold(context: Context) {
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "co.phonly.phone:contacts")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun release() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
    }
}

class ContactsSyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ContactsSyncScheduler.request(context, goAsync())
    }
}
