package com.example.mixrecorder


import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat

class RecordService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "mix_record_channel"

        const val ACTION_STARTED = "com.example.mixrecorder.STARTED"
        const val ACTION_STOPPED = "com.example.mixrecorder.STOPPED"
        const val EXTRA_FILE = "file"
    }

    private var recorder: MixRecorder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_START -> {
                startForeground(1, buildNotification())

                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("data")

                if (resultCode == Activity.RESULT_OK && data != null) {
                    val projection = pm.getMediaProjection(resultCode, data)
                    recorder = projection?.let { MixRecorder(this, it) }
                    recorder!!.start()
                    notifyStarted()
                }
            }

            ACTION_STOP -> {
                recorder?.stop()
                stopForeground(true)
                stopSelf()
                recorder?.let { notifyStopped(it.fileName) }
            }
        }
        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Notification ----------------

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mix Recorder")
            .setContentText("Đang ghi mic + system audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mix Recorder",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    private fun notifyStarted() {
        sendBroadcast(
            Intent(ACTION_STARTED).apply {
                setPackage(packageName) // 🔐 chỉ app này nhận
            }
        )
    }

    private fun notifyStopped(path: String) {
        sendBroadcast(
            Intent(ACTION_STOPPED).apply {
                setPackage(packageName)
                putExtra(EXTRA_FILE, path)
            }
        )
    }



}
