package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.telecom.Call
import android.telecom.DisconnectCause
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.databinding.MissedCallOverlayBinding
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isConference
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.models.CallContact
import org.fossify.phone.services.MissedCallOverlayService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kid-visible missed-call cue: one top bar until they tap it or open Recents.
 * No swipe-to-dismiss. No Settings snackbar if the overlay grant is missing.
 */
object MissedCallOverlay {
    @Volatile
    var recentsOnScreen: Boolean = false

    private const val KEY_NUMBER = "number"
    private const val KEY_COMPARABLE = "comparable"
    private const val KEY_NAME = "name"
    private const val KEY_PHOTO = "photoUri"
    private const val KEY_COUNT = "count"
    private const val KEY_TIME = "time"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var refreshSeq = 0
    private var host: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var attached = false

    private data class Miss(
        val number: String,
        val comparable: String,
        val name: String,
        val photoUri: String,
        val count: Int,
        val time: Long,
    )

    fun onCallRemoved(context: Context, call: Call) {
        if (call.isOutgoing() || call.isConference()) {
            return
        }
        if (call.details.connectTimeMillis > 0L) {
            return
        }
        when (call.details.disconnectCause.code) {
            DisconnectCause.REJECTED, DisconnectCause.LOCAL, DisconnectCause.CANCELED -> return
        }

        val number = call.details.handle?.schemeSpecificPart?.trim().orEmpty()
        if (number.isEmpty() || !CallAllowlist.isNumberAllowed(context, number)) {
            return
        }

        record(context, number, name = number, photoUri = "")
        refresh(context)
        getCallContact(context, call) { contact ->
            updateDetails(context, number, contact)
            refresh(context)
        }
    }

    fun setRecentsVisible(context: Context, visible: Boolean) {
        recentsOnScreen = visible
        if (visible) {
            acknowledge(context)
        }
    }

    fun acknowledge(context: Context) {
        val app = context.applicationContext
        writeMisses(app, emptyList())
        mainHandler.post {
            clear(app)
            app.stopService(Intent(app, MissedCallOverlayService::class.java))
        }
        app.clearMissedCalls()
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        val seq = ++refreshSeq
        if (recentsOnScreen) {
            writeMisses(app, emptyList())
            mainHandler.post {
                if (seq == refreshSeq) {
                    clear(app)
                    app.stopService(Intent(app, MissedCallOverlayService::class.java))
                }
            }
            app.clearMissedCalls()
            return
        }

        if (!Settings.canDrawOverlays(app)) {
            mainHandler.post {
                if (seq == refreshSeq) {
                    clear(app)
                    app.stopService(Intent(app, MissedCallOverlayService::class.java))
                }
            }
            return
        }

        val misses = readMisses(app)
        mainHandler.post {
            if (seq != refreshSeq) {
                return@post
            }
            render(app, misses)
            val serviceIntent = Intent(app, MissedCallOverlayService::class.java)
            if (misses.isEmpty()) {
                app.stopService(serviceIntent)
            } else {
                try {
                    app.startService(serviceIntent)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun record(context: Context, number: String, name: String, photoUri: String) {
        val comparable = comparableNumber(number)
        if (comparable.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val existing = readMisses(context).toMutableList()
        val index = existing.indexOfFirst { it.comparable == comparable }
        val updated = if (index >= 0) {
            val previous = existing.removeAt(index)
            previous.copy(
                number = number.ifBlank { previous.number },
                name = name.ifBlank { previous.name },
                photoUri = photoUri.ifBlank { previous.photoUri },
                count = previous.count + 1,
                time = now,
            )
        } else {
            Miss(
                number = number,
                comparable = comparable,
                name = name.ifBlank { number },
                photoUri = photoUri,
                count = 1,
                time = now,
            )
        }
        existing.add(0, updated)
        writeMisses(context, existing)
    }

    private fun updateDetails(context: Context, number: String, contact: CallContact) {
        val comparable = comparableNumber(number)
        if (comparable.isEmpty()) {
            return
        }
        val existing = readMisses(context).toMutableList()
        val index = existing.indexOfFirst { it.comparable == comparable }
        if (index < 0) {
            return
        }
        val previous = existing[index]
        val name = contact.name.ifBlank { previous.name }
        existing[index] = previous.copy(
            name = name,
            photoUri = contact.photoUri.ifBlank { previous.photoUri },
        )
        writeMisses(context, existing)
    }

    @SuppressLint("InflateParams")
    private fun render(context: Context, misses: List<Miss>) {
        if (misses.isEmpty()) {
            clear(context)
            return
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = host ?: MissedCallOverlayBinding.inflate(LayoutInflater.from(context)).root.also {
            host = it
        }
        val binding = MissedCallOverlayBinding.bind(view)
        bindCard(context, binding, misses)

        val params = windowParams ?: buildLayoutParams(context).also { windowParams = it }
        params.y = statusBarOffset(context)
        if (!attached) {
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (_: Exception) {
                attached = false
            }
        } else {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                attached = false
                try {
                    windowManager.addView(view, params)
                    attached = true
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun bindCard(context: Context, binding: MissedCallOverlayBinding, misses: List<Miss>) {
        val latest = misses.first()
        binding.missedCallOverlayTitle.text = context.getString(R.string.missed_call)
        binding.missedCallOverlayName.text = subtitle(context, misses)
        binding.root.contentDescription = "${binding.missedCallOverlayTitle.text}, ${binding.missedCallOverlayName.text}"
        bindPhoto(context, binding, latest)
        binding.missedCallOverlayCard.setOnClickListener {
            openRecents(context)
        }
    }

    private fun subtitle(context: Context, misses: List<Miss>): String {
        val latest = misses.first()
        val otherPeople = misses.size - 1
        val name = latest.name.ifBlank { latest.number }
        return when {
            otherPeople > 1 -> context.getString(R.string.missed_call_and_others, name, otherPeople)
            otherPeople == 1 -> context.getString(R.string.missed_call_and_one_other, name)
            latest.count > 1 -> context.getString(R.string.missed_calls_from, name, latest.count)
            else -> name
        }
    }

    private fun bindPhoto(context: Context, binding: MissedCallOverlayBinding, latest: Miss) {
        binding.missedCallOverlayPhoto.outlineProvider = ViewOutlineProvider.BACKGROUND
        binding.missedCallOverlayPhoto.clipToOutline = true
        val avatar = CallContactAvatarHelper(context).getCallContactAvatar(
            CallContact(latest.name, latest.photoUri, latest.number, "")
        )
        if (avatar != null) {
            binding.missedCallOverlayPhoto.setPadding(0, 0, 0, 0)
            binding.missedCallOverlayPhoto.clearColorFilter()
            binding.missedCallOverlayPhoto.setImageBitmap(avatar)
            return
        }

        val letter = latest.name.ifBlank { latest.number }
        val icon = try {
            SimpleContactsHelper(context).getContactLetterIcon(letter)
        } catch (_: Exception) {
            null
        }
        binding.missedCallOverlayPhoto.setPadding(0, 0, 0, 0)
        binding.missedCallOverlayPhoto.clearColorFilter()
        if (icon != null) {
            binding.missedCallOverlayPhoto.setImageBitmap(icon)
        } else {
            val pad = context.resources.getDimensionPixelSize(R.dimen.small_margin)
            binding.missedCallOverlayPhoto.setPadding(pad, pad, pad, pad)
            binding.missedCallOverlayPhoto.setImageResource(R.drawable.ic_phone_green_vector)
            binding.missedCallOverlayPhoto.setColorFilter(Color.WHITE)
        }
    }

    private fun openRecents(context: Context) {
        acknowledge(context)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType(CallLog.Calls.CONTENT_URI, CallLog.Calls.CONTENT_TYPE)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun buildLayoutParams(context: Context): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = statusBarOffset(context)
        }
    }

    private fun statusBarOffset(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
        val gap = context.resources.getDimensionPixelSize(R.dimen.missed_call_overlay_top_gap)
        return status + gap
    }

    private fun clear(context: Context) {
        val view = host ?: return
        if (attached) {
            try {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {
            }
        }
        attached = false
    }

    private fun comparableNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    private fun readMisses(context: Context): List<Miss> = synchronized(lock) {
        val raw = context.config.missedCallOverlayJson
        if (raw.isBlank() || raw == "[]") {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val number = obj.optString(KEY_NUMBER).trim()
                    val comparable = obj.optString(KEY_COMPARABLE).ifBlank { comparableNumber(number) }
                    if (comparable.isEmpty()) {
                        continue
                    }
                    add(
                        Miss(
                            number = number,
                            comparable = comparable,
                            name = obj.optString(KEY_NAME).ifBlank { number },
                            photoUri = obj.optString(KEY_PHOTO),
                            count = obj.optInt(KEY_COUNT, 1).coerceAtLeast(1),
                            time = obj.optLong(KEY_TIME),
                        )
                    )
                }
            }.sortedByDescending { it.time }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeMisses(context: Context, misses: List<Miss>) = synchronized(lock) {
        if (misses.isEmpty()) {
            context.config.missedCallOverlayJson = "[]"
            return
        }
        val array = JSONArray()
        misses.forEach { miss ->
            array.put(
                JSONObject()
                    .put(KEY_NUMBER, miss.number)
                    .put(KEY_COMPARABLE, miss.comparable)
                    .put(KEY_NAME, miss.name)
                    .put(KEY_PHOTO, miss.photoUri)
                    .put(KEY_COUNT, miss.count)
                    .put(KEY_TIME, miss.time)
            )
        }
        context.config.missedCallOverlayJson = array.toString()
    }
}
