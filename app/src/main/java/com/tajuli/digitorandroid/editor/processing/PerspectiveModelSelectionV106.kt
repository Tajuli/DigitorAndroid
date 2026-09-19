package com.tajuli.digitorandroid.editor.processing

import kotlin.math.ln
import kotlin.math.max

internal fun perspectiveModelTrustV106(
    similarityErrorsPx: List<Float>,
    projectiveErrorsPx: List<Float>,
): Float {
    if (
        similarityErrorsPx.size != projectiveErrorsPx.size ||
        similarityErrorsPx.size < MIN_MODEL_SELECTION_POINTS_V106
    ) return 0f

    val n = similarityErrorsPx.size.toDouble()
    val similarityMse = similarityErrorsPx.sumOf { it.toDouble() * it.toDouble() } / n
    val projectiveMse = projectiveErrorsPx.sumOf { it.toDouble() * it.toDouble() } / n
    if (!similarityMse.isFinite() || !projectiveMse.isFinite() || projectiveMse >= similarityMse) {
        return 0f
    }

    val safeSimilarityMse = max(similarityMse, MIN_MODEL_MSE_V106)
    val safeProjectiveMse = max(projectiveMse, MIN_MODEL_MSE_V106)
    val similarityBic =
        n * ln(safeSimilarityMse) + SIMILARITY_PARAMETER_COUNT_V106 * ln(n)
    val projectiveBic =
        n * ln(safeProjectiveMse) + PROJECTIVE_PARAMETER_COUNT_V106 * ln(n)
    val bicAdvantage = similarityBic - projectiveBic
    if (bicAdvantage <= MIN_PROJECTIVE_BIC_ADVANTAGE_V106) return 0f

    val relativeGain = ((similarityMse - projectiveMse) / safeSimilarityMse)
        .toFloat().coerceIn(0f, 1f)
    if (relativeGain < MIN_PROJECTIVE_RELATIVE_GAIN_V106) return 0f

    val bicTrust = (
        (bicAdvantage - MIN_PROJECTIVE_BIC_ADVANTAGE_V106) /
            (FULL_PROJECTIVE_BIC_ADVANTAGE_V106 - MIN_PROJECTIVE_BIC_ADVANTAGE_V106)
        ).toFloat().coerceIn(0f, 1f)
    val gainTrust = (
        (relativeGain - MIN_PROJECTIVE_RELATIVE_GAIN_V106) /
            (FULL_PROJECTIVE_RELATIVE_GAIN_V106 - MIN_PROJECTIVE_RELATIVE_GAIN_V106)
        ).coerceIn(0f, 1f)

    val b = bicTrust * bicTrust * (3f - 2f * bicTrust)
    val g = gainTrust * gainTrust * (3f - 2f * gainTrust)
    return (b * g).coerceIn(0f, 1f)
}

private const val MIN_MODEL_SELECTION_POINTS_V106 = 8
private const val MIN_MODEL_MSE_V106 = 1e-4
private const val SIMILARITY_PARAMETER_COUNT_V106 = 4.0
private const val PROJECTIVE_PARAMETER_COUNT_V106 = 8.0
private const val MIN_PROJECTIVE_BIC_ADVANTAGE_V106 = 2.0
private const val FULL_PROJECTIVE_BIC_ADVANTAGE_V106 = 12.0
private const val MIN_PROJECTIVE_RELATIVE_GAIN_V106 = .14f
private const val FULL_PROJECTIVE_RELATIVE_GAIN_V106 = .40f
