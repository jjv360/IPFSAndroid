package com.jjv360.ipfsandroid

import android.app.Activity
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    // Recycler view
    lateinit var recyclerView : RecyclerView

    // Called on activity create
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Update activity name
        title = "IPFS Tools"

        // Create table
        recyclerView = RecyclerView(this)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setPadding(0, 20, 0, 0)
        setContentView(recyclerView)

        // Update favorites
        updateListItems()

    }

    // Called when the activity is going to come on screen
    override fun onResume() {
        super.onResume()

        // Start the service
        BackgroundService.start(this)

    }

    // Update the list of favorites
    fun updateListItems() {

        // Remove old ones
        listItems.clear()

        // Add dashboard tool
        listItems.add(ListItem(type = ListItemType.RowItem, title = "Dashboard", text = "Open the WebUI", icon = R.drawable.ic_speedometer, action = {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:5001/webui")))
            finish()
        }))

        // Open link tool
        listItems.add(ListItem(type = ListItemType.RowItem, title = "Open link", text = "Enter an IPFS address", icon = R.drawable.ic_search, action = {

            // Create text field
            val field = EditText(this)
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            field.setPadding(20, 20, 20, 20)

            // Create dialog
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter address or hash")
            builder.setView(field)
            builder.setCancelable(true)
            builder.setPositiveButton("Go", DialogInterface.OnClickListener { dialog, which ->

                // Parse Uri
                val uri : Uri
                try {
                    uri = Uri.parse(field.text.toString())
                } catch (ex : Throwable) {
                    Log.w("IPFSTool", "Unable to parse Uri entered. ${ex.message}")
                    return@OnClickListener
                }

                // Go to the address
                val intent = Intent(this, OpenURLActivity::class.java)
                intent.data = uri
                startActivity(intent)
                finish()

            })

            // Show it
            builder.show()

        }))

        // Add shutdown button
        listItems.add(ListItem(type = ListItemType.RowItem, title = "Shutdown", text = "Stop the IPFS node", icon = R.drawable.ic_shutdown, action = {
            val shutdownIntent = Intent(this, BackgroundService::class.java)
            shutdownIntent.putExtra("service-action", "shutdown")
            startService(shutdownIntent)
            finish()
        }))

        // Add about button
        listItems.add(ListItem(type = ListItemType.RowItem, title = "About", text = "More info about this app", icon = R.drawable.ic_info, action = {

            // Check if service is running
            var statsText = "- Node is offline"
            if (BackgroundService.instance != null) {

                // Gather time running
                val svc = BackgroundService.instance!!
                val duration = System.currentTimeMillis() - svc.dateStarted
                var durationText = ""
                if (duration < 1000*60) durationText = "${duration / 1000} seconds"
                else if (duration < 1000*60*60) durationText = "${duration / 1000 / 60} minutes"
                else durationText = "${duration / 1000 / 60 / 60} hours"

                // Gather transferred stats
                val amtTransferred = svc.readableFileSize(svc.ipfsTotalTransferred)

                // Gather repo size info
                val ipfsFolder = File(cacheDir, "ipfs")
                val ipfsRepo = File(ipfsFolder, "repo")
                val repoSize = svc.readableFileSize(size(ipfsRepo.toPath()))

                // Create stats text
                statsText = """
                    |- Transferred this session: $amtTransferred
                    |- Current session duration: $durationText
                    |- Repo file size: $repoSize
                """.trimMargin()

            }

            // Create info text
            val text = """
                |This app runs an IPFS node on your device. The node will only run while it's in use, and will shut itself down after a few minutes of inactivity. 
                |
                |If your device is plugged in to power, the node won't shut down until the power is removed.
                |
                |Info: 
                |- Go IPFS: v0.7.0
                |$statsText
            """.trimMargin()

            // Show about UI
            val builder = AlertDialog.Builder(this)
            builder.setTitle("IPFS Tools")
            builder.setMessage(text)
            builder.setNegativeButton("Close", null)

            // Show it
            builder.show()

        }))

        // Refresh UI
        recyclerView.adapter?.notifyDataSetChanged()

    }

    // Called when the user selects an item from the list
    fun onClick(idx : Int) {

        // Find item
        val item = listItems[idx]

        // Run action
        if (item.action != null)
            item.action!!()

    }

    // Called when the user holds on an item from the list
    fun onLongClick(idx : Int) {

        // Find item
        val item = listItems[idx]

        // Stop if can't delete
        if (!item.canDelete)
            return

        // Show edit menu
        val builder = AlertDialog.Builder(this)
        builder.setTitle(item.title)
        builder.setMessage("Do you want to delete this item?")
        builder.setNegativeButton("Cancel", null)
        builder.setPositiveButton("Delete", DialogInterface.OnClickListener { dialog, which ->

            // Delete this item

        })

        // Show it
        builder.show()

//        builder.setItems(listOf("Delete").toTypedArray(), DialogInterface.OnClickListener { dialog, which ->
//
//            // Check what was pressed
//            if (which == 0) {
//
//                // Delete the item
//
//            }
//
//        })

    }

    // Recycler view's data type
    enum class ListItemType {
        Header, RowItem, EmptyDescription
    }
    class ListItem(
        var type: ListItemType,
        var title : String = "",
        var text : String = "",
        var canDelete : Boolean = false,
        var icon : Int = R.drawable.ic_speedometer,
        var action : (() -> Unit)? = null
    )

    // Recycler view's data
    val listItems = mutableListOf<ListItem>()

    // Recycler view's adapter
    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // Get total items
        override fun getItemCount(): Int {
            return listItems.size
        }

        // Get the row item's type code
        override fun getItemViewType(position: Int): Int {
            return listItems[position].type.ordinal
        }

        // Create the appropriate view
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

            // Check which type of view to use
            var view : View
            if (viewType == ListItemType.Header.ordinal) {

                // Load view
                view = LayoutInflater.from(parent.context).inflate(R.layout.menu_item_header, parent, false)

            } else if (viewType == ListItemType.EmptyDescription.ordinal) {

                // Load view
                view = LayoutInflater.from(parent.context).inflate(R.layout.menu_item_emptydescription, parent, false)

            } else if (viewType == ListItemType.RowItem.ordinal) {

                // Load view
                view = LayoutInflater.from(parent.context).inflate(R.layout.menu_item, parent, false)

            } else {

                // Unknown type
                view = LayoutInflater.from(parent.context).inflate(R.layout.menu_item_header, parent, false)

            }

            // Create view holder
            val holder = object : RecyclerView.ViewHolder(view) {}

            // Add click handler
            view.setOnClickListener {
                onClick(holder.adapterPosition)
            }

            // Add long press handler
            view.setOnLongClickListener {
                onLongClick(holder.adapterPosition)
                return@setOnLongClickListener true
            }

            // Done
            return holder

        }

        // Apply the content to the view
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

            // Apply content... Check which view it is
            val item = listItems[position]
            if (item.type == ListItemType.Header) {

                // Header view is just a single label
                (holder.itemView as TextView).text = item.title

            } else if (item.type == ListItemType.EmptyDescription) {

                // Empty view is just a single label
                (holder.itemView as TextView).text = item.title

            } else if (item.type == ListItemType.RowItem) {

                // Fill in row item fields
                holder.itemView.findViewById<TextView>(R.id.rowTitle).text = item.title
                holder.itemView.findViewById<TextView>(R.id.rowText).text = item.text
                holder.itemView.findViewById<ImageView>(R.id.imageView).setImageResource(item.icon)

            }

        }


    }



    /**
     * Attempts to calculate the size of a file or directory.
     *
     * Since the operation is non-atomic, the returned value may be inaccurate.
     * However, this method is quick and does its best.
     *
     * From: https://stackoverflow.com/a/19877372/1008736
     */
    fun size(path: Path?): Long {
        val size = AtomicLong(0)
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path?>() {
                override fun visitFile(file: Path?, attrs: BasicFileAttributes): FileVisitResult? {
                    size.addAndGet(attrs.size())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
                    println("skipped: $file ($exc)")
                    // Skip folders that can't be traversed
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                    if (exc != null) println("had trouble traversing: $dir ($exc)")
                    // Ignore errors traversing a folder
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
//            throw AssertionError("walkFileTree will not throw IOException if the FileVisitor does not")
            return 0
        }
        return size.get()
    }

}