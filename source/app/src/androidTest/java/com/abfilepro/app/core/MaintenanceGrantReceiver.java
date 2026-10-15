package com.abfilepro.app.core;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

/** Test APK components run separately from the instrumented app. Use only platform Java APIs. */
public final class MaintenanceGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String target = intent.getStringExtra("target_package");
        if (!"com.abfilepro.app.p13sharetrial".equals(target) && !"com.abfilepro.app.p14mainttrial".equals(target)
                && !"com.abfilepro.app.p15mainttrial".equals(target)
                && !"com.abfilepro.app.p16mainttrial".equals(target)
                && !"com.abfilepro.app.p20mainttrial".equals(target)) {
            throw new IllegalArgumentException("Only isolated trial apps may read test fixtures");
        }
        Uri tree = DocumentsContract.buildTreeDocumentUri("com.abfilepro.app.maintenance.test.documents", "root");
        context.grantUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Log.i("P14Fixture", "Granted fixture tree to " + target);
        setResultCode(Activity.RESULT_OK);
    }
}
