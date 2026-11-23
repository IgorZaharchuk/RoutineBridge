package com.routinebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class SamsungRoutineService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            Log.e(TAG, "FOREGROUND_START_FAILED", error)
            waitForQueueAndStop(startId)
            return START_NOT_STICKY
        }

        val input = try {
            val id = intent?.getStringExtra(EXTRA_INPUT_ID)
            val label = intent?.getStringExtra(EXTRA_LABEL)
            val routineUuid = requireNotNull(intent).getLongExtra(EXTRA_ROUTINE_UUID, Long.MIN_VALUE)
            val routineName = intent?.getStringExtra(EXTRA_ROUTINE_NAME)
            val actionName = intent?.getStringExtra(EXTRA_ACTION)
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)

            require(!id.isNullOrBlank())
            require(!label.isNullOrBlank())
            require(routineUuid > 0L)
            require(!routineName.isNullOrBlank())
            require(!requestId.isNullOrBlank())

            RoutineInput(
                id = id,
                label = label,
                routineUuid = routineUuid,
                routineName = routineName,
                action = RoutineInputAction.valueOf(requireNotNull(actionName))
            ) to requestId
        } catch (error: Exception) {
            Log.e(TAG, "SERVICE_INVALID_COMMAND", error)
            waitForQueueAndStop(startId)
            return START_NOT_STICKY
        }

        Log.i(

            TAG,

            "SERVICE_COMMAND_RECEIVED request=${input.second} " +

                "inputId=${input.first.id} uuid=${input.first.routineUuid} " +

                "action=${input.first.action}"

        )


        try {
            SamsungRoutineDispatcher.enqueue(
                applicationContext,
                input.first,
                input.second
            ) {
                mainHandler.post {
                    stopWhenDrained(startId)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "SERVICE_ENQUEUE_FAILED", error)
            waitForQueueAndStop(startId)
        }

        return START_NOT_STICKY
    }

    private fun waitForQueueAndStop(startId: Int) {
        SamsungRoutineDispatcher.whenWorkerStopped {
            mainHandler.post {
                stopWhenDrained(startId)
            }
        }
    }

    private fun stopWhenDrained(startId: Int) {
        if (!SamsungRoutineDispatcher.isWorkerStopped()) return

        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.routine_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.routine_service_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.routine_service_notification_title))
            .setContentText(getString(R.string.routine_service_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RoutineBridge"
        private const val CHANNEL_ID = "routine_execution"
        private const val NOTIFICATION_ID = 7401

        const val EXTRA_INPUT_ID = "routine_input_id"
        const val EXTRA_LABEL = "routine_input_label"
        const val EXTRA_ROUTINE_UUID = "routine_uuid"
        const val EXTRA_ROUTINE_NAME = "routine_name"
        const val EXTRA_ACTION = "routine_action"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
