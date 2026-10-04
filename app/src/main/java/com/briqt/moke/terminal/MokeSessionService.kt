package com.briqt.moke.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务：有存活或正在建连的会话时提高后台优先级，并持有 CPU / Wi-Fi 锁供心跳和读线程使用。
 * 会话对象由 [MokeApplication.sessions] 持有；已断线标签只保留终端内容，不再持锁耗电。
 * 锁与前台服务不能保证永不断线，Doze、厂商限制及网络变化仍可能中断连接。
 */
class MokeSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var sessionObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moke:sessions")
            .apply { setReferenceCounted(false) }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "moke:sessions-wifi")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = (application as MokeApplication).sessions.sessions.value.count { it.alive.value }
        // 即使建连已失败，也先满足 startForegroundService 的前台启动要求，再立即停服。
        startForeground(NOTIF_ID, buildNotification(count))
        started = true
        updateActiveSessions(count)
        if (count > 0 && sessionObserver == null) observeSessions()
        // 无会话可续时不自动重启（连接无法凭空恢复）。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    private fun observeSessions() {
        val sessions = (application as MokeApplication).sessions
        sessionObserver = scope.launch {
            sessions.sessions.collectLatest { list ->
                if (list.isEmpty()) {
                    if (started) updateActiveSessions(0)
                } else {
                    // alive 从建连开始为 true，结束才转 false；列表不变时也必须观察断线。
                    combine(list.map { it.alive }) { states -> states.count { it } }
                        .collect { count -> if (started) updateActiveSessions(count) }
                }
            }
        }
    }

    private fun updateActiveSessions(count: Int) {
        if (count == 0) {
            releaseLocks()
            stopForegroundCompat()
            started = false
            stopSelf()
        } else {
            try {
                wakeLock?.let { if (!it.isHeld) it.acquire() }
                wifiLock?.let { if (!it.isHeld) it.acquire() }
            } catch (e: RuntimeException) {
                releaseLocks()
                throw e
            }
            notificationManager().notify(NOTIF_ID, buildNotification(count))
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
    }

    private fun buildNotification(count: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Moke")
            .setContentText(localized(R.string.notif_sessions_active, count))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                // 点通知要回到会话，而不只是把 app 拉到前台（见 MainActivity.ACTION_OPEN_SESSIONS）；
                // SINGLE_TOP|CLEAR_TOP 复用已有界面，不另起一个。
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SESSIONS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, localized(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = localized(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager().createNotificationChannel(ch)
        }
    }

    private fun notificationManager() =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "moke_sessions"
        private const val NOTIF_ID = 1001
    }
}
