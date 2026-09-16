package com.zuruikyoku.yoink.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.zuruikyoku.yoink.R

@Composable
fun MagpieMascot(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Image(
        painter = painterResource(R.drawable.ic_magpie),
        contentDescription = stringResource(R.string.cd_magpie_mascot),
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier
    )
}
