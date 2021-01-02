package com.jjv360.ipfsandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import nl.komponents.kovenant.then
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi

class OpenURLActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load UI
        setContentView(R.layout.activity_open_url)

    }

    override fun onResume() {
        super.onResume()

        // Ensure we have an intent Uri
        if (intent?.data == null) finish()
        val uri = intent.data!!

        // Handle the different Uri formats
        if (uri.scheme == "ipfs" || uri.scheme == "ipns") {

            // Hostname is the hash
            openPath("/${uri.scheme}/${uri.host}")

        } else if (uri.pathSegments.size >= 2 && (uri.pathSegments[0] == "ipfs" || uri.pathSegments[0] == "ipns")) {

            // Second param is the hash
            openPath("/${uri.pathSegments[0]}/${uri.pathSegments[1]}")

        } else if (uri.pathSegments.size == 1 && uri.pathSegments[0].matches(Regex.fromLiteral("^[0-9a-zA-Z]{20,}$"))) {

            // May have entered just the hash itself
            openPath("/ipfs/${uri.pathSegments[0]}")

        } else if (uri.pathSegments.size == 1 && uri.pathSegments[0].matches(Regex.fromLiteral("^[0-9a-zA-Z\\.-_].*$"))) {

            // May have entered just an ipns hash itself
            openPath("/ipns/${uri.pathSegments[0]}")

        } else {

            // Error!
            showErrorAndClose("Invalid link", "Unable to read the IPFS address format.")
            Log.w("IPFSTool", "Unknown Uri format: $uri path=${uri.pathSegments} host=${uri.host} scheme=${uri.scheme}")

        }

    }

    // Called to open the specified Uri
    fun openPath(path : String) {

        // Wait for service to start
        BackgroundService.start(this) successUi {

            // Go to Uri
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:8080" + path)))
            finish()

        } failUi {

            // Failed!
            showErrorAndClose("Couldn't start IPFS", it.localizedMessage)

        }

    }

    // Show error dialog
    fun showErrorAndClose(title : String, text : String) {

        // Show about UI
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(text)
        builder.setNegativeButton("Close", null)
        builder.setOnDismissListener {
            finish()
        }

        // Show it
        builder.show()

    }

}