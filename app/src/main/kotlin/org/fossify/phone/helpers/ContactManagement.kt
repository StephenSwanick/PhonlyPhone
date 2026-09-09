package org.fossify.phone.helpers

/**
 * Kids cannot create, edit, or delete Android contacts from this APK.
 * Parents own the list (Mongo → Esper → [co.phonly.contacts]).
 * This app only reads Contacts Provider for names and tap-to-call.
 */
fun canEditDeviceContacts(): Boolean = false
