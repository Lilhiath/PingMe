package com.example.pingme

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        findViewById<Button>(R.id.viewLogButton).setOnClickListener {
            val intent = Intent(this, LogActivity::class.java)
            startActivity(intent)
        }

        findViewById<Button>(R.id.shareLogButton).setOnClickListener {
            shareLogFile(this)
        }

        val configsCopy = testConfigs.toList()
        configsCopy.forEach { config ->
            addTestButton(config)
        }
    }


    val LOG_FILE_NAME = "test_failures_log.txt"
    val MAX_LOG_ENTRIES = 100

    private fun addFailureLog(name: String, address: String, port: Int) {
        val logFile = File(filesDir, "test_failures.log")

        // Verifica se il file esiste e crealo se non esiste
        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
                Log.d("MainActivity", "File di log creato: ${logFile.absolutePath}")
            } catch (e: IOException) {
                Log.e("MainActivity", "Errore durante la creazione del file di log", e)
            }
        }

        // Aggiungi un nuovo log
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$currentTime - $name - $address - $port\n"

        try {
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
            Log.d("MainActivity", "Log aggiunto: $logEntry")
        } catch (e: IOException) {
            Log.e("MainActivity", "Errore durante l'aggiunta al file di log", e)
        }

        // Mantieni solo gli ultimi 100 log
        limitLogFileEntries(logFile)
    }

    private fun limitLogFileEntries(logFile: File) {
        try {
            val lines = logFile.readLines()
            if (lines.size > 100) {
                val linesToKeep = lines.takeLast(100)
                logFile.writeText(linesToKeep.joinToString("\n"))
                Log.d("MainActivity", "File di log limitato agli ultimi 100 log")
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Errore durante il limite delle righe del file di log", e)
        }
    }

    // Funzione per permettere la condivisione/copia del file di log
    private fun shareLogFile(mainActivity: MainActivity) {
        val logFile = File(filesDir, "test_failures.log")

        if (logFile.exists()) {
            try {
                val packageName = "com.example.pingme"
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Test Failures Log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Condividi log"))
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to share log", e)
                Toast.makeText(this, "Errore durante la condivisione del log", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Il file di log non esiste", Toast.LENGTH_SHORT).show()
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
        statusButton.setOnClickListener {
            showButtonMenu(statusButton, testConfig) // Associa il menu
        }
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

        // Aggiungi la configurazione dei test se non è già presente
        if (testConfigs.none { it == testConfig }) {
            testConfigs.add(testConfig)
            saveTestConfigs()
        }
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

    private fun getLogFileContent(context: Context): String {
        val logFile = File(context.filesDir, "test_failures.log")
        return if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: IOException) {
                "Errore nella lettura del file di log: ${e.message}"
            }
        } else {
            "Il file di log non esiste."
        }
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

        // Aggiungi la configurazione dei test se non è già presente
        if (testConfigs.none { it == config }) {
            testConfigs.add(config)
            saveTestConfigs() // Salva le configurazioni aggiornate
        }

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
            val port = inputPort.text.toString().toIntOrNull() ?: run {
                Toast.makeText(this@MainActivity, "Invalid port number", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val interval = (inputInterval.text.toString().toLongOrNull() ?: run {
                Toast.makeText(this@MainActivity, "Invalid interval", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }) * 1000
            val maxFailures = inputFailures.text.toString().toIntOrNull() ?: run {
                Toast.makeText(this@MainActivity, "Invalid max failures", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val testType = testTypeSpinner.selectedItem.toString()

            // Update the test configuration
            val updatedConfig = TestConfig(address, port, interval, testType, name, maxFailures)
            val index = testConfigs.indexOf(testConfig)
            if (index != -1) {
                testConfigs[index] = updatedConfig
                saveTestConfigs() // Salva le configurazioni aggiornate

                // Remove and re-add the button with updated configuration
                removeTestButton(button)
                addTestButton(updatedConfig)
            }
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

        // Rimuovi la configurazione di test dal testConfigs
        val testConfig = statusButton.tag as? TestConfig
        if (testConfig != null) {
            testConfigs.remove(testConfig)
            saveTestConfigs() // Salva le configurazioni aggiornate
        }

        // Rimuovi il pulsante dal layout
        val parent = statusButton.parent as? LinearLayout
        parent?.let {
            buttonContainer.removeView(it)
        }

        // Aggiorna l'interfaccia utente
        buttonContainer.invalidate()
        buttonContainer.requestLayout()
    }

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
                    addFailureLog(name, address, port)
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
                    addFailureLog(name, address, port) // Aggiungi questa riga
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
