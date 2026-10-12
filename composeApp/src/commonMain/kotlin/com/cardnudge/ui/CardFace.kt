package com.cardnudge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    last4: String? = null,
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
            DrawnFace(art, name, bank, network, last4)
        }
        badge?.invoke(this)
    }
}

/**
 * A printed-card look: bank top left, card name top right, chip, last digits bottom left and
 * the network wordmark bottom right, over the art's colours with diagonal light bands.
 */
@Composable
private fun DrawnFace(art: CardArt, name: String, bank: String, network: Network?, last4: String?) {
    val ink = if (art.darkText) Color(0xFF2A2116) else Color.White
    Box(
        Modifier
            .fillMaxSize()
            .background(art.brush())
            .bands(art.finish)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(
            bank.ifBlank { "Card" }.uppercase(),
            color = ink.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.5f),
        )
        Text(
            name.uppercase(),
            color = ink, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp,
            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.48f),
        )
        Box(Modifier.align(Alignment.CenterStart).padding(bottom = 6.dp).size(42.dp, 31.dp).clip(RoundedCornerShape(7.dp)).background(ChipBrush))
        if (!last4.isNullOrBlank()) {
            Text(
                "••$last4", color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (network != null) NetworkMark(network, ink, Modifier.align(Alignment.BottomEnd))
    }
}

/** Text-only network wordmarks; real logos are trademarks we don't bundle. */
@Composable
private fun NetworkMark(network: Network, ink: Color, modifier: Modifier) {
    when (network) {
        Network.VISA -> Text(
            "VISA", color = ink, fontSize = 28.sp, fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic, letterSpacing = (-1).sp, modifier = modifier,
        )
        Network.AMEX -> Text(
            "AMEX", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = modifier.border(1.5.dp, ink, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Network.MASTERCARD -> Box(modifier.size(46.dp, 28.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(28.dp).clip(CircleShape).background(Color(0xFFEB001B)))
            Box(Modifier.align(Alignment.CenterEnd).size(28.dp).clip(CircleShape).background(Color(0xCCF79E1B)))
        }
        Network.DISCOVER -> Text(
            "DISCOVER", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, modifier = modifier,
        )
    }
}

/** Diagonal light and shade bands like printed card art, plus the finish. Cached per size. */
private fun Modifier.bands(finish: Finish): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val light = Path().apply {
        moveTo(w * 0.42f, 0f); lineTo(w * 0.62f, 0f); lineTo(w * 0.32f, h); lineTo(w * 0.12f, h); close()
    }
    val shade = Path().apply { moveTo(w, h * 0.35f); lineTo(w, h); lineTo(w * 0.25f, h); close() }
    val lightColor = Color.White.copy(alpha = if (finish == Finish.MATTE) 0.05f else 0.10f)
    onDrawWithContent {
        drawContent()
        drawPath(light, lightColor)
        drawPath(shade, Color.Black.copy(alpha = 0.12f))
    }
}.finish(finish)

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
