package com.example.darren.drawing

import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.get
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private var drawingView: DrawingView? = null
    private var mImageButtonCurrentPaint: ImageButton? = null
    private var customProgressDialog: Dialog? = null
    private var mPathOfLastFile: String? = null

    private val openGalleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
            result ->
            if(result.resultCode == RESULT_OK && result.data != null){
                val imageBackground: ImageView = findViewById(R.id.iv_background)
                imageBackground.setImageURI(result.data?.data)
            }
        }

    private val permissionsResultLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                val permissionName = it.key
                val isGranted = it.value
                if (isGranted) {
                    when (permissionName) {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE -> {
                            Toast.makeText(this, "Permission granted now you can read the storage files.", Toast.LENGTH_LONG).show()
                            val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            openGalleryLauncher.launch(pickIntent)
                        }
                    }
                } else {
                    when (permissionName) {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE -> {
                            Toast.makeText(this, "Oops you just deny the permission.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val linearLayoutPaintColor = findViewById<LinearLayout>(R.id.ll_paint_color)

        mImageButtonCurrentPaint = linearLayoutPaintColor[1] as ImageButton
        mImageButtonCurrentPaint!!.setImageDrawable(
            ContextCompat.getDrawable(this, R.drawable.pallet_selected)
        )

        drawingView = findViewById(R.id.drawing_view)
        drawingView?.setBrushSize(20.0f)

        val brushBtn: ImageButton = findViewById(R.id.ib_brush)
        brushBtn.setOnClickListener { showBrushSizeChooserDialog() }

        val undoBtn: ImageButton = findViewById(R.id.ib_undo)
        undoBtn.setOnClickListener {
            drawingView?.onClickUndo()
        }

        val redoBtn: ImageButton = findViewById(R.id.ib_redo)
        redoBtn.setOnClickListener {
            drawingView?.onClickRedo()
        }

        val galleryBtn: ImageButton = findViewById(R.id.ib_gallery)
        galleryBtn.setOnClickListener {
            requestExternalStoragePermission()
        }

        val saveBtn: ImageButton = findViewById(R.id.ib_save)
        saveBtn.setOnClickListener {
            //TODO: Fix redesign the permission mechanism
            if(isReadStorageAllowed()){
                lifecycleScope.launch{
                    showProgressDialog()
                    val flDrawingView: FrameLayout = findViewById(R.id.fl_drawing_view_container)
                    saveBitmapFile(getBitmapFromView(flDrawingView))
                }
            }
        }

        val shareBtn: ImageButton = findViewById(R.id.ib_share)
        shareBtn.setOnClickListener {
            if (mPathOfLastFile == null){
                Toast.makeText(this@MainActivity, "You should save your drawing before sharing it.",Toast.LENGTH_LONG).show()
            } else{
                shareImage(mPathOfLastFile!!)
            }
        }

    }

    private fun requestExternalStoragePermission(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)){
            showRationaleDialog("Permission",
                "This function needs permission.")
        } else{
            permissionsResultLauncher.launch(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun showBrushSizeChooserDialog(){
        val brushDialog = Dialog(this)
        brushDialog.setContentView(R.layout.dialog_brush_size)
        brushDialog.setTitle("Brush size: ")
        val smallBtn: ImageButton = brushDialog.findViewById(R.id.ib_small_brush)
        smallBtn.setOnClickListener {
            drawingView?.setBrushSize(10.0f)
            brushDialog.dismiss()
        }
        val mediumBtn: ImageButton = brushDialog.findViewById(R.id.ib_medium_brush)
        mediumBtn.setOnClickListener {
            drawingView?.setBrushSize(20.0f)
            brushDialog.dismiss()

        }
        val largeBtn: ImageButton = brushDialog.findViewById(R.id.ib_large_brush)
        largeBtn.setOnClickListener {
            drawingView?.setBrushSize(30.0f)
            brushDialog.dismiss()
        }
        brushDialog.show()
    }

    fun paintClicked(view: View){
        if(view != mImageButtonCurrentPaint){
            val imageButton = view as ImageButton
            val colorTag = imageButton.tag.toString()
            drawingView?.setColor(colorTag)

            imageButton.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.pallet_selected)
            )
            mImageButtonCurrentPaint!!.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.pallet_normal)
            )

            mImageButtonCurrentPaint = view
        }
    }

    private fun showRationaleDialog(title: String, message: String){
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(title)
            .setMessage(message)
            .setPositiveButton("Cancel") { dialogInterface, _ -> dialogInterface.dismiss() }

        builder.create().show()

    }

    private fun getBitmapFromView(view: View): Bitmap {
        val returnedBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(returnedBitmap)
        val bgDrawable = view.background
        if(bgDrawable != null){
            bgDrawable.draw(canvas)
        } else{
            canvas.drawColor(Color.WHITE)
        }
        view.draw(canvas)
        return returnedBitmap
    }

    private suspend fun saveBitmapFile(mBitmap: Bitmap? ): String{
        var result = ""
        withContext(Dispatchers.IO){
            if(mBitmap != null){
                try{
                    val bytes = ByteArrayOutputStream()
                    mBitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes)

                    val f = File(externalCacheDir?.absoluteFile.toString() +
                            File.separator + "DrawingApp_" + System.currentTimeMillis() /1000 + ".png" )
                    val fo = FileOutputStream(f)
                    fo.write(bytes.toByteArray())
                    fo.close()

                    result = f.absolutePath

                    runOnUiThread {
                        cancelProgressDialog()
                        if(result.isNotEmpty()){
                            Toast.makeText(this@MainActivity,
                                "File saved successfully: $result",
                                Toast.LENGTH_LONG).show()
                                mPathOfLastFile = result
                        } else{
                            Toast.makeText(this@MainActivity,
                                "Something went wrong while saving the file.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception){
                    result = ""
                    e.printStackTrace()
                }
            }
        }
        return result
    }

    private fun isReadStorageAllowed(): Boolean{
        val result = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    private fun showProgressDialog(){
        customProgressDialog = Dialog(this@MainActivity)
        customProgressDialog?.setContentView(R.layout.dialog_custom_progress)
        customProgressDialog?.show()
    }

    private fun cancelProgressDialog(){
        if(customProgressDialog != null){
            customProgressDialog?.dismiss()
            customProgressDialog = null
        }
    }

    private fun shareImage(result: String){
        MediaScannerConnection.scanFile(this, arrayOf(result), null){
            path, uri ->
            val shareIntent = Intent()
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            shareIntent.type = "image/png"
            startActivity(Intent.createChooser(shareIntent, "Share"))
        }
    }

}