package com.example.pingme

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LogActivity : AppCompatActivity() {

    fun getLogFileContent(context: Context): String {
        val logFile = File(context.filesDir, "test_failures.log")
        return if (logFile.exists()) {
            logFile.readText()
        } else {
            "Il file di log non esiste."
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val logContent = getLogFileContent(this)
        val logTextView: TextView = findViewById(R.id.logTextView)
        logTextView.text = logContent
    }
}