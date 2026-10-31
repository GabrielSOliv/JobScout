package com.jobscout.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jobscout.domain.*

@Composable
fun VerificationBadge(verification: JobVerification, modifier: Modifier = Modifier) {
    val state = if (verification.state == VerificationState.PAGE_READ && verification.validThrough?.let { it < System.currentTimeMillis() } == true)
        VerificationState.EXPIRED else verification.state
    val label = when (state) {
        VerificationState.UNVERIFIED -> "Prévia · não verificada"
        VerificationState.PAGE_READ -> "Página original lida"
        VerificationState.UNAVAILABLE -> "Página indisponível"
        VerificationState.RESTRICTED -> "Leitura restrita"
        VerificationState.NOT_A_JOB -> "Publicação não confirmada"
        VerificationState.EXPIRED -> "Prazo informado encerrado"
    }
    val (background, foreground) = when (state) {
        VerificationState.PAGE_READ -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        VerificationState.UNAVAILABLE, VerificationState.EXPIRED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        VerificationState.RESTRICTED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(4.dp),
        modifier = modifier.testTag("verification-${state.name.lowercase()}").semantics { contentDescription = "Verificação da publicação: $label" }) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (state == VerificationState.PAGE_READ) Icons.Outlined.Article else if (state in setOf(VerificationState.UNAVAILABLE, VerificationState.EXPIRED))
                Icons.Outlined.LinkOff else Icons.Outlined.Info, null, Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
