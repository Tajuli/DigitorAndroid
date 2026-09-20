package com.tajuli.digitorandroid.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tajuli.digitorandroid.R
import com.tajuli.digitorandroid.editor.render.FilterEffectThumbnailRendererV98

@Composable
internal fun FilterThumbnailV98(
    presetId: String,
    modifier: Modifier = Modifier,
) {
    ThumbnailV98(
        cacheKey = "filter::" + presetId,
        modifier = modifier,
    ) { context ->
        FilterEffectThumbnailRendererV98.renderFilter(context, presetId)
    }
}

@Composable
internal fun EffectThumbnailV98(
    effectName: String,
    modifier: Modifier = Modifier,
) {
    ThumbnailV98(
        cacheKey = "effect::" + effectName,
        modifier = modifier,
    ) { context ->
        FilterEffectThumbnailRendererV98.renderEffect(context, effectName)
    }
}

@Composable
private fun ThumbnailV98(
    cacheKey: String,
    modifier: Modifier,
    loader: suspend (android.content.Context) -> Bitmap,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = cacheKey,
    ) {
        value = loader(context)
    }

    Box(modifier = modifier.background(Color.Black)) {
        val ready = bitmap
        if (ready != null) {
            Image(
                bitmap = ready.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Loading state uses the same neutral source, never a fake filter approximation.
            Image(
                painter = painterResource(R.drawable.filter_effect_preview_base),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color.White.copy(alpha = .55f)),
            )
        }
    }
}
