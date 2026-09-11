package org.fossify.phone.helpers

/**
 * Kids cannot create, edit, or delete Android contacts from this APK.
 * Parents own the list (Mongo → Esper `allowlist_json` on this package).
 * This process upserts/deletes Contacts Provider. `co.phonly.contacts` is retired.
 * The UI still only reads that DB.
 */
fun canEditDeviceContacts(): Boolean = false
