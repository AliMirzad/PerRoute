package app.perroute.compose.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.quality.QualityLabel
import app.perroute.quality.QualityScore

/** The colour of a quality label. Every use also shows the label as text, never colour alone. */
internal fun qualityColor(score: QualityScore?): Color = when (score?.label()) {
    QualityLabel.EXCELLENT, QualityLabel.GOOD -> Cyan
    QualityLabel.FAIR -> Amber
    QualityLabel.POOR, QualityLabel.UNAVAILABLE -> Red
    else -> Muted
}

/** "92 Excellent", "? Unknown" or "0 Unavailable", with freshness and confidence in the tooltip. */
@Composable
internal fun QualityPill(score: QualityScore?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = qualityColor(score)
    val label = score?.label() ?: QualityLabel.UNKNOWN
    val stale = score?.stale() == true && label != QualityLabel.UNKNOWN
    val tooltip = when {
        score == null || label == QualityLabel.UNKNOWN -> score?.headline() ?: "Not measured yet."
        else -> "${score.headline()} Confidence ${score.confidencePercent()}%." +
            (if (score.measuredAt() > 0) " Last measured ${formatAgo(score.measuredAt())}." else "") +
            (if (stale) " The result is older than 30 minutes and may be outdated." else "")
    }
    val shape = RoundedCornerShape(50)
    Tooltip(tooltip, modifier) {
        Row(
            Modifier.clip(shape).background(color.copy(.10f)).border(1.dp, color.copy(.45f), shape)
                .semantics { contentDescription = "Network quality ${label.text()}${score?.score()?.let { ", $it of 100" } ?: ""}${if (stale) ", outdated" else ""}" }
                .padding(start = 3.dp, end = if (compact) 8.dp else 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(if (compact) 20.dp else 22.dp).clip(CircleShape).background(color.copy(.18f)), contentAlignment = Alignment.Center) {
                Text(score?.score()?.toString() ?: "?", color = color, fontSize = if (compact) 9.5.sp else 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Text(label.text(), color = color, fontSize = if (compact) 9.5.sp else 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (stale) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Schedule, "Outdated", tint = Amber, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** The confidence of a score as bars and a percentage. */
@Composable
internal fun ConfidenceBadge(score: QualityScore?) {
    val percent = score?.takeIf { it.label() != QualityLabel.UNKNOWN }?.confidencePercent()
    val color = when {
        percent == null -> Muted
        percent >= 70 -> Cyan
        percent >= 40 -> Amber
        else -> Red
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Confidence ${percent?.let { "$it percent" } ?: "unknown"}" }) {
        Icon(Icons.Rounded.SignalCellularAlt, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(percent?.let { "$it%" } ?: "—", color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The expanded explanation of one server's score: each measured part, freshness and the reasoning. */
@Composable
internal fun QualityDetails(score: QualityScore, modifier: Modifier = Modifier) {
    val parts = score.components()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricTile(Icons.Rounded.Bolt, "Latency", parts.latencyMillis()?.let { "$it ms" } ?: "—")
            MetricTile(Icons.Rounded.GraphicEq, "Jitter", parts.jitterMillis()?.let { "$it ms" } ?: "—")
            MetricTile(Icons.Rounded.Shield, "Success", parts.successRate()?.let { "${Math.round(it * 100)}%" } ?: "—")
            MetricTile(Icons.Rounded.SignalCellularAlt, "Stability", parts.stabilityScore()?.let { "$it%" } ?: "—")
            MetricTile(Icons.Rounded.Timer, "Tunnel setup", parts.setupMillis()?.let { "$it ms" } ?: "Not verified")
            MetricTile(Icons.Rounded.Schedule, "Last tested", if (score.measuredAt() > 0) formatAgo(score.measuredAt()) else "Never")
        }
        Spacer(Modifier.height(8.dp))
        val (icon, color) = when {
            score.label() == QualityLabel.UNAVAILABLE -> Icons.Rounded.ErrorOutline to Red
            score.label() == QualityLabel.UNKNOWN -> Icons.Rounded.HelpOutline to Muted
            score.stale() || !score.tunnelVerified() || score.label() == QualityLabel.FAIR -> Icons.Rounded.WarningAmber to Amber
            score.label() == QualityLabel.POOR -> Icons.Rounded.ErrorOutline to Red
            else -> Icons.Rounded.CheckCircle to Cyan
        }
        Row(
            Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.07f), RowShape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(score.headline(), color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Text(score.explanation(), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                Text("Confidence ${score.confidencePercent()}% from ${plural(score.sampleCount(), "measurement")}." +
                    if (parts.recoveryPenalty() > 0) " Recent recovery: -${parts.recoveryPenalty()}." else "",
                    color = Muted.copy(.85f), fontSize = 9.5.sp)
            }
        }
    }
}

@Composable
private fun RowScope.MetricTile(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.weight(1f).widthIn(min = 60.dp).clip(RoundedCornerShape(13.dp)).background(NeutralOverlay.copy(.04f))
            .border(1.dp, NeutralOverlay.copy(.08f), RoundedCornerShape(13.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Violet2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, color = Muted, fontSize = 9.sp, maxLines = 1)
            Text(value, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A short line naming what quality data a decision used. */
internal fun qualitySummary(score: QualityScore?): String = when {
    score == null || score.label() == QualityLabel.UNKNOWN -> "Not measured yet"
    score.label() == QualityLabel.UNAVAILABLE -> "Unavailable"
    else -> "${score.label().text()} · ${score.score()}" + if (score.stale()) " (outdated)" else ""
}

