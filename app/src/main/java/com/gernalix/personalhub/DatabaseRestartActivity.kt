package com.gernalix.personalhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Tiny foreground relay in its own process; no DB or feature graph is initialized here. */
class DatabaseRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(if (intent.getBooleanExtra("rolled_back", false)) R.string.database_rolled_back else R.string.database_imported); setPadding(32, 64, 32, 32) })
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 700)
    }
}
