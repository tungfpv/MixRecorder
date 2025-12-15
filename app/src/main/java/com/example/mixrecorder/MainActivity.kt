package com.example.mixrecorder


import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var recorder: MixRecorder? = null
    private lateinit var btnStart : Button;
    private lateinit var btnStop : Button;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart = findViewById<Button>(R.id.btnStart)
        btnStop = findViewById<Button>(R.id.btnStop)
        btnStart.isEnabled = true
        btnStop.isEnabled = false

        btnStart.setOnClickListener {
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                1001
            )
        }

        btnStop.setOnClickListener {
            recorder?.stop()
            val stopIntent = Intent(this, RecordService::class.java)
            stopIntent.action = RecordService.ACTION_STOP
            startService(stopIntent)

        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {

            val i = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        }
    }

    private val recordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                RecordService.ACTION_STARTED -> {
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Start recording",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                RecordService.ACTION_STOPPED -> {
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    val path = intent.getStringExtra(RecordService.EXTRA_FILE)
                    Toast.makeText(
                        this@MainActivity,
                        "Saved: $path",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()
        registerReceiver(
            recordReceiver,
            IntentFilter().apply {
                addAction(RecordService.ACTION_STARTED)
                addAction(RecordService.ACTION_STOPPED)
            },
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(recordReceiver)
    }


}
