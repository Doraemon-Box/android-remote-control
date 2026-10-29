package com.remotecontrol.config

import android.content.Context
import java.net.URI

data class ServerConfig(
    val signalingUrl: String,
    val turnUrls: List<String>,
    val turnUsername: String,
    val turnPassword: String
) {
    companion object {
        val DEFAULT = ServerConfig(
            signalingUrl = "ws://47.94.172.185:8080",
            turnUrls = emptyList(),
            turnUsername = "",
            turnPassword = ""
        )

        fun isValidSignalingUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
        } catch (_: Exception) { false }

        fun isValidTurnUrl(value: String): Boolean = try {
            val scheme = value.substringBefore(':')
            val address = value.substringAfter(':', "")
            val uri = URI("$scheme://$address")
            scheme in listOf("turn", "turns") && !address.startsWith("//") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && uri.rawPath.isNullOrEmpty() &&
                (uri.port == -1 || uri.port in 1..65535) &&
                (uri.rawQuery == null || uri.rawQuery in listOf("transport=udp", "transport=tcp"))
        } catch (_: Exception) { false }
    }
}

/** App-private preferences survive Activity/process recreation and device restarts. */
class ServerConfigStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("server_config", Context.MODE_PRIVATE)

    fun hasSavedConfig(): Boolean = preferences.contains("signaling_url")

    fun load(): ServerConfig {
        val defaults = ServerConfig.DEFAULT
        return ServerConfig(
            preferences.getString("signaling_url", defaults.signalingUrl)!!,
            preferences.getString("turn_urls", defaults.turnUrls.joinToString("\n"))!!
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
            preferences.getString("turn_username", defaults.turnUsername)!!,
            preferences.getString("turn_password", defaults.turnPassword)!!
        )
    }

    fun save(config: ServerConfig): Boolean = preferences.edit()
        .putString("signaling_url", config.signalingUrl)
        .putString("turn_urls", config.turnUrls.joinToString("\n"))
        .putString("turn_username", config.turnUsername)
        .putString("turn_password", config.turnPassword)
        .commit()
}
