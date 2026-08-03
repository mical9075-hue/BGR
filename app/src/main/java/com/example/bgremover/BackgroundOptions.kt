package com.example.bgremover

/**
 * Represents one selectable background in the "Change Background" picker.
 */
sealed class BackgroundOption {

    /** A flat solid color fill. */
    data class SolidColor(val color: Int) : BackgroundOption()

    /** A simple two-color diagonal gradient fill. */
    data class Gradient(val startColor: Int, val endColor: Int) : BackgroundOption()

    /** A curated free stock photo (Picsum) — thumbnail for the grid, full-res URL to composite with. */
    data class OnlineImage(val id: String, val thumbUrl: String, val fullUrl: String) : BackgroundOption()

    /** Special entry: opens the system gallery picker to choose a custom photo. */
    object PickFromGallery : BackgroundOption()

    /** Special entry: resets to the transparent (checkerboard) cutout — no background applied. */
    object Transparent : BackgroundOption()
}

object BackgroundPresets {

    /** A tasteful, varied palette — neutrals, brand-friendly colors, and a few bold accents. */
    val solidColors: List<BackgroundOption.SolidColor> = listOf(
        0xFFFFFFFF.toInt(), // white
        0xFF000000.toInt(), // black
        0xFFF5F5F5.toInt(), // light gray
        0xFF1C1B1F.toInt(), // near-black
        0xFF6750A4.toInt(), // deep purple
        0xFF2E7D32.toInt(), // green
        0xFF1565C0.toInt(), // blue
        0xFFC62828.toInt(), // red
        0xFFEF6C00.toInt(), // orange
        0xFFF9A825.toInt(), // yellow
        0xFFAD1457.toInt(), // pink/magenta
        0xFF00838F.toInt()  // teal
    ).map { BackgroundOption.SolidColor(it) }

    /** Smooth two-color gradients for a more polished studio-style backdrop. */
    val gradients: List<BackgroundOption.Gradient> = listOf(
        0xFF6750A4.toInt() to 0xFF9A82DB.toInt(), // purple
        0xFF1565C0.toInt() to 0xFF64B5F6.toInt(), // blue sky
        0xFF2E7D32.toInt() to 0xFF81C784.toInt(), // green
        0xFFEF6C00.toInt() to 0xFFFFB74D.toInt(), // sunset orange
        0xFF37474F.toInt() to 0xFF90A4AE.toInt(), // slate gray
        0xFFC62828.toInt() to 0xFFEF9A9A.toInt(), // red blush
        0xFF000000.toInt() to 0xFF424242.toInt(), // studio black
        0xFFAD1457.toInt() to 0xFFF48FB1.toInt()  // magenta
    ).map { BackgroundOption.Gradient(it.first, it.second) }

    /**
     * Curated Picsum (https://picsum.photos) photo IDs spanning nature, texture,
     * and abstract themes — a free, no-API-key photo service good for backdrops.
     * Requires internet; thumbnails are small, full-res fetch happens on selection.
     */
    private val curatedPicsumIds = listOf(
        "1015", "1016", "1018", "1024", "1035", "1041",
        "1043", "1050", "1060", "1074", "1080", "110"
    )

    val onlineImages: List<BackgroundOption.OnlineImage> = curatedPicsumIds.map { id ->
        BackgroundOption.OnlineImage(
            id = id,
            thumbUrl = "https://picsum.photos/id/$id/300/300",
            fullUrl = "https://picsum.photos/id/$id/2000/2000"
        )
    }
}
