package com.zuruikyoku.yoink.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.ui.theme.HeistBackground
import com.zuruikyoku.yoink.ui.theme.HeistYellow

// The logo is a full-color illustration (black/white/yellow), not a single-color glyph, so
// unlike the line-art mascot this replaced, it's drawn as-is with no tint applied. A soft
// radial glow - yellow centered behind the bird, blurred out to the page's own background
// color so it blends rather than showing a hard edge - sits behind it. The glow's box is
// sized well past the bird itself so the blur has room to fall off before it would otherwise
// get clipped.
private const val GLOW_SIZE_MULTIPLIER = 2.2f
private const val GLOW_BLUR_MULTIPLIER = 0.35f

@Composable
fun MagpieMascot(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val glowSize = size * GLOW_SIZE_MULTIPLIER
    Box(
        modifier = modifier.size(glowSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(size * GLOW_BLUR_MULTIPLIER)
                .background(Brush.radialGradient(listOf(HeistYellow, HeistBackground)))
        )
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = stringResource(R.string.cd_magpie_mascot),
            modifier = Modifier.size(size)
        )
    }
}
