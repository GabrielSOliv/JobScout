package com.jobscout.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jobscout.domain.*

@Composable fun DiscoveryBadge(method: DiscoveryMethod, source: String? = null) {
    val (background, foreground) = when (method) {
        DiscoveryMethod.HTML, DiscoveryMethod.RSS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        DiscoveryMethod.API -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DiscoveryMethod.DORKS -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = method.label + source?.substringBefore(" · ")?.take(28)?.let {
        if (it.startsWith("Freehire", ignoreCase = true)) " · via freehire.me" else " · $it"
    }.orEmpty()
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(4.dp),
        modifier = Modifier.testTag("method-${method.name.lowercase()}").semantics { contentDescription = "Método de descoberta: $label" }) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(when (method) { DiscoveryMethod.HTML -> Icons.Outlined.Code; DiscoveryMethod.API -> Icons.Outlined.CloudDownload
                DiscoveryMethod.RSS -> Icons.Outlined.RssFeed; DiscoveryMethod.DORKS -> Icons.Outlined.Search
                DiscoveryMethod.SHARED_LINK -> Icons.Outlined.Link; else -> Icons.Outlined.Info }, null, Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
