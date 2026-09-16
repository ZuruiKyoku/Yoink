package com.zuruikyoku.yoink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.data.platform.Platform

@Composable
fun UrlInputSection(
    url: String,
    detectedPlatform: Platform?,
    isDownloading: Boolean,
    progressPercent: Int,
    errorMessageRes: Int?,
    onUrlChanged: (String) -> Unit,
    onClear: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.url_input_hint)) },
            leadingIcon = if (detectedPlatform != null) {
                { PlatformBadge(detectedPlatform) }
            } else null,
            trailingIcon = if (url.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_input))
                    }
                }
            } else null,
            singleLine = true,
            isError = errorMessageRes != null,
            enabled = !isDownloading,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        AnimatedVisibility(visible = errorMessageRes != null) {
            Text(
                text = errorMessageRes?.let { stringResource(it) } ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onDownloadClick,
            enabled = url.isNotBlank() && !isDownloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(
                    if (isDownloading) R.string.downloading_button else R.string.download_button
                ),
                style = MaterialTheme.typography.titleMedium
            )
        }

        AnimatedVisibility(visible = isDownloading) {
            val progress = progressPercent / 100f
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (progressPercent > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

