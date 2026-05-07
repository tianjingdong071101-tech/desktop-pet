package com.petpal.app.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.petpal.app.R

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("pet_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val rbCat = findViewById<RadioButton>(R.id.rbCat)
        val rbDog = findViewById<RadioButton>(R.id.rbDog)
        val rbBunny = findViewById<RadioButton>(R.id.rbBunny)
        val sbSpeed = findViewById<SeekBar>(R.id.sbSpeed)
        val sbIdleTime = findViewById<SeekBar>(R.id.sbIdleTime)
        val cbAutoStart = findViewById<CheckBox>(R.id.cbAutoStart)

        // 加载已保存设置
        val petType = prefs.getString("pet_type", "CAT") ?: "CAT"
        when (petType) {
            "CAT"   -> rbCat.isChecked = true
            "DOG"   -> rbDog.isChecked = true
            "BUNNY" -> rbBunny.isChecked = true
        }
        sbSpeed.progress = prefs.getInt("speed", 5)
        sbIdleTime.progress = prefs.getInt("idle_time", 10)
        cbAutoStart.isChecked = prefs.getBoolean("auto_start", false)

        // 保存事件
        val save = {
            val type = when {
                rbCat.isChecked   -> "CAT"
                rbDog.isChecked   -> "DOG"
                rbBunny.isChecked -> "BUNNY"
                else -> "CAT"
            }
            prefs.edit()
                .putString("pet_type", type)
                .putInt("speed", sbSpeed.progress)
                .putInt("idle_time", sbIdleTime.progress)
                .putBoolean("auto_start", cbAutoStart.isChecked)
                .apply()
        }

        rbCat.setOnClickListener { save() }
        rbDog.setOnClickListener { save() }
        rbBunny.setOnClickListener { save() }
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { save() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbIdleTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { save() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        cbAutoStart.setOnCheckedChangeListener { _, _ -> save() }
    }
}
