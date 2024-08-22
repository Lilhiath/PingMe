package com.example.pingme

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import com.example.pingme.R
import java.util.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MainActivity : AppCompatActivity() {

    private lateinit var buttonContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = mutableMapOf<Button, Runnable>()
    private val failureCounts = mutableMapOf<Button, Int>()
    private val testConfigs: MutableList<TestConfig> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()
        loadTestConfigs()

        buttonContainer = findViewById(R.id.buttonContainer)

        findViewById<Button>(R.id.addButton).setOnClickListener {
            showInputDialog()
        }

        // Usa una copia della lista per evitare ConcurrentModificationException
        val configsCopy = testConfigs.toList()
        configsCopy.forEach { config ->
            addTestButton(config)
        }
    }

    private fun addTestButton(testConfig: TestConfig) {
        val horizontalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val statusButton = Button(this).apply {
            text = "${testConfig.name}\nWaiting for test..."
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_circle_grey, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusButton.tag = testConfig
        horizontalLayout.addView(statusButton)

        val openLinkButton = Button(this).apply {
            text = "🌐"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        openLinkButton.setOnClickListener {
            val url = "http://${testConfig.address}:${testConfig.port}"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error opening URL: $url", e)
                Toast.makeText(this@MainActivity, "Invalid URL", Toast.LENGTH_SHORT).show()
            }
        }

        horizontalLayout.addView(openLinkButton)

        buttonContainer.addView(horizontalLayout)

        // Programma l'esecuzione immediata e poi periodica del test
        val testRunnable = object : Runnable {
            override fun run() {
                executeTest(statusButton)
                handler.postDelayed(this, testConfig.interval)
            }
        }

        tasks[statusButton] = testRunnable
        failureCounts[statusButton] = 0 // Initialize failure count

        // Aggiorna l'interfaccia utente prima di eseguire il test
        statusButton.text = "${testConfig.name}\nRunning first test..."
        statusButton.invalidate()  // Forza il ridisegno del pulsante

        // Esegui il test immediatamente
        executeTest(statusButton)

        // Programma il test a intervalli
        handler.postDelayed(testRunnable, testConfig.interval)
    }

    private fun showInputDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter Test Details")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val inputName = EditText(this).apply { hint = "Test Name (e.g., My Server)" }
        val inputAddress = EditText(this).apply { hint = "e.g., www.example.com" }
        val inputPort = EditText(this).apply { hint = "Port (e.g., 80, 25, 587)" }
        val inputInterval = EditText(this).apply { hint = "Interval in seconds" }
        val inputFailures = EditText(this).apply { hint = "Max Failures" }

        val testTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Ping", "Mail Server Check")
            )
        }

        layout.apply {
            addView(inputName)
            addView(inputAddress)
            addView(inputPort)
            addView(inputInterval)
            addView(inputFailures)
            addView(testTypeSpinner)
        }

        builder.setView(layout)
        builder.setPositiveButton("OK") { _, _ ->
            val name = inputName.text.toString()
            val address = inputAddress.text.toString()
            val port = inputPort.text.toString().toIntOrNull() ?: 80
            val interval = (inputInterval.text.toString().toLongOrNull() ?: 5) * 1000
            val maxFailures = inputFailures.text.toString().toIntOrNull() ?: 3
            val testType = testTypeSpinner.selectedItem.toString()

            createTestButton(TestConfig(address, port, interval, testType, name, maxFailures))
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun createTestButton(config: TestConfig) {
        val horizontalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val statusButton = Button(this).apply {
            text = "${config.name}\nWaiting for test..."
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_circle_grey, 0, 0, 0)
            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = params
        }

        statusButton.tag = config
        statusButton.setOnClickListener {
            showButtonMenu(statusButton, config)
        }
        horizontalLayout.addView(statusButton)

        val openLinkButton = Button(this).apply {
            text = "🌐"
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8.dpToPx()
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = params
        }

        openLinkButton.setOnClickListener {
            val url = "http://${config.address}:${config.port}"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error opening URL: $url", e)
                Toast.makeText(this@MainActivity, "Invalid URL", Toast.LENGTH_SHORT).show()
            }


        }

        horizontalLayout.addView(openLinkButton)
        buttonContainer.addView(horizontalLayout)

        testConfigs.add(config)
        saveTestConfigs()

        // Programma l'esecuzione immediata e poi periodica del test
        val testRunnable = object : Runnable {
            override fun run() {
                executeTest(statusButton)
                handler.postDelayed(this, config.interval)
            }
        }

        // Esegui il test immediatamente
        executeTest(statusButton)

        // Programma il test a intervalli
        handler.postDelayed(testRunnable, config.interval)
    }



    fun Int.dpToPx(): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (this * density).toInt()
    }



    private fun showButtonMenu(button: Button, testConfig: TestConfig) {
        val popupMenu = PopupMenu(this, button)
        popupMenu.menuInflater.inflate(R.menu.test_button_menu, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.edit -> {
                    showEditDialog(button, testConfig)
                    true
                }
                R.id.delete -> {
                    removeTestButton(button)
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun showEditDialog(button: Button, testConfig: TestConfig) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Test Details")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val inputName = EditText(this).apply {
            hint = "Test Name (e.g., My Server)"
            setText(testConfig.name)
        }

        val inputAddress = EditText(this).apply {
            hint = "e.g., www.example.com"
            setText(testConfig.address)
        }

        val inputPort = EditText(this).apply {
            hint = "Port (e.g., 80, 25, 587)"
            setText(testConfig.port.toString())
        }

        val inputInterval = EditText(this).apply {
            hint = "Interval in seconds"
            setText((testConfig.interval / 1000).toString())
        }

        val inputFailures = EditText(this).apply {
            hint = "Max Failures"
            setText(testConfig.maxFailures.toString())
        }

        val testTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Ping", "Mail Server Check")
            )
            setSelection(if (testConfig.testType == "Ping") 0 else 1)
        }

        layout.apply {
            addView(inputName)
            addView(inputAddress)
            addView(inputPort)
            addView(inputInterval)
            addView(inputFailures)
            addView(testTypeSpinner)
        }

        builder.setView(layout)
        builder.setPositiveButton("OK") { _, _ ->
            val name = inputName.text.toString()
            val address = inputAddress.text.toString()
            val port = inputPort.text.toString().toIntOrNull() ?: 80
            val interval = (inputInterval.text.toString().toLongOrNull() ?: 5) * 1000
            val maxFailures = inputFailures.text.toString().toIntOrNull() ?: 3
            val testType = testTypeSpinner.selectedItem.toString()

            // Update the test configuration
            val updatedConfig = TestConfig(address, port, interval, testType, name, maxFailures)
            testConfigs[testConfigs.indexOf(testConfig)] = updatedConfig
            saveTestConfigs()
            button.text = "${updatedConfig.name}\nWaiting for test..."

            // Ferma il runnable corrente
            tasks[button]?.let { handler.removeCallbacks(it) }

            // Esegui il test immediatamente
            executeTest(button)

            // Riparti con il test aggiornato e programma a intervalli
            val testRunnable = object : Runnable {
                override fun run() {
                    executeTest(button)
                    handler.postDelayed(this, updatedConfig.interval)
                }
            }
            tasks[button] = testRunnable
            handler.postDelayed(testRunnable, updatedConfig.interval)
            failureCounts[button] = 0 // Reset failure count
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun removeTestButton(statusButton: Button) {
        // Cancella il Runnable associato
        tasks[statusButton]?.let {
            handler.removeCallbacks(it)
        }

        // Rimuovi il pulsante dalla mappa di tasks
        tasks.remove(statusButton)

        // Rimuovi il pulsante dalla mappa di conteggio fallimenti
        failureCounts.remove(statusButton)

        // Rimuovi il pulsante dal layout
        val parent = statusButton.parent as? LinearLayout
        parent?.let {
            buttonContainer.removeView(it)
        }

        // Aggiorna l'interfaccia utente
        buttonContainer.invalidate()
        buttonContainer.requestLayout()
    }



    //private fun deleteTestButton(button: Button) {
      //tasks[button]?.let { handler.removeCallbacks(it) }
      //  tasks.remove(button)
       // failureCounts.remove(button)
       // testConfigs.remove(button.tag)
       // saveTestConfigs()
    //}

    private fun executeTest(button: Button) {
        val testConfig = button.tag as? TestConfig ?: return

        when (testConfig.testType) {
            "Ping" -> checkPing(button, testConfig.address, testConfig.port, testConfig.name, testConfig.maxFailures)
            "Mail Server Check" -> checkMailServer(button, testConfig.address, testConfig.port, testConfig.name, testConfig.maxFailures)
        }
    }

    private fun saveTestConfigs() {
        try {
            val sharedPrefs = getSharedPreferences("TestConfigs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            val json = Gson().toJson(testConfigs)
            editor.putString("configs", json)
            editor.apply() // Usa apply() per una scrittura asincrona
            Log.d("MainActivity", "Test configs saved successfully.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving test configs", e)
        }
    }

    private fun loadTestConfigs() {
        try {
            val sharedPrefs = getSharedPreferences("TestConfigs", Context.MODE_PRIVATE)
            val json = sharedPrefs.getString("configs", "[]")
            val type = object : TypeToken<MutableList<TestConfig>>() {}.type
            testConfigs.clear() // Pulisce la lista esistente
            testConfigs.addAll(Gson().fromJson(json, type))
            Log.d("MainActivity", "Test configs loaded successfully.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading test configs", e)
        }
    }

    private fun isNotificationChannelEnabled(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = notificationManager.getNotificationChannel(channelId)
            return channel?.importance != NotificationManager.IMPORTANCE_NONE
        }
        return true
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = CHANNEL_ID
            val name = "Test Notifications"
            val descriptionText = "Notifications for test results"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(channelId) == null) {
                notificationManager.createNotificationChannel(channel)
            }
            if (!isNotificationChannelEnabled(CHANNEL_ID)) {
                // Mostra un messaggio all'utente o chiedi di abilitare le notifiche
                Toast.makeText(this, "Le notifiche sono disabilitate per questa app. Abilitarle nelle impostazioni.", Toast.LENGTH_LONG).show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    // Chiedi all'utente di abilitare le notifiche nelle impostazioni
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun sendNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Usa un'icona valida
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private class PingTask(
        private val address: String,
        private val port: Int,
        private val callback: (Boolean, Long) -> Unit
    ) : AsyncTask<Void, Void, Pair<Boolean, Long>>() {

        override fun doInBackground(vararg params: Void?): Pair<Boolean, Long> {
            return try {
                val start = System.currentTimeMillis()
                val process = Runtime.getRuntime().exec("ping -c 1 $address")
                val exitCode = process.waitFor()
                val end = System.currentTimeMillis()
                Pair(exitCode == 0, end - start)
            } catch (e: IOException) {
                Pair(false, -1)
            }
        }

        override fun onPostExecute(result: Pair<Boolean, Long>) {
            callback(result.first, result.second)
        }
    }

    override fun onPause() {
        super.onPause()
        saveTestConfigs()
    }


    private fun checkPing(button: Button, address: String, port: Int, name: String, maxFailures: Int) {
        val pingTask = object : AsyncTask<Void, Void, Pair<Boolean, Long>>() {
            override fun doInBackground(vararg params: Void?): Pair<Boolean, Long> {
                return try {
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, port), 5000)
                    socket.close()
                    val end = System.currentTimeMillis()
                    Pair(true, end - start)
                } catch (e: IOException) {
                    Pair(false, -1)
                }
            }

            override fun onPostExecute(result: Pair<Boolean, Long>) {
                val isReachable = result.first
                val responseTime = result.second
                val statusText = "$name\nPing Response Time: $responseTime ms"
                button.text = statusText
                val drawableRes = if (isReachable) R.drawable.ic_circle_green else R.drawable.ic_circle_red
                button.setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)

                val currentFailures = failureCounts[button] ?: 0
                if (isReachable) {
                    failureCounts[button] = 0
                } else {
                    failureCounts[button] = currentFailures + 1
                    if (failureCounts[button]!! >= maxFailures) {
                        sendNotification("Test Failed", "$name has failed $maxFailures times consecutively.")
                        failureCounts[button] = 0
                    }
                }
            }
        }
        pingTask.execute()
    }


    private class MailServerTask(
        private val address: String,
        private val port: Int,
        private val callback: (Boolean, Long) -> Unit
    ) : AsyncTask<Void, Void, Pair<Boolean, Long>>() {

        override fun doInBackground(vararg params: Void?): Pair<Boolean, Long> {
            return try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(address, port), 5000) // 5 secondi
                socket.close()
                val end = System.currentTimeMillis()
                Pair(true, end - start)
            } catch (e: IOException) {
                Log.e("MailServerTask", "Connection error: ${e.message}")
                Pair(false, -1)
            }
        }

        override fun onPostExecute(result: Pair<Boolean, Long>) {
            callback(result.first, result.second)
        }
    }





    private fun checkMailServer(button: Button, address: String, port: Int, name: String, maxFailures: Int) {
        MailServerTask(address, port) { isReachable, responseTime ->
            val statusText = "$name\nMail Server Response Time: $responseTime ms"
            button.text = statusText
            val drawableRes = if (isReachable) R.drawable.ic_circle_green else R.drawable.ic_circle_red
            button.setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)

            val currentFailures = failureCounts[button] ?: 0
            if (isReachable) {
                failureCounts[button] = 0
            } else {
                failureCounts[button] = currentFailures + 1
                if (failureCounts[button]!! >= maxFailures) {
                    sendNotification("Test Failed", "$name has failed $maxFailures times consecutively.")
                    failureCounts[button] = 0
                }
            }
        }.execute()
    }

    private data class TestConfig(
        val address: String,
        val port: Int,
        val interval: Long,
        val testType: String, // "Ping" or "Mail Server Check"
        val name: String,
        val maxFailures: Int
    )

    companion object {
        private const val CHANNEL_ID = "test_notifications"
    }
}
