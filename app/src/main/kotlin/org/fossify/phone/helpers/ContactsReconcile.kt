package org.fossify.phone.helpers

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.RawContacts
import android.util.Log

enum class ReconcileResult {
    Applied,
    Missing,
    Invalid,
    Denied,
    Failed,
}

/**
 * Write Esper `allowlist_json` into Contacts Provider.
 * `co.phonly.contacts` is retired; this package owns the write.
 * The Contacts tab still reads the Provider, not labels.
 */
object ContactsReconcile {
    private const val TAG = "PhonlyPhone"
    const val SYNC_TAG = "phonly"

    fun run(context: Context): ReconcileResult {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CONTACTS/WRITE_CONTACTS not granted")
            return ReconcileResult.Denied
        }

        val rawJson = restrictionJson(context)
        return when (val parsed = AllowlistJson.parse(rawJson)) {
            AllowlistContactParse.Missing -> {
                Log.i(TAG, "skip: allowlist_json missing")
                ReconcileResult.Missing
            }
            AllowlistContactParse.Invalid -> {
                Log.i(TAG, "skip: allowlist_json invalid")
                ReconcileResult.Invalid
            }
            is AllowlistContactParse.Apply -> {
                PhonlyAccount.ensure(context)
                val desired = parsed.entries.associateBy { AllowlistJson.matchKey(it.e164) }
                val wrote = upsertPhonly(context, desired)
                if (!wrote) {
                    Log.e(TAG, "upsert failed")
                    return ReconcileResult.Failed
                }
                val visible = visibleKeys(context)
                val missing = desired.keys.filter { it !in visible }
                if (missing.isNotEmpty()) {
                    Log.w(TAG, "not visible after Phonly upsert: ${missing.size}; inserting local")
                    if (!insertLocal(context, missing.mapNotNull { desired[it] })) {
                        Log.e(TAG, "local insert failed")
                        return ReconcileResult.Failed
                    }
                }
                stripOthers(context)
                Log.i(TAG, "synced ${desired.size} Phonly contact(s)")
                ReconcileResult.Applied
            }
        }
    }

    private fun restrictionJson(context: Context): String? {
        val bundle = context.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions
        val values = LinkedHashMap<String, String?>()
        if (bundle != null) {
            for (key in bundle.keySet()) {
                values[key] = bundle.get(key)?.toString()
            }
        }
        Log.i(TAG, "restriction keys=${values.keys.joinToString(",")} size=${values.size}")
        return AllowlistJson.rawFromValues(values)
    }

    private fun upsertPhonly(context: Context, desired: Map<String, AllowlistContactEntry>): Boolean {
        val existing = loadPhonly(context)
        val ops = ArrayList<ContentProviderOperation>()

        for (entry in desired.values) {
            val key = AllowlistJson.matchKey(entry.e164)
            val current = existing[key]
            if (current == null) {
                insert(ops, entry)
            } else if (current.label != entry.label || current.e164 != entry.e164) {
                update(ops, current.rawId, entry)
            }
        }

        for ((key, current) in existing) {
            if (key !in desired) {
                ops.add(
                    ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(RawContacts.CONTENT_URI, current.rawId).buildUpon()
                            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                            .build(),
                    ).build(),
                )
            }
        }

        return applyOps(context, ops)
    }

    private fun insert(
        ops: ArrayList<ContentProviderOperation>,
        entry: AllowlistContactEntry,
        accountName: String?,
        accountType: String?,
    ) {
        val rawIndex = ops.size
        val raw = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.SYNC1, SYNC_TAG)
            .withValue(RawContacts.SOURCE_ID, AllowlistJson.matchKey(entry.e164))
            .withValue(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED)
        if (accountName != null) {
            raw.withValue(RawContacts.ACCOUNT_NAME, accountName)
        }
        if (accountType != null) {
            raw.withValue(RawContacts.ACCOUNT_TYPE, accountType)
        }
        ops.add(raw.build())
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, entry.label)
                .build(),
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, entry.e164)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build(),
        )
    }

    private fun insert(ops: ArrayList<ContentProviderOperation>, entry: AllowlistContactEntry) {
        insert(ops, entry, PhonlyAccount.ACCOUNT_NAME, PhonlyAccount.ACCOUNT_TYPE)
    }

    private fun insertLocal(context: Context, entries: List<AllowlistContactEntry>): Boolean {
        if (entries.isEmpty()) return true
        val ops = ArrayList<ContentProviderOperation>()
        for (entry in entries) {
            insert(ops, entry, null, null)
        }
        return applyOps(context, ops)
    }

    private fun visibleKeys(context: Context): Set<String> {
        val keys = HashSet<String>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(Phone.NUMBER)
            if (idx < 0) return@use
            while (cursor.moveToNext()) {
                val key = AllowlistJson.matchKey(cursor.getString(idx).orEmpty())
                if (key.isNotEmpty()) keys.add(key)
            }
        }
        return keys
    }

    private fun update(ops: ArrayList<ContentProviderOperation>, rawId: Long, entry: AllowlistContactEntry) {
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(rawId.toString(), StructuredName.CONTENT_ITEM_TYPE),
                )
                .withValue(StructuredName.DISPLAY_NAME, entry.label)
                .build(),
        )
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(rawId.toString(), Phone.CONTENT_ITEM_TYPE),
                )
                .withValue(Phone.NUMBER, entry.e164)
                .build(),
        )
    }

    private data class Existing(val rawId: Long, val e164: String, val label: String)

    private fun loadPhonly(context: Context): Map<String, Existing> {
        val byRaw = LinkedHashMap<Long, Existing>()
        val resolver = context.contentResolver
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.SYNC1),
            "${RawContacts.DELETED}=0",
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val typeIdx = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_TYPE)
            val nameIdx = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_NAME)
            val syncIdx = cursor.getColumnIndex(RawContacts.SYNC1)
            while (cursor.moveToNext()) {
                val type = cursor.getString(typeIdx)
                val name = cursor.getString(nameIdx)
                val sync = if (syncIdx >= 0) cursor.getString(syncIdx) else null
                val ours = (type == PhonlyAccount.ACCOUNT_TYPE && name == PhonlyAccount.ACCOUNT_NAME) ||
                    sync == SYNC_TAG
                if (!ours) continue
                val rawId = cursor.getLong(idIdx)
                byRaw[rawId] = Existing(rawId, "", "")
            }
        }

        if (byRaw.isEmpty()) return emptyMap()

        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                StructuredName.DISPLAY_NAME,
                Phone.NUMBER,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} IN (${byRaw.keys.joinToString(",")})",
            null,
            null,
        )?.use { cursor ->
            val rawIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val nameIdx = cursor.getColumnIndex(StructuredName.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(Phone.NUMBER)
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(rawIdx)
                val current = byRaw[rawId] ?: continue
                when (cursor.getString(mimeIdx)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        val label = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        byRaw[rawId] = current.copy(label = label)
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val number = if (numberIdx >= 0) cursor.getString(numberIdx).orEmpty() else ""
                        byRaw[rawId] = current.copy(e164 = number)
                    }
                }
            }
        }

        return byRaw.values.mapNotNull { row ->
            val key = AllowlistJson.matchKey(row.e164)
            if (key.isEmpty()) null else key to row
        }.toMap()
    }

    private fun stripOthers(context: Context) {
        val resolver = context.contentResolver
        val ids = ArrayList<Long>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.SYNC1),
            "${RawContacts.DELETED}=0",
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val typeIdx = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_TYPE)
            val nameIdx = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_NAME)
            val syncIdx = cursor.getColumnIndex(RawContacts.SYNC1)
            while (cursor.moveToNext()) {
                val type = cursor.getString(typeIdx)
                val name = cursor.getString(nameIdx)
                val sync = if (syncIdx >= 0) cursor.getString(syncIdx) else null
                if (type == PhonlyAccount.ACCOUNT_TYPE && name == PhonlyAccount.ACCOUNT_NAME) {
                    continue
                }
                if (sync == SYNC_TAG) continue
                ids.add(cursor.getLong(idIdx))
            }
        }

        var deleted = 0
        var failed = 0
        for (rawId in ids) {
            if (isUserProfile(context, rawId)) continue
            val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build()
            val rows = try {
                resolver.delete(uri, null, null)
            } catch (error: Exception) {
                Log.w(TAG, "delete raw $rawId failed: ${error.message}")
                0
            }
            if (rows > 0) {
                deleted += 1
            } else {
                failed += 1
            }
        }
        Log.i(TAG, "stripped non-Phonly raw contacts deleted=$deleted failed=$failed")
    }

    private fun isUserProfile(context: Context, rawId: Long): Boolean {
        val contactId = context.contentResolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            arrayOf(RawContacts.CONTACT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: return false
        if (contactId <= 0L) return false
        return context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(ContactsContract.Contacts.IS_USER_PROFILE),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        } ?: false
    }

    private fun applyOps(context: Context, ops: ArrayList<ContentProviderOperation>): Boolean {
        if (ops.isEmpty()) return true
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (error: Exception) {
            Log.e(TAG, "applyBatch failed: ${error.message}")
            false
        }
    }
}
