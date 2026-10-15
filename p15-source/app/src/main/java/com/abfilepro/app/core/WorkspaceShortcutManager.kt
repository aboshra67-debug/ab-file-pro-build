package com.abfilepro.app.core

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R

object WorkspaceShortcutManager {
    private const val PREFS = "ab_file_pro_workspace_shortcut"
    private const val KEY_REQUESTED_FOR = "requested_for"

    private fun buildShortcut(context: Context, workspaceName: String): ShortcutInfoCompat {
        val label = WorkspaceShortcutRules.shortLabel(workspaceName)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(WorkspaceShortcutRules.EXTRA_OPEN_WORKSPACE, true)
        }
        return ShortcutInfoCompat.Builder(context, WorkspaceShortcutRules.shortcutId(workspaceName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ab_file_manager_icon_color))
            .setIntent(launchIntent)
            .build()
    }

    fun requestPin(context: Context, workspaceName: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, buildShortcut(context, workspaceName), null)
    }

    /**
     * Keep an existing pinned shortcut updated (label/icon) without creating duplicates.
     * If none exists, request it once. Android launchers may show one confirmation dialog.
     */
    fun requestPinOnce(context: Context, workspaceName: String): Boolean {
        val shortcut = buildShortcut(context, workspaceName)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pinned = runCatching {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .any { it.id == shortcut.id }
        }.getOrDefault(false)

        if (pinned) {
            runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut)) }
            prefs.edit().putString(KEY_REQUESTED_FOR, shortcut.id).apply()
            return true
        }

        if (prefs.getString(KEY_REQUESTED_FOR, null) == shortcut.id) return false
        val requested = requestPin(context, workspaceName)
        if (requested) prefs.edit().putString(KEY_REQUESTED_FOR, shortcut.id).apply()
        return requested
    }
}
