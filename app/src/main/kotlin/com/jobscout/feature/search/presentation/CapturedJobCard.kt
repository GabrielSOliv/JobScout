package com.jobscout.feature.search.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jobscout.domain.TrackedJob
import com.jobscout.feature.jobs.presentation.JobCard

@Composable
fun CapturedJobCard(item: ScoredJob, matchesFilters: Boolean, open: () -> Unit, bookmark: (TrackedJob) -> Unit,
                    missingKeywords: List<String> = emptyList()) {
    Column(Modifier.testTag("captured-${item.tracked.job.id}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (matchesFilters) "Capturada no Google · passa pelos filtros" else "Capturada no Google · fora dos filtros ou com dados a confirmar",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (missingKeywords.isNotEmpty()) Text(
            (if (item.tracked.job.descriptionComplete) "Termos obrigatórios não encontrados no conteúdo: " else "O resumo ainda não confirma os termos obrigatórios: ") +
                missingKeywords.joinToString(", "),
            modifier = Modifier.testTag("required-unconfirmed-${item.tracked.job.id}"), style = MaterialTheme.typography.bodySmall)
        JobCard(item, open, { bookmark(item.tracked) })
    }
}
