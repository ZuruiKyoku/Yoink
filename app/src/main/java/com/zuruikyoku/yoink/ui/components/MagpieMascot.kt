package com.zuruikyoku.yoink.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.zuruikyoku.yoink.R

// The logo is a full-color illustration (black/white/yellow), not a single-color glyph, so
// unlike the line-art mascot this replaced, it's drawn as-is with no tint applied.
@Composable
fun MagpieMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = stringResource(R.string.cd_magpie_mascot),
        modifier = modifier
    )
}
