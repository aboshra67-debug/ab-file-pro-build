package com.abfilepro.app.core

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abfilepro.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** P16: the Google Play channel must never update itself or hold the package-installer permission. */
@RunWith(AndroidJUnit4::class)
class PlayChannelPolicyTest {
    @Test fun playChannelDisablesSelfUpdate() {
        assertEquals("play", BuildConfig.DISTRIBUTION_CHANNEL)
        assertFalse("Self-update must be off in the Play channel", BuildConfig.SELF_UPDATE_ENABLED)
        assertFalse(AppUpdateManager.isEnabled)
    }

    @Test fun playBuildDoesNotRequestInstallPackages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("REQUEST_INSTALL_PACKAGES is declared in the Play build",
            "android.permission.REQUEST_INSTALL_PACKAGES" in requested)
    }
}
