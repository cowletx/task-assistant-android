package com.example.taskassistant.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.taskassistant.MainActivity
import com.example.taskassistant.R

/**
 * 训练期间的前台服务：让休息倒计时在应用退到后台后仍准确，并让用户能看到训练进度。
 *
 * Android 版本适配（全部按系统规范实现，不做保活）：
 *  - API 26+：必须先建立 NotificationChannel；
 *  - API 29+：startForeground 必须传 foregroundServiceType（Manifest 里声明 specialUse）；
 *  - API 33+：POST_NOTIFICATIONS 是运行时权限。**未授权时不启动前台服务**，
 *    避免 Android 14+ 因不可见通知抛 ForegroundServiceStartNotAllowedException，
 *    此时界面内的倒计时仍然工作；
 *  - API 34+：specialUse 需要在 Manifest 里用 <property> 说明用途（见 AndroidManifest.xml）。
 *
 * 生命周期：训练进入 → start/update；训练结束或用户点通知上的「结束训练」→ stop。
 * 只在训练进行中存在，结束后立刻 stopSelf()，不做常驻后台。
 */
class WorkoutTimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知上的「结束训练」按钮：直接停服务（训练状态由应用界面负责结束）
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        createChannel()
        val notification = buildNotification(title, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.workout_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.workout_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WorkoutTimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.workout_notification_stop), stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "workout_timer"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val ACTION_STOP = "com.example.taskassistant.action.STOP_WORKOUT_TIMER"

        /**
         * 启动/更新训练前台服务。
         *
         * @return true 表示已请求启动；false 表示当前系统不允许（API 33+ 未授权通知），
         *         调用方应退回「仅界面内计时」。
         */
        fun start(context: Context, title: String, text: String, canPostNotifications: Boolean): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications) {
                return false
            }
            val intent = Intent(context, WorkoutTimerService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: IllegalStateException) {
                // 系统在后台启动限制下拒绝时不要崩溃，退回界面内计时
                false
            }
        }

        /** 停止服务（训练结束时调用）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WorkoutTimerService::class.java)) }
        }

        /** 判断是否已授予通知权限（API 32 及以下视为已授予）。 */
        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
