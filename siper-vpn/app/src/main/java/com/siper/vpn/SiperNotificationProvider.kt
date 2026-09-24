package com.siper.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wgtunnel.backend.AndroidApplicationProvider
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.state.BackendStatus

class SiperNotificationProvider(
    override val context: Context
) : AndroidApplicationProvider {

    companion object {
        private const val CHANNEL_ID = "siper_vpn"
        private const val CHANNEL_NAME = "Siper VPN"
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Secure tunnel status"
                setShowBadge(false)
            }
        )
    }

    override val vpnNotificationId: Int = 4101
    override val proxyNotificationId: Int = 4102

    override val vpnInitNotification: Notification
        get() = build("Siper VPN", "در حال ایجاد تونل امن…")

    override val proxyInitNotification: Notification
        get() = build("Siper VPN", "در حال ایجاد مسیر امن…")

    override fun createVpnConfigurePendingIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override suspend fun buildVpnPersistentNotification(
        status: BackendStatus
    ): Notification {
        val active = status.activeTunnels.values.firstOrNull()
        val text = when (active?.transportState) {
            is Tunnel.State.Up.Healthy -> "متصل — Handshake سالم"
            is Tunnel.State.Up.HandshakeFailure -> "تونل فعال — Handshake ناموفق"
            is Tunnel.State.Starting -> "در حال اتصال…"
            is Tunnel.State.Stopping -> "در حال قطع…"
            else -> "VPN آماده"
        }
        return build("Siper VPN", text)
    }

    override suspend fun buildProxyPersistentNotification(
        status: BackendStatus
    ): Notification = build("Siper VPN", "Proxy فعال")

    override fun refreshStatusUi() = Unit

    private fun build(title: String, text: String): Notification {
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(createVpnConfigurePendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
