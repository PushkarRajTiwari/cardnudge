package com.cardnudge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardnudge.domain.CardArt
import com.cardnudge.domain.Finish
import com.cardnudge.domain.Network
import com.cardnudge.platform.PHOTO_MAX_PX
import com.cardnudge.platform.loadCardPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Standard ID-1 card proportions (85.6 × 53.98 mm). */
const val CARD_RATIO = 1.586f

private val FaceShape = RoundedCornerShape(22.dp)
private val ChipBrush = Brush.linearGradient(listOf(Color(0xFFFFE7A3), Color(0xFFC9A227)))

/** Converted once per art (art objects are long-lived singletons), not per frame. */
private val brushCache = HashMap<String, Brush>()
fun CardArt.brush(): Brush = brushCache.getOrPut(id) { Brush.linearGradient(colors.map { Color(it) }) }

fun CardArt.glowColor(): Color = Color(colors[colors.size / 2])

/**
 * A credit-card face: the user's photo when there is one, otherwise the drawn [art]
 * with a metal sheen or matte finish. Layout always keeps real card proportions.
 */
@Composable
fun CardFace(
    art: CardArt,
    name: String,
    bank: String,
    network: Network?,
    photo: String?,
    modifier: Modifier = Modifier,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    val glow = art.glowColor()
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .shadow(16.dp, FaceShape, ambientColor = glow, spotColor = glow)
            .clip(FaceShape)
    ) {
        val bitmap = photo?.let { rememberCardPhoto(it) }
        if (bitmap != null) {
            Image(bitmap, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            DrawnFace(art, name, bank, network)
        }
        badge?.invoke(this)
    }
}

@Composable
private fun DrawnFace(art: CardArt, name: String, bank: String, network: Network?) {
    val ink = if (art.darkText) Color(0xFF2A2116) else Color.White
    Column(
        Modifier
            .fillMaxSize()
            .background(art.brush())
            .finish(art.finish)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(
            bank.ifBlank { "Card" }.uppercase(),
            color = ink.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        )
        Box(Modifier.padding(top = 14.dp).size(42.dp, 31.dp).clip(RoundedCornerShape(7.dp)).background(ChipBrush))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, color = ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (network != null) {
                Text(network.label, color = ink.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            }
        }
    }
}

/** Metal: a diagonal light band. Matte: a faint dark wash. Both cached per size. */
private fun Modifier.finish(finish: Finish): Modifier = when (finish) {
    Finish.GLOSS -> this
    Finish.MATTE -> drawWithCache { onDrawWithContent { drawContent(); drawRect(Color(0x14000000)) } }
    Finish.METAL -> drawWithCache {
        val sheen = Brush.linearGradient(
            0.0f to Color.Transparent,
            0.42f to Color(0x00FFFFFF),
            0.5f to Color(0x40FFFFFF),
            0.58f to Color(0x00FFFFFF),
            1.0f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawWithContent { drawContent(); drawRect(sheen) }
    }
}

/**
 * Loads a saved card photo off the main thread. The bitmap belongs to this composition,
 * so it is released as soon as the card scrolls away or the screen closes.
 */
@Composable
private fun rememberCardPhoto(name: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.Default) { loadCardPhoto(name, PHOTO_MAX_PX) }
    }
    return bitmap
}
