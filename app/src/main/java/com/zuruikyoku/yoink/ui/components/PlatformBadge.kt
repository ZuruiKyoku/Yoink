package com.zuruikyoku.yoink.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.data.platform.Platform

private fun Platform.shortLabel(): String = when (this) {
    Platform.TWITTER -> "X"
    Platform.INSTAGRAM -> "IG"
    Platform.PINTEREST -> "PIN"
}

@Composable
fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.cd_platform_badge) + ": " + platform.displayName
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text = platform.shortLabel(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
