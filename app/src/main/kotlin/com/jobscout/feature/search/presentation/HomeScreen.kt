package com.jobscout.feature.search.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jobscout.domain.*
import com.jobscout.core.designsystem.OpportunityRadar
import com.jobscout.core.designsystem.JobScoutEmblem
import com.jobscout.core.designsystem.DiscoveryBadge
import com.jobscout.core.designsystem.ScoutColors
import com.jobscout.feature.jobs.presentation.JobCard

@Composable
fun HomeScreen(state: ScoutUiState, scan: () -> Unit, cancel: () -> Unit, explore: () -> Unit, web: () -> Unit,
               profile: () -> Unit, open: (String) -> Unit, bookmark: (TrackedJob) -> Unit, search: (String) -> Unit, editFilters: () -> Unit = explore,
               google: () -> Unit = web, startGoogle: () -> Unit = scan, captured: () -> Unit = explore) {
    var showActivity by remember { mutableStateOf(false) }
    var text by rememberSaveable(state.preferences.query.titles) { mutableStateOf(state.preferences.query.titles.joinToString(", ")) }
    var loadedText by rememberSaveable { mutableStateOf(state.preferences.query.titles.joinToString(", ")) }
    LaunchedEffect(state.preferences.query.titles) {
        val fresh = state.preferences.query.titles.joinToString(", ")
        if (fresh != loadedText) { text = fresh; loadedText = fresh }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val capturedPreview = state.googleCaptured.take(3)
    val capturedIds = capturedPreview.map { it.tracked.job.id }.toSet()
    val recommendations = remember(state.results, capturedIds) { state.results.filterNot { it.tracked.job.id in capturedIds } }
    val best = recommendations.take(1)
    val recent = remember(recommendations) { recommendations.sortedByDescending { it.tracked.job.postedAt ?: it.tracked.job.firstSeen }.filter { it.tracked.job.id != best.firstOrNull()?.tracked?.job?.id }.take(4) }
    LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(profile, modifier = Modifier.size(42.dp)) {
                    JobScoutEmblem(Modifier.size(36.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("JOBSCOUT / SEU RADAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(state.preferences.displayName.ifBlank { "Encontre seu próximo passo" }, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedIconButton({ showActivity = true }, modifier = Modifier.size(42.dp), shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    BadgedBox(badge = { if (state.activity.searching || state.preferences.reports.any { it.error != null }) Badge(modifier = Modifier.size(5.dp), containerColor = MaterialTheme.colorScheme.secondary) }) {
                        Icon(Icons.Outlined.NotificationsNone, "Atividade da varredura", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 13.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(text, { text = it }, Modifier.weight(1f).padding(horizontal = 9.dp).testTag("home-search"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(text); keyboard?.hide() }),
                        decorationBox = { inner -> Box { if (text.isEmpty()) Text("Buscar vagas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    IconButton(editFilters) { Icon(Icons.Outlined.FilterAlt, "Ajustar busca", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        item {
            Surface(color = ScoutColors.Petrol, shape = RoundedCornerShape(24.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 166.dp)) {
                    OpportunityRadar(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(112.dp))
                    Column(Modifier.padding(22.dp).fillMaxWidth(.67f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Seu próximo passo começa aqui.", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text("Vagas, critérios e escolhas no seu controle.", color = ScoutColors.OnPetrol, style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton({ if (state.activity.searching) showActivity = true else scan() },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = ScoutColors.Mint, contentColor = ScoutColors.OnMint)) {
                            Text(if (state.activity.waitingSeconds > 0) "Em ${state.activity.waitingSeconds}s" else if (state.activity.searching) "Coletando" else "Explorar")
                            Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.NorthEast, null, Modifier.size(17.dp))
                        }
                    }
                    if (state.activity.searching) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp), color = ScoutColors.Mint, trackColor = Color.Transparent)
                }
            }
        }
        item { GoogleSearchCard(state, google, startGoogle, captured) }
        if (capturedPreview.isNotEmpty()) {
            item {
                SectionHeading("Capturados no Google · ${state.googleCaptured.size}", captured)
                Text("Prévias capturadas pelo app. Confira os dados antes de se candidatar.", style = MaterialTheme.typography.bodySmall)
            }
            items(capturedPreview, key = { "captured-${it.tracked.job.id}" }) { item ->
                CapturedJobCard(item, state.results.any { it.tracked.job.id == item.tracked.job.id }, { open(item.tracked.job.id) }, bookmark,
                    RequiredKeywords.missing(item.tracked.job, state.preferences.query.keywords, state.preferences.synonyms))
            }
        }
        if (best.isNotEmpty() || capturedPreview.isEmpty()) item { SectionHeading("Recomendações", explore) }
        if (best.isEmpty() && capturedPreview.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.activity.searching) "Procurando a sua próxima oportunidade…" else "Seu radar está pronto", style = MaterialTheme.typography.titleSmall)
                Text(if (state.activity.searching) "Lendo páginas e comparando com seus critérios." else "Defina cargos e locais para encontrar opções que façam sentido para você.", style = MaterialTheme.typography.bodySmall)
                TextButton(editFilters) { Text("Ajustar minha busca") }
            }
        }
        items(best, key = { "best-${it.tracked.job.id}" }) { job -> JobCard(job, { open(job.tracked.job.id) }, { bookmark(job.tracked) }) }
        if (recent.isNotEmpty()) {
            item { SectionHeading("Vagas recentes", explore) }
            items(recent, key = { "recent-${it.tracked.job.id}" }) { job -> JobCard(job, { open(job.tracked.job.id) }, { bookmark(job.tracked) }) }
        }
    }
    if (showActivity) AlertDialog(onDismissRequest = { showActivity = false }, title = { Text("Atividade da busca") }, text = {
        ScanReports(state)
    }, confirmButton = { TextButton({ showActivity = false; web() }) { Text("Ver coleta web") } }, dismissButton = {
        TextButton({ if (state.activity.searching) cancel(); showActivity = false }) { Text(if (state.activity.searching) "Parar coleta" else "Fechar") }
    })
}

@Composable fun ScanReports(state: ScoutUiState, scrollable: Boolean = true) {
    val reports = if (state.activity.searching) state.activity.progress else state.preferences.reports
    if (!scrollable) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.activity.waitingSeconds > 0) Text("Nova coleta em ${state.activity.waitingSeconds}s. Seus filtros já foram aplicados.")
            if (reports.isEmpty()) Text(if (state.activity.searching) "Descobrindo páginas…" else "Sua próxima busca aparecerá aqui.")
            reports.forEach { ScanReportRow(state, it) }
        }
        return
    }
    LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.activity.waitingSeconds > 0) item { Text("Nova coleta em ${state.activity.waitingSeconds}s. Seus filtros já foram aplicados.") }
        if (reports.isEmpty()) item { Text(if (state.activity.searching) "Descobrindo páginas… Os resultados aparecem por fonte." else "Sua próxima busca aparecerá aqui.") }
        items(reports, key = { it.source }) { report ->
            ScanReportRow(state, report)
        }
    }
}

@Composable private fun ScanReportRow(state: ScoutUiState, report: SourceResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DiscoveryBadge(JobProvenance.sourceMethod(report.source), state.preferences.sources.firstOrNull { it.key == report.source }?.company ?: report.source)
        Text("${report.fetched} encontradas · ${report.matched} compatíveis", style = MaterialTheme.typography.bodySmall)
        if (report.pagesRead + report.cacheHits > 0) Text("${report.pagesRead} páginas lidas · ${report.detailsRead} detalhes · ${report.cacheHits} do cache", style = MaterialTheme.typography.bodySmall)
        report.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        report.coverage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (report.source.startsWith("dorks:")) report.queries.forEach {
            Text("Consulta executada: $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SectionHeading(title: String, all: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(all, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.height(30.dp)) { Text("Ver todas", style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(6.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
