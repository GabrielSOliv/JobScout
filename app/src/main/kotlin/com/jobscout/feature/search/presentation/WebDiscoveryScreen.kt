package com.jobscout.feature.search.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jobscout.core.designsystem.*
import com.jobscout.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebDiscoveryScreen(state: ScoutUiState, import: (String) -> Unit, scan: () -> Unit, cancel: () -> Unit, filters: () -> Unit,
                       google: () -> Unit = {}, startGoogle: () -> Unit = scan, captured: () -> Unit = {}, sites: () -> Unit = {}) {
    var link by rememberSaveable { mutableStateOf("") }
    val terms = remember(state.preferences.query, state.preferences.profile) { DiscoveryPlanner.webTerms(state.preferences.query, state.preferences.profile) }
    LazyColumn(Modifier.testTag("screen-web"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Coleta pela internet", style = MaterialTheme.typography.headlineMedium)
            Text("O app encontra páginas, lê as vagas e compara com a sua busca.", style = MaterialTheme.typography.bodyMedium) }
        item { GoogleSearchCard(state, google, startGoogle, captured) }
        item { OutlinedButton(sites, Modifier.fillMaxWidth().testTag("manage-google-sites")) { Text("Sites para pesquisar (${2 + state.preferences.googleSites.orEmpty().size})") }
            Text("Adicione sites além de LinkedIn e Gupy para a coleta automática no Google.", style = MaterialTheme.typography.bodySmall) }
        item {
            Text("Métodos de descoberta", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DiscoveryMethod.DORKS, DiscoveryMethod.HTML, DiscoveryMethod.API, DiscoveryMethod.RSS).forEach { method ->
                    DiscoveryBadge(method)
                }
            }
            val enabled = state.preferences.sources.filter { it.kind == "dorks" && it.enabled }
            Text(if (enabled.isEmpty()) "Dorks · LinkedIn e Gupy na sessão Google acima. As fontes HTTP continuam em paralelo." else "LinkedIn e Gupy no Google do app. DuckDuckGo legado também foi ativado nas fontes.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).testTag("dorks-status"))
        }
        item {
            Text("Sua busca", style = MaterialTheme.typography.titleMedium)
            Text(terms.joinToString(" · ").ifBlank { "Defina seus cargos de interesse" })
            Text(Geography.targets(state.preferences.query.locations).joinToString(" · ").ifBlank { if (state.preferences.query.worldwide) "Todos os países" else "Todo o Brasil" }, style = MaterialTheme.typography.bodySmall)
            state.preferences.googleSession?.let { session -> session.tasks.forEachIndexed { index, task ->
                val status = when { task.id in session.taskCounts.orEmpty() -> "Lida"; index == session.taskIndex -> "Atual"
                    index < session.taskIndex && task.fallbackFor != null -> "Dispensada (já havia links)"
                    index < session.taskIndex -> "Lida"; else -> "Planejada" }
                Text("$status · ${if (task.strategy == DorkStrategy.EXPANDED) "ampliada" else if (task.role != null) "título" else "termos"}: ${task.query}", style = MaterialTheme.typography.bodySmall)
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(if (state.activity.searching) cancel else scan) { Text(if (state.activity.searching) "Parar coleta" else "Buscar agora") }
                TextButton(filters) { Text("Ajustar critérios") }
            }
            if (state.activity.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item { Text("Páginas e fontes consultadas", style = MaterialTheme.typography.titleMedium); ScanReports(state, scrollable = false) }
        item {
            HorizontalDivider(); Spacer(Modifier.height(16.dp)); Text("Já tem um link?", style = MaterialTheme.typography.titleMedium)
            Text("Opcional: adicione uma oportunidade encontrada em outro lugar.", style = MaterialTheme.typography.bodySmall)
            Field("Link original da vaga", link, { link = it.take(10_000) }, Modifier.testTag("web-link"))
            OutlinedButton({ import(link) }, enabled = link.isNotBlank()) { Text("Ler link") }
        }
    }
}

@Composable fun WebImportScreen(state: ScoutUiState, save: () -> Unit, discard: () -> Unit, back: () -> Unit) {
    LazyColumn(Modifier.testTag("screen-import"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Vaga compartilhada", style = MaterialTheme.typography.headlineSmall) }
        if (state.activity.importingWeb) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Lendo a publicação original…") }
        val job = state.activity.webDraft
        if (job != null) item {
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DiscoveryBadge(DiscoveryMethod.SHARED_LINK)
                Text(job.title, style = MaterialTheme.typography.titleMedium); Text(job.company); Text(job.location)
                Text(job.url, style = MaterialTheme.typography.bodySmall)
                Text(job.description.take(350), maxLines = 5, style = MaterialTheme.typography.bodySmall)
                Button(save, enabled = !state.activity.savingWeb) {
                    if (state.activity.savingWeb) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(if (state.activity.savingWeb) " Salvando…" else "Salvar e ver salvas")
                }
                TextButton({ discard(); back() }, enabled = !state.activity.savingWeb) { Text("Descartar leitura") }
            } }
        } else if (!state.activity.importingWeb && !state.activity.savingWeb) item {
            Text("Não há leitura disponível. Confira a mensagem ou tente outro link na tela de coleta.")
            TextButton(back) { Text("Voltar") }
        }
    }
}
