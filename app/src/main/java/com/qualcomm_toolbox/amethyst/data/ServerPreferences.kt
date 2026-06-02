package com.qualcomm_toolbox.amethyst.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

class ServerPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_SERVER, value?.let { normalizeServerUrl(it) }).apply()
        }

    var savedUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    val hasServer: Boolean get() = !serverUrl.isNullOrBlank()

    /** When true, accepts self-signed / invalid HTTPS certs (needed on some Android 6 devices). */
    var trustAllCertificates: Boolean
        get() = prefs.getBoolean(
            KEY_TRUST_ALL_CERTS,
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.M,
        )
        set(value) {
            prefs.edit().putBoolean(KEY_TRUST_ALL_CERTS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "amethyst_prefs"
        private const val KEY_SERVER = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_TRUST_ALL_CERTS = "trust_all_certs"

        fun normalizeServerUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            while (url.endsWith('/')) {
                url = url.dropLast(1)
            }
            return url
        }
    }
}
