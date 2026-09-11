package org.fossify.phone.helpers

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log

object PhonlyAccount {
    const val ACCOUNT_NAME = "Phonly"
    const val ACCOUNT_TYPE = "co.phonly.phone"
    private const val TAG = "PhonlyPhone"

    fun account(): Account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    fun ensure(context: Context): Account {
        val account = account()
        val manager = AccountManager.get(context)
        val existing = manager.getAccountsByType(ACCOUNT_TYPE)
        if (existing.none { it.name == ACCOUNT_NAME }) {
            val added = manager.addAccountExplicitly(account, null, null)
            Log.i(TAG, "addAccountExplicitly=$added")
        }
        return account()
    }
}
