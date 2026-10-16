package com.zbrowser.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zbrowser.MainActivity

/** Foreground service with a visible notification so Android keeps the loop alive. */
class AgentService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentLoop.requestStop()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val text = intent?.getStringExtra("text") ?: "Agent running"
        startForeground(1, buildNotif(this, "ZBrowser", text, true))
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zbrowser:agent").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val CH = "zb_agent"
        const val ACTION_STOP = "com.zbrowser.STOP"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Agent", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun buildNotif(ctx: Context, title: String, text: String, ongoing: Boolean): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val b = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setOngoing(ongoing)
            if (ongoing) {
                val stop = PendingIntent.getService(ctx, 1,
                    Intent(ctx, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
                b.addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            }
            return b.build()
        }

        fun start(ctx: Context, text: String) {
            ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra("text", text))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AgentService::class.java)) }

        /** Tell the user why the agent stopped. */
        fun alert(ctx: Context, text: String) {
            ensureChannel(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(2, buildNotif(ctx, "ZBrowser stopped", text, false))
        }
    }
}
