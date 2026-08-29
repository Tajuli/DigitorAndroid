package com.tajuli.digitorandroid.editor.model

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** Camera/input profiles converted into the editor's Rec.709 grading working space. */
enum class InputColorProfile(val displayName: String, val family: String) {
    REC709("Rec.709 / Standard", "Standard"),
    SONY_SLOG2("Sony S-Log2", "Sony"),
    SONY_SLOG3_SGAMUT3_CINE("Sony S-Log3 / S-Gamut3.Cine", "Sony"),
    CANON_CLOG("Canon C-Log", "Canon"),
    CANON_CLOG2_CINEMA_GAMUT("Canon C-Log2 / Cinema Gamut", "Canon"),
    CANON_CLOG3_CINEMA_GAMUT("Canon C-Log3 / Cinema Gamut", "Canon"),
    PANASONIC_VLOG_VGAMUT("Panasonic V-Log / V-Gamut", "Panasonic"),
    ARRI_LOGC3_AWG3("ARRI LogC3 / AWG3", "ARRI"),
    BLACKMAGIC_FILM_GEN5("Blackmagic Film Gen 5", "Blackmagic"),
    DJI_DLOG_M("DJI D-Log M", "DJI"),
    FUJIFILM_FLOG_FGAMUT("Fujifilm F-Log / F-Gamut", "Fujifilm"),
    HLG_BT2020("HLG / BT.2020", "HDR"),
    PQ_BT2020("PQ (ST 2084) / BT.2020", "HDR"),
}

/** Missing legacy Gson field resolves to standard Rec.709 without requiring a project migration. */
fun TimelineClip.resolvedInputColorProfile(): InputColorProfile =
    inputColorProfileV1 ?: InputColorProfile.REC709

/**
 * Deterministic camera-input transform used before node grading.
 *
 * The current renderer is SDR/Rec.709, so camera log/HDR values are decoded to scene linear,
 * converted from the source wide gamut where a profile defines one, highlight-compressed into the
 * SDR working range, then encoded to Rec.709. The node graph therefore receives normalised Rec.709
 * rather than a flat log signal. This same function feeds the shared preview/export LUT builder.
 */
object InputColorTransform {
    fun toWorkingRec709(profile: InputColorProfile, r: Float, g: Float, b: Float): FloatArray {
        if (profile == InputColorProfile.REC709) {
            return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }

        var lr = decode(profile, r.coerceIn(0f, 1f))
        var lg = decode(profile, g.coerceIn(0f, 1f))
        var lb = decode(profile, b.coerceIn(0f, 1f))

        val matrix = gamutToRec709(profile)
        if (matrix != null) {
            val rr = matrix[0] * lr + matrix[1] * lg + matrix[2] * lb
            val gg = matrix[3] * lr + matrix[4] * lg + matrix[5] * lb
            val bb = matrix[6] * lr + matrix[7] * lg + matrix[8] * lb
            lr = rr
            lg = gg
            lb = bb
        }

        return floatArrayOf(
            rec709Oetf(sdrShoulder(lr)),
            rec709Oetf(sdrShoulder(lg)),
            rec709Oetf(sdrShoulder(lb)),
        )
    }

    private fun decode(profile: InputColorProfile, code: Float): Float = when (profile) {
        InputColorProfile.REC709 -> rec709Eotf(code)
        InputColorProfile.SONY_SLOG2 -> slog2ToLinear(code)
        InputColorProfile.SONY_SLOG3_SGAMUT3_CINE -> slog3ToLinear(code)
        InputColorProfile.CANON_CLOG -> calibratedStopLog(code, gray = .343f, black = .125f, codePerStop = .073f)
        InputColorProfile.CANON_CLOG2_CINEMA_GAMUT -> calibratedStopLog(code, gray = .398f, black = .092f, codePerStop = .073f)
        InputColorProfile.CANON_CLOG3_CINEMA_GAMUT -> calibratedStopLog(code, gray = .343f, black = .073f, codePerStop = .073f)
        InputColorProfile.PANASONIC_VLOG_VGAMUT -> vlogToLinear(code)
        InputColorProfile.ARRI_LOGC3_AWG3 -> logC3ToLinear(code)
        InputColorProfile.BLACKMAGIC_FILM_GEN5 -> calibratedStopLog(code, gray = .383f, black = .092f, codePerStop = .073f)
        InputColorProfile.DJI_DLOG_M -> calibratedStopLog(code, gray = .385f, black = .092f, codePerStop = .075f)
        InputColorProfile.FUJIFILM_FLOG_FGAMUT -> flogToLinear(code)
        InputColorProfile.HLG_BT2020 -> hlgToLinear(code)
        InputColorProfile.PQ_BT2020 -> pqToRelativeLinear(code)
    }.coerceAtLeast(0f)

    /** Sony published S-Log3 inverse, expressed in normalized 10-bit code values. */
    private fun slog3ToLinear(code: Float): Float {
        val tenBit = code * 1023f
        val cut = 171.2102946929f
        return if (tenBit >= cut) {
            (10.0.pow(((tenBit - 420f) / 261.5f).toDouble()) * .19 - .01).toFloat()
        } else {
            (tenBit - 95f) * .01125f / (cut - 95f)
        }
    }

    /** S-Log2 inverse in normalized full-range form. */
    private fun slog2ToLinear(code: Float): Float {
        val cut = .03000122285f
        return if (code >= cut) {
            (10.0.pow(((code - .616596f - .03f) / .432699f).toDouble()) - .037584).toFloat()
        } else {
            (code - cut) / 5f
        }
    }

    /** Panasonic V-Log inverse transfer. */
    private fun vlogToLinear(code: Float): Float =
        if (code >= .181f) {
            (10.0.pow(((code - .598206f) / .241514f).toDouble()) - .00873).toFloat()
        } else {
            (code - .125f) / 5.6f
        }

    /** ARRI LogC3 EI800 inverse transfer. */
    private fun logC3ToLinear(code: Float): Float =
        if (code > .1496582f) {
            ((10.0.pow(((code - .385537f) / .247190f).toDouble()) - .052272) / 5.555556).toFloat()
        } else {
            (code - .092809f) / 5.367655f
        }

    /** Fujifilm F-Log inverse transfer. */
    private fun flogToLinear(code: Float): Float =
        if (code >= .100537775f) {
            ((10.0.pow(((code - .790453f) / .344676f).toDouble()) - .009468) / .555556).toFloat()
        } else {
            (code - .092864f) / 8.735631f
        }

    /** Calibrated stop-domain fallback used for camera curves whose vendor variants differ by body. */
    private fun calibratedStopLog(code: Float, gray: Float, black: Float, codePerStop: Float): Float {
        if (code <= black) return 0f
        return (.18 * 2.0.pow(((code - gray) / codePerStop).toDouble())).toFloat()
    }

    private fun hlgToLinear(code: Float): Float {
        val a = .17883277f
        val b = .28466892f
        val c = .55991073f
        return if (code <= .5f) {
            code * code / 3f
        } else {
            ((exp(((code - c) / a).toDouble()) + b) / 12.0).toFloat()
        }
    }

    /** ST 2084 EOTF; return is relative to a 100-nit SDR diffuse white. */
    private fun pqToRelativeLinear(code: Float): Float {
        val m1 = 2610.0 / 16384.0
        val m2 = 2523.0 / 32.0
        val c1 = 3424.0 / 4096.0
        val c2 = 2413.0 / 128.0
        val c3 = 2392.0 / 128.0
        val p = code.toDouble().pow(1.0 / m2)
        val n = max(p - c1, 0.0)
        val d = max(c2 - c3 * p, 1e-9)
        val normalized10000Nits = (n / d).pow(1.0 / m1)
        return (normalized10000Nits * 100.0).toFloat()
    }

    private fun rec709Eotf(code: Float): Float =
        if (code < .081f) code / 4.5f else ((code + .099f) / 1.099f).toDouble().pow(1.0 / .45).toFloat()

    private fun rec709Oetf(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v < .018f) 4.5f * v else (1.099 * v.toDouble().pow(.45) - .099).toFloat()
    }

    /** Keep diffuse values unchanged and roll scene-linear highlights into the SDR display range. */
    private fun sdrShoulder(value: Float): Float {
        val v = value.coerceAtLeast(0f)
        if (v <= .75f) return v
        return (.75 + .25 * (1.0 - exp(-4.0 * (v - .75)))).toFloat().coerceIn(0f, 1f)
    }

    private fun gamutToRec709(profile: InputColorProfile): FloatArray? = when (profile) {
        InputColorProfile.SONY_SLOG3_SGAMUT3_CINE -> SGAMUT3_CINE_TO_709
        InputColorProfile.CANON_CLOG2_CINEMA_GAMUT,
        InputColorProfile.CANON_CLOG3_CINEMA_GAMUT -> CINEMA_GAMUT_TO_709
        InputColorProfile.PANASONIC_VLOG_VGAMUT -> VGAMUT_TO_709
        InputColorProfile.ARRI_LOGC3_AWG3 -> AWG3_TO_709
        InputColorProfile.BLACKMAGIC_FILM_GEN5 -> BLACKMAGIC_WIDE_GAMUT_TO_709
        InputColorProfile.FUJIFILM_FLOG_FGAMUT,
        InputColorProfile.HLG_BT2020,
        InputColorProfile.PQ_BT2020 -> BT2020_TO_709
        else -> null
    }

    private val SGAMUT3_CINE_TO_709 = floatArrayOf(
        1.6269474f, -.5401385f, -.0868089f,
        -.1785155f, 1.4179409f, -.2394254f,
        -.0444361f, -.1959200f, 1.2403561f,
    )
    private val CINEMA_GAMUT_TO_709 = floatArrayOf(
        1.9238613f, -.7987607f, -.1251006f,
        -.2043108f, 1.4958985f, -.2915877f,
        -.0236850f, -.4201270f, 1.4438120f,
    )
    private val VGAMUT_TO_709 = floatArrayOf(
        1.8065759f, -.6956973f, -.1108786f,
        -.1700903f, 1.3059552f, -.1358649f,
        -.0252058f, -.1544683f, 1.1796741f,
    )
    private val AWG3_TO_709 = floatArrayOf(
        1.6175234f, -.5372866f, -.0802368f,
        -.0705727f, 1.3346131f, -.2640403f,
        -.0211017f, -.2269539f, 1.2480556f,
    )
    private val BLACKMAGIC_WIDE_GAMUT_TO_709 = floatArrayOf(
        1.5684452f, -.5227165f, -.0457287f,
        -.0863609f, 1.3449761f, -.2586152f,
        -.0520425f, -.2491468f, 1.3011893f,
    )
    private val BT2020_TO_709 = floatArrayOf(
        1.6604910f, -.5876411f, -.0728499f,
        -.1245505f, 1.1328999f, -.0083494f,
        -.0181508f, -.1005789f, 1.1187297f,
    )
}
