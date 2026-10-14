package com.abfilepro.app.core

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

/** Grants only disposable test-provider fixtures to the corresponding isolated trial app. */
class MaintenanceGrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = context.packageName.removeSuffix(".test")
        check(target in setOf("com.abfilepro.app.p13sharetrial", "com.abfilepro.app.p14mainttrial"))
        val tree = DocumentsContract.buildTreeDocumentUri("com.abfilepro.app.maintenance.test.documents", "root")
        context.grantUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        resultCode = Activity.RESULT_OK
    }
}
