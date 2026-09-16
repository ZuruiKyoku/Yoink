package com.zuruikyoku.yoink.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.data.db.DownloadEntity
import com.zuruikyoku.yoink.data.extractor.MediaType
import com.zuruikyoku.yoink.data.platform.Platform

@Composable
fun HistoryItemRow(
    entity: DownloadEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mediaType = runCatching { MediaType.valueOf(entity.mediaType) }.getOrNull()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThumbnailBox(mediaType, entity.mediaUri)

        Column(modifier = Modifier.weight(1f)) {
            PlatformLabel(entity)
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    entity.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.nest_delete_entry)
            )
        }
    }
}

@Composable
private fun ThumbnailBox(mediaType: MediaType?, mediaUri: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = mediaUri,
            contentDescription = stringResource(R.string.cd_thumbnail),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (mediaType == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(22.dp)
            )
        } else if (mediaType == MediaType.GIF) {
            Icon(
                imageVector = Icons.Filled.GifBox,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(22.dp)
            )
        }
    }
}

@Composable
private fun PlatformLabel(entity: DownloadEntity) {
    val platform = runCatching { Platform.valueOf(entity.platform) }.getOrNull()
    Text(
        text = platform?.displayName ?: entity.platform,
        style = MaterialTheme.typography.titleMedium
    )
}
