package com.landpoint.app.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Resolves strings for code that runs outside composition — ViewModel snackbar
 * messages, PDF labels, share text. Resources are looked up per call so a
 * language change takes effect without restarting anything.
 */
class AppStrings(private val context: Context) {

    fun get(@StringRes id: Int): String =
        Localization.wrap(context).resources.getString(id)

    fun get(@StringRes id: Int, vararg args: Any): String =
        Localization.wrap(context).resources.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int): String =
        Localization.wrap(context).resources.getQuantityString(id, count, count)

    fun array(@androidx.annotation.ArrayRes id: Int): Array<String> =
        Localization.wrap(context).resources.getStringArray(id)
}
