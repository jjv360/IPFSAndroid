package com.jjv360.ipfsandroid

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder

class MainActivity : AppCompatActivity() {

    // Called on activity create
    override fun onCreate(savedInstanceState: Bundle?) {

        // Load UI
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the service
        BackgroundService.start(this)

    }

}