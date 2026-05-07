package com.petpal.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.petpal.app.FloatingPetService
import com.petpal.app.R

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStartPet)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        btnStart.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startPet()
            } else {
                requestOverlayPermission()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStartButton()
    }

    private fun updateStartButton() {
        val btnStart = findViewById<Button>(R.id.btnStartPet)
        if (FloatingPetService.isRunning()) {
            btnStart.text = "关闭桌宠"
            btnStart.setOnClickListener { stopPet() }
        } else {
            btnStart.text = "启动桌宠"
            btnStart.setOnClickListener {
                if (Settings.canDrawOverlays(this)) startPet()
                else requestOverlayPermission()
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                startPet()
            }
        }
    }

    private fun startPet() {
        val intent = Intent(this, FloatingPetService::class.java)
        startForegroundService(intent)
        updateStartButton()
    }

    private fun stopPet() {
        val intent = Intent(this, FloatingPetService::class.java)
        stopService(intent)
        updateStartButton()
    }
}
