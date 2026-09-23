package net.daniellehmann.localchat

import android.content.Context

/** Remembers the last used server and model for new conversations. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("localchat", Context.MODE_PRIVATE)

    var lastServerId: Long
        get() = sp.getLong("lastServerId", -1)
        set(v) = sp.edit().putLong("lastServerId", v).apply()

    var lastModel: String
        get() = sp.getString("lastModel", "") ?: ""
        set(v) = sp.edit().putString("lastModel", v).apply()
}
