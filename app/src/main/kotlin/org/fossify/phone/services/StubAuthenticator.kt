package org.fossify.phone.services

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle

class StubAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
    ): Bundle? = null

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String = ""

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle {
        return Bundle().apply {
            putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        }
    }
}
