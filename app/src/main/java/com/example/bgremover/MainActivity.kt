package com.example.bgremover

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.appcent.removebg.RemoveBg
import com.example.bgremover.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * BG Remover — offline, on-device background removal for BOTH people and
 * general objects (products, animals, furniture, etc.), powered by the
 * U2Net salient-object-detection model via the `removebg` library
 * (https://github.com/AppcentMobile/removebg). The model ships inside the
 * library itself — no manual model download needed.
 *
 * Quality notes:
 *  - U2Net is a general-purpose "what's the main subject" model, so unlike
 *    a selfie-only segmenter it works well on products, pets, objects, etc.
 *  - The network runs at a modest internal resolution for speed, but the
 *    resulting alpha matte is always applied back onto your FULL original
 *    photo resolution — so output sharpness matches your input photo.
 *  - An extra light edge-feathering pass smooths any residual mask noise
 *    for a cleaner, more professional cutout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var originalBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var currentComposite: Bitmap? = null
    private var cameraImageUri: Uri? = null

    private lateinit var remover: RemoveBg

    companion object {
        // Cap the longest side of any loaded image to keep processing fast
        // and memory-safe. 2000px is plenty for sharing / printing use.
        private const val MAX_DIMENSION = 2000

        // Radius (in pixels) of the box blur applied to the alpha channel
        // for extra edge feathering/polish.
        private const val FEATHER_RADIUS = 2
    }

    // ---- Activity result launchers ----

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadBitmapFromUri(it) }
        }

    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { applyCustomBackgroundFromUri(it) }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                cameraImageUri?.let { loadBitmapFromUri(it) }
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remover = RemoveBg(this)

        setupClickListeners()
        setupCompareButton()
    }

    private fun setupClickListeners() {
        binding.btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnCamera.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        binding.btnRemoveBg.setOnClickListener {
            removeBackground()
        }

        binding.btnSave.setOnClickListener {
            saveResultToGallery()
        }

        binding.btnChangeBackground.setOnClickListener {
            showBackgroundPicker()
        }
    }

    /** Press-and-hold on the "Hold to Compare" chip briefly reveals the original photo. */
    private fun setupCompareButton() {
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    originalBitmap?.let { binding.previewImage.setImageBitmap(it) }
                    binding.checkerLayer.visibility = View.GONE
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val shown = currentComposite ?: resultBitmap
                    shown?.let { binding.previewImage.setImageBitmap(it) }
                    binding.checkerLayer.visibility = if (currentComposite == null) View.VISIBLE else View.GONE
                    true
                }
                else -> false
            }
        }
    }

    private fun checkCameraPermissionAndLaunch() {
        when {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> launchCamera()

            else -> requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", imageFile
        )
        takePictureLauncher.launch(cameraImageUri)
    }

    private fun loadBitmapFromUri(uri: Uri) {
        try {
            val decoded = decodeSampledBitmap(uri, MAX_DIMENSION)
            originalBitmap = decoded
            resultBitmap = null
            currentComposite = null

            binding.previewImage.setImageBitmap(decoded)
            binding.previewImage.alpha = 0f
            binding.previewImage.animate().alpha(1f).setDuration(220).start()

            binding.emptyStateGroup.visibility = View.GONE
            binding.checkerLayer.visibility = View.GONE
            binding.btnRemoveBg.isEnabled = true
            binding.btnChangeBackground.isEnabled = false
            binding.btnSave.isEnabled = false
            binding.btnCompare.isEnabled = false
            binding.btnCompare.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Decodes an image URI downsampled to at most [maxDim] on its longest side, in ARGB_8888. */
    private fun decodeSampledBitmap(uri: Uri, maxDim: Int): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }

        var sampleSize = 1
        val longestSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        while (longestSide / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("Unable to decode image")

        val stillLongest = max(bitmap.width, bitmap.height)
        if (stillLongest > maxDim) {
            val scale = maxDim.toFloat() / stillLongest
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            bitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        return bitmap
    }

    private fun removeBackground() {
        val bitmap = originalBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Pehle koi image select karein", Toast.LENGTH_SHORT).show()
            return
        }

        binding.processingOverlay.visibility = View.VISIBLE
        binding.btnRemoveBg.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.btnCompare.isEnabled = false

        lifecycleScope.launch {
            try {
                remover.clearBackground(bitmap).collect { output ->
                    // Library returns the cutout bitmap; apply a light feather
                    // pass on the alpha channel for extra edge smoothness.
                    val polished = withContext(Dispatchers.Default) {
                        featherAlphaEdges(output, FEATHER_RADIUS)
                    }

                    resultBitmap = polished
                    currentComposite = null
                    binding.checkerLayer.visibility = View.VISIBLE
                    crossfadeToResult(polished)
                    binding.processingOverlay.visibility = View.GONE
                    binding.btnRemoveBg.isEnabled = true
                    binding.btnChangeBackground.isEnabled = true
                    binding.btnSave.isEnabled = true
                    binding.btnCompare.isEnabled = true
                    binding.btnCompare.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Background removed!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.processingOverlay.visibility = View.GONE
                binding.btnRemoveBg.isEnabled = true
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Smooth crossfade from whatever's showing to the freshly computed result. */
    private fun crossfadeToResult(bitmap: Bitmap) {
        val imageView = binding.previewImage
        val fadeOut = ObjectAnimator.ofFloat(imageView, View.ALPHA, 1f, 0f).setDuration(120)
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                imageView.setImageBitmap(bitmap)
                ObjectAnimator.ofFloat(imageView, View.ALPHA, 0f, 1f).setDuration(220).start()
            }
        })
        fadeOut.start()
    }* cutout bitmap, smoothing any jagged/noisy mask edges left over from
     * the segmentation model — a lightweight polish step independent of
     * whichever model produced the cutout.
     */
    private fun featherAlphaEdges(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val alpha = IntArray(pixels.size) { (pixels[it] ushr 24) and 0xFF }
        val temp = IntArray(pixels.size)
        val blurred = IntArray(pixels.size)

        // Horizontal pass
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx in 0 until width) {
                        sum += alpha[rowStart + xx]
                        count++
                    }
                }
                temp[rowStart + x] = sum / count
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy in 0 until height) {
                        sum += temp[yy * width + x]
                        count++
                    }
                }
                blurred[y * width + x] = sum / count
            }
        }

        val outPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val a = blurred[i]
            outPixels[i] = if (a <= 0) Color.TRANSPARENT else (a shl 24) or (pixels[i] and 0x00FFFFFF)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    // ---- Background replacement ----

    /** Opens the bottom-sheet grid for choosing a new background: colors, gradients, online photos, or gallery. */
    private fun showBackgroundPicker() {
        if (resultBitmap == null) {
            Toast.makeText(this, "Pehle background remove karein", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_background, null)
        dialog.setContentView(sheetView)

        val grid = sheetView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.swatchGrid)
        val onlineHint = sheetView.findViewById<android.widget.TextView>(R.id.onlineHint)
        val tabColors = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.tabColors)
        val tabGradients = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.tabGradients)
        val tabOnline = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.tabOnline)
        val tabGroup = sheetView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tabGroup)
        val btnTransparentQuick = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTransparentQuick)
        val btnGalleryQuick = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGalleryQuick)

        grid.layoutManager = GridLayoutManager(this, 4)

        lateinit var adapter: BackgroundAdapter
        adapter = BackgroundAdapter(BackgroundPresets.solidColors) { option ->
            dialog.dismiss()
            handleBackgroundSelection(option)
        }
        grid.adapter = adapter

        tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.tabColors -> {
                    onlineHint.visibility = View.GONE
                    adapter.submitList(BackgroundPresets.solidColors)
                }
                R.id.tabGradients -> {
                    onlineHint.visibility = View.GONE
                    adapter.submitList(BackgroundPresets.gradients)
                }
                R.id.tabOnline -> {
                    onlineHint.visibility = View.VISIBLE
                    adapter.submitList(BackgroundPresets.onlineImages)
                }
            }
        }

        btnTransparentQuick.setOnClickListener {
            dialog.dismiss()
            handleBackgroundSelection(BackgroundOption.Transparent)
        }

        btnGalleryQuick.setOnClickListener {
            dialog.dismiss()
            pickBackgroundLauncher.launch("image/*")
        }

        dialog.show()
    }

    private fun handleBackgroundSelection(option: BackgroundOption) {
        val cutout = resultBitmap ?: return

        when (option) {
            BackgroundOption.Transparent -> {
                currentComposite = null
                binding.checkerLayer.visibility = View.VISIBLE
                crossfadeToResult(cutout)
            }

            BackgroundOption.PickFromGallery -> {
                pickBackgroundLauncher.launch("image/*")
            }

            is BackgroundOption.SolidColor -> {
                lifecycleScope.launch {
                    binding.processingOverlay.visibility = View.VISIBLE
                    val bg = createSolidBitmap(option.color, cutout.width, cutout.height)
                    applyComposite(bg, cutout)
                    binding.processingOverlay.visibility = View.GONE
                }
            }

            is BackgroundOption.Gradient -> {
                lifecycleScope.launch {
                    binding.processingOverlay.visibility = View.VISIBLE
                    val bg = createGradientBitmap(option.startColor, option.endColor, cutout.width, cutout.height)
                    applyComposite(bg, cutout)
                    binding.processingOverlay.visibility = View.GONE
                }
            }

            is BackgroundOption.OnlineImage -> {
                lifecycleScope.launch {
                    binding.processingOverlay.visibility = View.VISIBLE
                    try {
                        val bg = withContext(Dispatchers.IO) { fetchBitmapFromUrl(option.fullUrl) }
                        applyComposite(bg, cutout)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Photo load failed — check internet connection",
                            Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        binding.processingOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** Loads a user-picked gallery photo and uses it as the new background. */
    private fun applyCustomBackgroundFromUri(uri: Uri) {
        val cutout = resultBitmap ?: return
        lifecycleScope.launch {
            binding.processingOverlay.visibility = View.VISIBLE
            try {
                val bg = withContext(Dispatchers.IO) { decodeSampledBitmap(uri, MAX_DIMENSION) }
                applyComposite(bg, cutout)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not load photo: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingOverlay.visibility = View.GONE
            }
        }
    }

    /** Composites [cutout] on top of [background] (center-cropped to cover) and shows the result. Suspends until done. */
    private suspend fun applyComposite(background: Bitmap, cutout: Bitmap) {
        val composite = withContext(Dispatchers.Default) {
            composeWithBackground(background, cutout)
        }
        currentComposite = composite
        binding.checkerLayer.visibility = View.GONE
        crossfadeToResult(composite)
    }

    /** Center-crop-scales [background] to cover the cutout's canvas, then draws the cutout on top. */
    private fun composeWithBackground(background: Bitmap, cutout: Bitmap): Bitmap {
        val targetW = cutout.width
        val targetH = cutout.height

        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Center-crop math: scale background so it fully covers the target canvas.
        val scale = max(
            targetW.toFloat() / background.width,
            targetH.toFloat() / background.height
        )
        val scaledW = background.width * scale
        val scaledH = background.height * scale
        val left = (targetW - scaledW) / 2f
        val top = (targetH - scaledH) / 2f
        val destRect = RectF(left, top, left + scaledW, top + scaledH)
        val srcRect = Rect(0, 0, background.width, background.height)

        canvas.drawBitmap(background, srcRect, destRect, null)
        canvas.drawBitmap(cutout, 0f, 0f, null)

        return output
    }

    private fun createSolidBitmap(color: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }

    private fun createGradientBitmap(startColor: Int, endColor: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = android.graphics.Paint()
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            startColor, endColor, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bmp
    }

    /** Blocking network fetch — call only from a background dispatcher. */
    private fun fetchBitmapFromUrl(url: String): Bitmap {
        URL(url).openStream().use { stream ->
            return BitmapFactory.decodeStream(stream)
                ?: throw IllegalStateException("Could not decode image from URL")
        }
    }

    private fun saveResultToGallery() {
        val bitmap = currentComposite ?: resultBitmap ?: run {
            Toast.makeText(this, "Pehle background remove karein", Toast.LENGTH_SHORT).show()
            return
        }

        val filename = "bgremoved_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.png"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BGRemover")
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } else {
                val picturesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                val file = File(picturesDir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            Toast.makeText(this, "Saved: $filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
