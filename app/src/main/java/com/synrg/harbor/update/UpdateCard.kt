package com.synrg.harbor.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synrg.harbor.R
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors

/** The update offer and its progress: same card on Home and in Settings. Shows nothing when idle. */
@Composable
fun UpdateCard(state: UpdateState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updater = context.graph.updater
    when (state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> Card(stringResource(R.string.update_available, state.release.version), notesPreview(state.release.notes), modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.update_action), HIcons.Refresh, onClick = { updater.start(state.release) }, modifier = Modifier.weight(1f))
                GlassButton(stringResource(R.string.common_not_now), null, onClick = { updater.dismiss(state.release) })
            }
        }
        is UpdateState.Downloading -> Card(stringResource(R.string.update_downloading, state.release.version), "${(state.progress * 100).toInt()}%", modifier) {
            LinearProgressIndicator(
                progress = { state.progress },
                color = HarborColors.Accent,
                trackColor = HarborColors.SurfaceHigh,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
        is UpdateState.NeedsPermission -> Card(
            stringResource(R.string.update_permission_title),
            stringResource(R.string.update_permission_body),
            modifier,
        ) {
            PrimaryButton(stringResource(R.string.update_open_android_settings), null, onClick = { context.startActivity(updater.permissionIntent()) }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Installing -> Card(
            stringResource(R.string.update_installing, state.release.version),
            stringResource(R.string.update_installing_body),
            modifier,
        ) {}
        is UpdateState.Failed -> Card(stringResource(R.string.update_failed), state.message, modifier) {
            val release = state.release
            if (release != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.common_retry), HIcons.Refresh, onClick = { updater.start(release) }, modifier = Modifier.weight(1f))
                    GlassButton(stringResource(R.string.common_not_now), null, onClick = { updater.dismiss(release) })
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, body: String, modifier: Modifier, actions: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(HarborColors.Surface)
            .border(1.dp, HarborColors.Accent.copy(alpha = 0.35f), shape).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = HarborColors.TextDim,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
        }
        actions()
    }
}

/** The release notes in short: each item's bold lead ("**New tab:** …" → "New tab"), or its text. */
private fun notesPreview(notes: String): String =
    notes.substringBefore("\n---").lines()
        .map { it.trim().removePrefix("- ") }
        .filter { it.isNotBlank() }
        .map { line ->
            val lead = if (line.startsWith("**")) line.removePrefix("**").substringBefore("**") else line
            lead.replace("**", "").replace("`", "").trim().removeSuffix(":")
        }
        .take(3)
        .joinToString("\n") { "• $it" }
