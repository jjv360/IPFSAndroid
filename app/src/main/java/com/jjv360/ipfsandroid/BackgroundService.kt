package com.jjv360.ipfsandroid

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.ipfs.kotlin.defaults.LocalIPFS
import nl.komponents.kovenant.Deferred
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.task
import nl.komponents.kovenant.ui.promiseOnUi
import nl.komponents.kovenant.ui.successUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Integer.max
import java.nio.charset.Charset
import java.text.DecimalFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

class BackgroundService : Service() {

    // Singleton
    companion object {

        // Shut down after this amount of inactivity
        val shutdownAfterMS = 5 * 60 * 1000

        // Sticky service notification ID
        private const val NotificationID = 1

        // Running instance
        var instance: BackgroundService? = null

        // Pending start promises
        private val pendingStartPromises = mutableListOf<Deferred<BackgroundService, Exception>>()

        // List of device listeners
        internal val listeners = mutableListOf<(BackgroundService) -> Unit>()

        // Start the service and return it
        fun start(ctx: Context): Promise<BackgroundService, Exception> {

            // Check if started
            if (instance != null) {
                instance!!.lastActivity = System.currentTimeMillis()
                return Promise.of(instance!!)
            }

            // Create a pending promise
            val pending: Deferred<BackgroundService, Exception> = deferred()
            pendingStartPromises.add(pending)

            // Start service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(Intent(ctx, BackgroundService::class.java))
            } else {
                ctx.startService(Intent(ctx, BackgroundService::class.java))
            }
            return pending.promise

        }
    }

    // Date of last activity
    var lastActivity = System.currentTimeMillis()

    // Date started
    var dateStarted = System.currentTimeMillis()

    // Running IPFS process
    var ipfsProcess : Process? = null
    var ipfsProcessReady = false
    var ipfsDownSpeed : Long = 0
    var ipfsUpSpeed : Long = 0
    var ipfsTotalTransferred : Long = 0
    var ipfsNumConnections = 0

    // Status check timer
    var ipfsCheckTimer : Timer? = null

    // True if currently connected to a power source
    var isPluggedIn = false

    // Power state broadcast receiver
    val powerBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            // Update plugged in state
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            isPluggedIn = (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)

        }
    }

    // Called when someone starts the service
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Someone wants to interact with the service, set our latest activity date
        lastActivity = System.currentTimeMillis()

        // Special: Our "Shutdown" button on the notification sends a start intent to our service
        // with a command in the extra section, check for it here
        if (intent?.getStringExtra("service-action") == "shutdown") {

            // Shutdown now
            stopSelf()

        }

        return START_STICKY
    }

    // Called when someone binds the service (we don't use this)
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    // Called on service start
    override fun onCreate() {

        // Store the active service for access from other parts of the app
        instance = this

        // Create notification channel
        val channel = NotificationChannel("service", "Service Status", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Displays the status of the service, and allows it to run in the background."

        // Register the channel
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        // Start the node
        startNode()

        // Update notification
        updateNotification()

        // Notify anyone who was waiting for us to start
        pendingStartPromises.forEach { it.resolve(this) }
        pendingStartPromises.clear()

        // Create notification update timer
        ipfsCheckTimer = Timer("IPFS Status Checker")
        ipfsCheckTimer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkStatus()
            }
        }, 3000, 3000)

        // Register power state change listener
        registerReceiver(powerBroadcastReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    }

    // Called on service shut down
    override fun onDestroy() {

        // No longer accessible
        if (instance == this) instance = null

        // Cancel timer
        ipfsCheckTimer?.cancel()
        ipfsCheckTimer = null

        // End IPFS process
        ipfsProcess?.destroy()
        ipfsProcess = null

        // Remove receiver
        unregisterReceiver(powerBroadcastReceiver)

    }

    // Displays the latest state notification
    fun updateNotification() {

        // Create it
        val notification = Notification.Builder(this, "service").setSmallIcon(R.drawable.ic_service_notification)

        // Set contents based on state
        var title = ""
        var text = ""
        if (ipfsProcess == null) {

            // Node is offline
            title = getText(R.string.notification_offline_title).toString()
            text = getText(R.string.notification_offline_text).toString()

        } else if (!ipfsProcessReady) {

            // Node is starting
            title = getText(R.string.notification_starting_title).toString()
            text = getText(R.string.notification_starting_text).toString()

        } else if (System.currentTimeMillis() - lastActivity < 30000) {

            // Recently used by another process
            title = getText(R.string.notification_running_title).toString()
            text = getText(R.string.notification_running_text).toString()

        } else if (System.currentTimeMillis() > lastActivity + shutdownAfterMS - 120000 && !isPluggedIn) {

            // Will shut down in a few minutes
            title = getText(R.string.notification_unused_title).toString()
            text = getText(R.string.notification_unused_text).toString()

        } else {

            // Node is up, but idle
            title = getText(R.string.notification_idle_title).toString()
            text = getText(R.string.notification_idle_text).toString()

        }

        // Insert vars
        title = title.replace("%down_speed", readableFileSize(ipfsDownSpeed) + "/s")
        title = title.replace("%up_speed", readableFileSize(ipfsUpSpeed) + "/s")
        title = title.replace("%total_transferred", readableFileSize(ipfsTotalTransferred))
        title = title.replace("%total_speed", readableFileSize(ipfsDownSpeed + ipfsUpSpeed) + "/s")
        title = title.replace("%connections", "$ipfsNumConnections")
        text = text.replace("%down_speed", readableFileSize(ipfsDownSpeed) + "/s")
        text = text.replace("%up_speed", readableFileSize(ipfsUpSpeed) + "/s")
        text = text.replace("%total_transferred", readableFileSize(ipfsTotalTransferred))
        text = text.replace("%total_speed", readableFileSize(ipfsDownSpeed + ipfsUpSpeed) + "/s")
        text = text.replace("%connections", "$ipfsNumConnections")

        // Apply text
        notification.setContentTitle(title)
        notification.setContentText(text)

        // Add details button
        val detailsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:5001/webui"))
        val detailsPending = PendingIntent.getActivity(this, 0, detailsIntent, 0)
        notification.addAction(Notification.Action.Builder(null, getText(R.string.notification_action_details).toString(), detailsPending).build())

        // Add shutdown button
        val shutdownIntent = Intent(this, BackgroundService::class.java)
        shutdownIntent.putExtra("service-action", "shutdown")
        val shutdownPending = PendingIntent.getService(this, 0, shutdownIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        notification.addAction(Notification.Action.Builder(null, getText(R.string.notification_action_shutdown).toString(), shutdownPending).build())

        // Add click button
        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPending = PendingIntent.getActivity(this, 0, activityIntent, 0)
        notification.setContentIntent(activityPending)

        // Show it
        startForeground(1, notification.build())

    }

    /** Start the node */
    fun startNode() {

        // Prepare IPFS folder paths
        val ipfsFolder = File(cacheDir, "ipfs")
        val ipfsRepo = File(ipfsFolder, "repo")
        val ipfsBinary = File(applicationInfo.nativeLibraryDir, "ipfs")
        if (!ipfsFolder.exists()) ipfsFolder.mkdirs()

        // Mark as executable
        ipfsBinary.setExecutable(true)

        // Create args to pass in
        val cmd = listOf<String>(
                ipfsBinary.absolutePath,        // <-- Path to binary
                "daemon",                       // <-- Run a daemon process
                "--init",                       // <-- Init the repo if it hasn't been created yet
                "--enable-gc",                  // <-- Clean out the repo periodically
                "--migrate",                    // <-- If a repo migration is necessary, do it without asking
                "--enable-pubsub-experiment",   // <-- Enable PubSub support
                "--enable-namesys-pubsub",      // <-- Enable IPNS record distribution
        )

        // Environment variables
        val envp = listOf<String>(
                "IPFS_PATH=${ipfsRepo.absolutePath}"
        )

        // Run it
        Log.i("IPFSService", "Starting IPFS daemon: ${cmd.joinToString(" ")}")
        ipfsProcessReady = false
        ipfsProcess = Runtime.getRuntime().exec(cmd.toTypedArray(), envp.toTypedArray(), ipfsFolder)

        // Update notification
        updateNotification()

        // Create pattern matcher
        val readyMatcher = StreamSearcher("Daemon is ready")
        val getBlockMatcher = StreamSearcher("BlockService GetBlock:")

        // Pipe output to our process's output
        task {
            val buffer = ByteArray(1024)
            while (ipfsProcess?.isAlive == true) {
                val amt = ipfsProcess?.inputStream?.read(buffer) ?: -1
                if (amt < 0) break
                System.out.write(buffer, 0, amt)

                // Check if ready
                if (!ipfsProcessReady && readyMatcher.add(buffer, 0, amt)) {

                    // Daemon is ready!
                    ipfsProcessReady = true
                    promiseOnUi {
                        updateNotification()
                    }

                    // Send command to start watching the logs
                    task {

                        // Monitor blockservice
                        val exitcode = Runtime.getRuntime().exec(listOf(ipfsBinary.absolutePath, "log", "level", "blockservice", "debug").toTypedArray(), envp.toTypedArray(), ipfsFolder).waitFor()
                        if (exitcode != 0)
                            Log.w("IPFSService", "Unable to set log level for blockservice")

                    }

                }

            }
        }

        // Pipe error to our process's error
        task {
            val buffer = ByteArray(1024)
            while (ipfsProcess?.isAlive == true) {
                val amt = ipfsProcess?.errorStream?.read(buffer) ?: -1
                if (amt < 0) break
                System.err.write(buffer, 0, amt)

                // Check if a block was fetched, indicating the node is in use
                if (getBlockMatcher.add(buffer, 0, amt))
                    lastActivity = System.currentTimeMillis()

            }
        }

        // Wait for the process to end
        task {

            // Get error code
            val result = ipfsProcess?.waitFor() ?: -1
            ipfsProcessReady = false
            ipfsProcess = null
            result

        } successUi {

            // Shutdown the service
            Log.w("IPFSService", "Process exited with code $it")
            stopSelf()

        }

    }

    /** Called every second, to check the status of the IPFS node. This runs on a background thread. */
    fun checkStatus() {

        // Stop if not ready
        if (!ipfsProcessReady)
            return

        // Check if should shut down
        if (!isPluggedIn && System.currentTimeMillis() > lastActivity + shutdownAfterMS) {

            // Begin shutting down
            promiseOnUi {
                stopSelf()
            }
            return

        }

        // Ignore errors
        try {

            // Get stats
            val info = LocalIPFS().stats.bandWidth()
            ipfsDownSpeed = info?.RateIn?.toLong() ?: 0
            ipfsUpSpeed = info?.RateOut?.toLong() ?: 0
            ipfsTotalTransferred = (info?.TotalIn?.toLong() ?: 0) + (info?.TotalOut?.toLong() ?: 0)

//            // Run netstat to see how many connections to our IPFS daemon exist
//            val process = Runtime.getRuntime().exec(listOf("/bin/sh", "-c", "netstat -a -n -W -t | egrep \"127.0.0.1:(5001^|8080)[ ]+ESTABLISHED\"").toTypedArray())
//
//            // Read all output
//            val out = ByteArrayOutputStream()
//            val buffer = ByteArray(1024*8)
//            while (process.isAlive) {
//                val amt = process.errorStream?.read(buffer) ?: -1
//                if (amt < 0) break
//                out.write(buffer, 0, amt)
//                if (out.size() > 1024 * 128) break
//            }
//
//            // Convert output to string
//            val processTxt = String(out.toByteArray(), Charset.forName("UTF-8"))
//            Log.i("IPFSService", processTxt)
//            val lines = processTxt.split("\n").map { it.trim() }.filter { it.startsWith("tcp") }
//
//            // Each line now represents an open connection. Minus one for our LocalIPFS connection.
//            ipfsNumConnections = max(0, lines.size - 1)
//            if (ipfsNumConnections > 0)
//                lastActivity = System.currentTimeMillis()

            // Update notification
            promiseOnUi {
                updateNotification()
            }

        } catch (err : Throwable) {
            Log.w("IPFSService", "Unable to fetch node status. ${err.message}")
        }

    }

    fun readableFileSize(size: Long): String {
        if (size <= 1024) return "0 KB"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "EB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())).toString() + " " + units[digitGroups]
    }

}