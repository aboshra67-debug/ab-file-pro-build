package com.abfilepro.app.core

import android.content.Context
import java.io.File

object ExplorerFavoriteStore {
    private const val PREFS = "ab_file_pro_explorer_favorites"
    private const val KEY_PATHS = "paths"

    private fun normalized(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    fun paths(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PATHS, emptySet())
            ?.toSet()
            .orEmpty()

    fun isFavorite(context: Context, file: File): Boolean = normalized(file) in paths(context)

    fun setFavorite(context: Context, file: File, favorite: Boolean) {
        val current = paths(context).toMutableSet()
        val path = normalized(file)
        if (favorite) current += path else current -= path
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PATHS, current).apply()
    }

    fun toggle(context: Context, file: File): Boolean {
        val next = !isFavorite(context, file)
        setFavorite(context, file, next)
        return next
    }
}
