package com.videomaker.app.model

/**
 * Colour filters and adjustments as a single 4×5 colour matrix (row-major, like Android's
 * ColorMatrix) with offsets in the 0..1 range.
 *
 * The same matrix drives both the exported video (as a GPU effect) and the thumbnails in the UI,
 * so what you see in the filter picker is what you get.
 */
enum class FilterPreset(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    VINTAGE("Vintage"),
    MATTE("Matte"),
    DRAMA("Drama"),
    MONO("Mono"),
    NOIR("Noir"),
    SEPIA("Sepia"),
}

object ColorGrading {

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    val IDENTITY: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Full look of a clip: filter (blended by strength) followed by manual adjustments. */
    fun matrixFor(filter: FilterPreset, strength: Float, adjust: ColorAdjust): FloatArray {
        val filtered = lerp(IDENTITY, presetMatrix(filter), strength.coerceIn(0f, 1f))
        return concat(adjustMatrix(adjust), filtered)
    }

    fun matrixFor(clip: MediaClip): FloatArray = matrixFor(clip.filter, clip.filterStrength, clip.adjust)

    fun presetMatrix(preset: FilterPreset): FloatArray = when (preset) {
        FilterPreset.NONE -> IDENTITY
        FilterPreset.VIVID -> concat(contrast(1.12f), saturation(1.45f))
        FilterPreset.WARM -> concat(channels(1.08f, 1.0f, 0.86f, 0.02f, 0.01f, 0f), saturation(1.1f))
        FilterPreset.COOL -> concat(channels(0.9f, 1.0f, 1.1f, 0f, 0.01f, 0.03f), saturation(0.95f))
        FilterPreset.VINTAGE -> concat(lift(0.06f), concat(contrast(0.88f), lerp(IDENTITY, sepia(), 0.55f)))
        FilterPreset.MATTE -> concat(lift(0.08f), concat(contrast(0.82f), saturation(0.8f)))
        FilterPreset.DRAMA -> concat(contrast(1.3f), concat(saturation(0.8f), lift(-0.03f)))
        FilterPreset.MONO -> saturation(0f)
        FilterPreset.NOIR -> concat(contrast(1.45f), saturation(0f))
        FilterPreset.SEPIA -> sepia()
    }

    fun adjustMatrix(a: ColorAdjust): FloatArray {
        var m = IDENTITY
        if (a.warmth != 0f) {
            val w = a.warmth * 0.12f
            m = concat(channels(1f + w, 1f + w * 0.15f, 1f - w, 0f, 0f, 0f), m)
        }
        if (a.saturation != 0f) m = concat(saturation(1f + a.saturation), m)
        if (a.contrast != 0f) {
            val k = if (a.contrast > 0) 1f + a.contrast * 0.6f else 1f + a.contrast * 0.5f
            m = concat(contrast(k), m)
        }
        if (a.brightness != 0f) m = concat(lift(a.brightness * 0.25f), m)
        return m
    }

    fun isIdentity(m: FloatArray): Boolean = m.indices.all { kotlin.math.abs(m[it] - IDENTITY[it]) < 1e-4f }

    // ---- Building blocks -----------------------------------------------------------------------

    private fun saturation(s: Float): FloatArray {
        val i = 1f - s
        return floatArrayOf(
            LR * i + s, LG * i, LB * i, 0f, 0f,
            LR * i, LG * i + s, LB * i, 0f, 0f,
            LR * i, LG * i, LB * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun contrast(k: Float): FloatArray {
        val o = (1f - k) * 0.5f
        return floatArrayOf(
            k, 0f, 0f, 0f, o,
            0f, k, 0f, 0f, o,
            0f, 0f, k, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun lift(o: Float): FloatArray = channels(1f, 1f, 1f, o, o, o)

    private fun channels(r: Float, g: Float, b: Float, ro: Float, go: Float, bo: Float) = floatArrayOf(
        r, 0f, 0f, 0f, ro,
        0f, g, 0f, 0f, go,
        0f, 0f, b, 0f, bo,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun sepia() = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Returns a·b: applies [b] first, then [a]. */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = if (col == 4) a[row * 5 + 4] else 0f
                for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(20) { a[it] + (b[it] - a[it]) * t }

    /**
     * Converts to the column-major 4×4 matrix Media3's RgbMatrix expects. The shader multiplies
     * (r, g, b, 1), so the offsets go in the fourth column. Colours are scaled by [scale]
     * (used for fades to black).
     */
    fun toGlMatrix(m: FloatArray, scale: Float = 1f): FloatArray {
        val gl = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) gl[col * 4 + row] = m[row * 5 + col] * scale
            gl[3 * 4 + row] = m[row * 5 + 4] * scale
        }
        gl[15] = 1f
        return gl
    }
}
