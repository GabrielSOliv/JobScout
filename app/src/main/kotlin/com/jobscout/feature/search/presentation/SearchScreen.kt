package com.jobscout.feature.search.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobscout.core.designsystem.*
import com.jobscout.domain.*
import com.jobscout.feature.jobs.presentation.JobCard
import com.jobscout.feature.jobs.presentation.shareLinks
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: ScoutUiState, search: (SearchQuery) -> Unit, savePreset: (String, SearchQuery) -> Unit,
                 removePreset: (String) -> Unit, open: (String) -> Unit, bookmark: (TrackedJob) -> Unit, cancel: () -> Unit,
                 google: () -> Unit = {}, startGoogle: () -> Unit = {}, captured: () -> Unit = {}, sites: () -> Unit = {}, settings: () -> Unit = {}, clearUnsaved: () -> Unit = {}) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val unsavedCount = state.all.count { !it.tracked.saved }
    if (showClearDialog) AlertDialog(onDismissRequest = { showClearDialog = false },
        title = { Text("Limpar vagas não salvas?") },
        text = { Text("Remove $unsavedCount vagas sem favorito, inclusive suas anotações e histórico de candidatura. Favoritos, currículo e filtros serão preservados. As coletas atuais serão encerradas.") },
        confirmButton = { TextButton({ showClearDialog = false; clearUnsaved() }, Modifier.testTag("confirm-clear-unsaved")) { Text("Limpar não salvas") } },
        dismissButton = { TextButton({ showClearDialog = false }) { Text("Cancelar") } })
    var showCaptured by rememberSaveable { mutableStateOf(false) }
    var handledCapture by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.activity.capturedResultsRequest) {
        if (state.activity.capturedResultsRequest > handledCapture) { showCaptured = true; handledCapture = state.activity.capturedResultsRequest }
    }
    var loadedQuery by rememberSaveable(stateSaver = SearchQuerySaver) { mutableStateOf(state.preferences.query) }
    val queryState = rememberSaveable(stateSaver = SearchQuerySaver) { mutableStateOf(state.preferences.query) }
    var query by queryState
    // Keep editable text as text; normalizing a controlled field on every keystroke can drop IME edits.
    val titleState = rememberSaveable { mutableStateOf(state.preferences.query.titles.joinToString(", ")) }
    var titleText by titleState
    val locationState = rememberSaveable { mutableStateOf(state.preferences.query.locations.joinToString(", ")) }
    var locationText by locationState
    val keywordState = rememberSaveable { mutableStateOf(state.preferences.query.keywords.joinToString(", ")) }
    var keywordText by keywordState
    val excludedState = rememberSaveable { mutableStateOf(state.preferences.query.excludedKeywords.joinToString(", ")) }
    var excludedText by excludedState
    val excludedCompanyState = rememberSaveable { mutableStateOf(state.preferences.query.excludedCompanies.joinToString(", ")) }
    var excludedCompanyText by excludedCompanyState
    LaunchedEffect(state.preferences.query) {
        val fresh = state.preferences.query
        if (loadedQuery != fresh) {
            query = fresh; titleText = fresh.titles.joinToString(", "); locationText = fresh.locations.joinToString(", ")
            keywordText = fresh.keywords.joinToString(", "); excludedText = fresh.excludedKeywords.joinToString(", ")
            excludedCompanyText = fresh.excludedCompanies.joinToString(", "); loadedQuery = fresh
        }
    }
    fun currentQuery() = query.copy(titles = splitTerms(titleText), locations = splitTerms(locationText),
        keywords = splitTerms(keywordText), excludedKeywords = splitTerms(excludedText), excludedCompanies = splitTerms(excludedCompanyText))
    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(state.preferences.lastRun == null) }
    var handledEdit by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.activity.editSearchRequest) {
        if (state.activity.editSearchRequest > 0 && state.activity.editSearchRequest != handledEdit) { showCaptured = false; showFilters = true; handledEdit = state.activity.editSearchRequest }
    }
    var presetName by rememberSaveable { mutableStateOf("") }
    var sortByDate by rememberSaveable { mutableStateOf(false) }
    var showReports by rememberSaveable { mutableStateOf(false) }
    var platform by rememberSaveable { mutableStateOf("all") }
    val context = LocalContext.current
    val selections = remember(state.results) { mapOf(
        "all" to state.results,
        "linkedin" to state.results.filter { DorksLinks.original(it.tracked.job.url, "linkedin") != null },
        "gupy" to state.results.filter { DorksLinks.original(it.tracked.job.url, "gupy") != null },
        "dorks" to state.results.filter { JobProvenance.origins(it.tracked.job).any { origin -> origin.method == DiscoveryMethod.DORKS } }) }
    val dateBasis = state.preferences.query.dateBasis
    val items = remember(selections, sortByDate, platform, dateBasis) { val selected = selections[platform].orEmpty()
        if (sortByDate) selected.sortedByDescending { if (dateBasis == DateBasis.DISCOVERY) it.tracked.job.firstSeen else it.tracked.job.postedAt ?: 0L } else selected }
    LazyColumn(Modifier.fillMaxSize().testTag("screen-explore"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Encontre sua próxima vaga", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(settings, Modifier.testTag("search-settings")) { Icon(Icons.Outlined.Settings, "Configurações de vagas") }
        }
            Text("${state.results.size} oportunidades para sua busca", style = MaterialTheme.typography.bodySmall)
            TextButton({ showClearDialog = true }, Modifier.testTag("search-clear-unsaved"), enabled = unsavedCount > 0 && !state.activity.clearingResults) {
                Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (state.activity.clearingResults) "Limpando vagas…" else "Limpar não salvas ($unsavedCount)")
            }
        }
        item { GoogleSearchCard(state, google, startGoogle, { showCaptured = true; captured() }) }
        item { TextButton(sites, Modifier.testTag("search-google-sites")) { Text("Sites para pesquisar no Google") } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showCaptured, { showCaptured = false }, { Text("Compatíveis (${state.results.size})") }, modifier = Modifier.testTag("results-matches"))
                FilterChip(showCaptured, { showCaptured = true }, { Text("Capturados (${state.googleCaptured.size})") }, modifier = Modifier.testTag("results-captured"))
            }
        }
        if (showCaptured) {
            item {
                Text("Capturados no Google", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("captured-heading"))
                Text("Histórico de links capturados. A coleta atual lê só a página1 e não abre o portal. Alguns links não passam pelos filtros ou têm dados a confirmar; continuam como prévias.", style = MaterialTheme.typography.bodySmall)
                if (state.googleCaptured.isEmpty()) Text("Os links capturados aparecerão aqui durante a coleta.", style = MaterialTheme.typography.bodySmall)
            }
            items(state.googleCaptured, key = { "captured-${it.tracked.job.id}" }) { item ->
                CapturedJobCard(item, state.results.any { it.tracked.job.id == item.tracked.job.id }, { open(item.tracked.job.id) }, bookmark,
                    RequiredKeywords.missing(item.tracked.job, state.preferences.query.keywords, state.preferences.synonyms))
            }
        } else {
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sua busca", style = MaterialTheme.typography.titleMedium)
                Text(if (query.worldwide) "Mercado: Mundo" else "Mercado: Brasil", style = MaterialTheme.typography.labelMedium)
                if (!showFilters) {
                    Text(splitTerms(titleText).joinToString(" · ").ifBlank { "Todos os cargos dos quadros ativos" })
                    Text(splitTerms(locationText).joinToString(" · ").ifBlank { if (query.worldwide) "Todos os países" else "Todo o Brasil" }, style = MaterialTheme.typography.bodySmall)
                    TextButton({ showFilters = true }) { Icon(Icons.Outlined.Tune, null); Text(" Editar filtros") }
                }
                if (showFilters) {
                val suggestions = ProfileQueryPlanner.suggestedTitles(state.preferences.profile)
                if (suggestions.isNotEmpty()) {
                    TextButton({ showSuggestions = !showSuggestions }) { Text(if (showSuggestions) "Ocultar sugestões do perfil" else "Usar sugestões do perfil") }
                }
                if (suggestions.isNotEmpty() && showSuggestions) {
                    Text("Sugestões a partir de objetivos e habilidades revisadas. Toque para incluir um cargo; não comprovam experiência.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { title -> FilterChip(title in splitTerms(titleText), {
                            val selected = splitTerms(titleText)
                            titleText = (if (title in selected) selected - title else selected + title).joinToString(", ")
                        }, { Text(title) }) }
                    }
                }
                SearchFilterFields(queryState, titleState, locationState, keywordState, excludedState, excludedCompanyState) {
                    Field("Nome desta busca", presetName, { presetName = it })
                    OutlinedButton(onClick = { savePreset(presetName, currentQuery()) }, enabled = presetName.isNotBlank()) { Text("Salvar filtros") }
                }
                }
                Button(onClick = { showFilters = false; search(currentQuery()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (state.activity.searching) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                    else { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.activity.searching) "Aplicar filtros e buscar" else "Encontrar vagas")
                }
                if (state.activity.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.activity.waitingSeconds > 0) "Nova coleta em ${state.activity.waitingSeconds}s. Seus filtros já foram aplicados."
                        else "Buscando nas fontes. Você pode mudar os filtros ou continuar navegando.", style = MaterialTheme.typography.bodySmall)
                    TextButton(cancel) { Text("Parar busca") }
                }
            } }
        }
        if (state.preferences.presets.isNotEmpty()) item {
            Text("BUSCAS SALVAS", style = MaterialTheme.typography.labelMedium)
            state.preferences.presets.forEach { preset -> Row {
                TextButton(onClick = {
                    query = preset.query
                    titleText = preset.query.titles.joinToString(", ")
                    locationText = preset.query.locations.joinToString(", ")
                    keywordText = preset.query.keywords.joinToString(", ")
                    excludedText = preset.query.excludedKeywords.joinToString(", ")
                    excludedCompanyText = preset.query.excludedCompanies.joinToString(", ")
                    showFilters = true
                }) { Text(preset.name) }
                IconButton(onClick = { removePreset(preset.name) }) { Icon(Icons.Outlined.Close, "Excluir busca ${preset.name}") }
            } }
        }
        item { Text("Última consulta: ${dateLabel(state.preferences.lastRun)}", style = MaterialTheme.typography.bodySmall) }
        item { TextButton({ showReports = !showReports }) { Text(if (state.activity.searching) "Coletando páginas… · Ver atividade" else "Ver atividade da busca") }
            if (showReports) ScanReports(state, scrollable = false) }
        item {
            Text("Seu radar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("A pontuação mede sobreposição de critérios; não é probabilidade de contratação.", style = MaterialTheme.typography.bodySmall)
            if (state.preferences.query.postedWithinDays != null) Text(
                if (dateBasis == DateBasis.DISCOVERY) "Período aplicado à primeira descoberta no app."
                else if (state.preferences.query.includeUnknownDates) "Google recebe o período para descoberta. Prévias sem data publicada continuam incluídas e sem data confirmada."
                else "O período exige data de publicação informada.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Todas", "linkedin" to "LinkedIn", "gupy" to "Gupy", "dorks" to "Dorks").forEach { (key, label) ->
                    FilterChip(platform == key, { platform = key }, { Text("$label · ${selections[key].orEmpty().size}") }, modifier = Modifier.testTag("filter-$key"))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!sortByDate, { sortByDate = false }, { Text("Compatibilidade") })
                FilterChip(sortByDate, { sortByDate = true }, { Text(if (dateBasis == DateBasis.DISCOVERY) "Últimas encontradas" else "Mais recentes") })
                if (items.isNotEmpty()) AssistChip({ shareLinks(context, items.map { it.tracked.job }) }, { Text("Compartilhar até 150 links") }, leadingIcon = { Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)) })
            }
        }
        if (items.isEmpty()) item { Notice(if (state.preferences.lastRun == null) "Seu radar está pronto. Configure os cargos e consulte as empresas ativas." else "Nenhuma vaga compatível nesta busca. Ajuste os filtros ou adicione quadros de empresas em Ajustes.") }
        items(items, key = { it.tracked.job.id }) { item -> JobCard(item, { open(item.tracked.job.id) }, { bookmark(item.tracked) }) }
        item { Text("Dorks • coleta HTML • feeds públicos • links originais", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun FilterGroupHeading(title: String, summary: String, expanded: Boolean, toggle: () -> Unit, tag: String) {
    HorizontalDivider()
    TextButton(toggle, Modifier.fillMaxWidth().testTag(tag), contentPadding = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Recolher $title" else "Expandir $title")
    }
}

@Composable private fun FilterCheckbox(label: String, checked: Boolean, change: (Boolean) -> Unit, tag: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, change, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun Metric(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}


/** Shared by first-run setup and the vacancies tab; text stays editable until the user applies it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SearchFilterFields(queryState: MutableState<SearchQuery>, titleState: MutableState<String>,
    locationState: MutableState<String>, keywordState: MutableState<String>, excludedState: MutableState<String>,
    excludedCompanyState: MutableState<String>, expandedInitially: Boolean = false, extra: @Composable () -> Unit = {}) {
    var query by queryState
    var titleText by titleState
    var locationText by locationState
    var keywordText by keywordState
    var excludedText by excludedState
    var excludedCompanyText by excludedCompanyState
    var advanced by rememberSaveable { mutableStateOf(expandedInitially) }
    var dateFilters by rememberSaveable { mutableStateOf(true) }
    var profileFilters by rememberSaveable { mutableStateOf(expandedInitially) }
    var qualityFilters by rememberSaveable { mutableStateOf(expandedInitially) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Cargos (qualquer um)", titleText, { titleText = it }, Modifier.testTag("search-titles"), supporting = "Separe cargos por vírgula. Vazio busca todos os cargos das fontes HTML/API/RSS; a pesquisa Google precisa de pelo menos um cargo.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(query.worldwide, { checked ->
                        query = query.copy(worldwide = checked)
                        if (checked) locationText = splitTerms(locationText).filterNot(BrazilMarket::isBrazilTerm).joinToString(", ")
                    }, modifier = Modifier.testTag("search-worldwide"))
                    Text("Buscar no mundo", style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (query.worldwide) "Todos os países estão liberados. Para uma busca global, deixe Locais vazio; cidades preenchidas continuam valendo."
                    else if (query.includeUnknownLocations) "Brasil e links com local a confirmar. País estrangeiro explícito continua fora."
                    else "Somente vagas com indicação de local no Brasil. Remotas estrangeiras ou sem local confirmado ficam de fora.", style = MaterialTheme.typography.bodySmall)
                Field("Locais (qualquer um)", locationText, { locationText = it }, Modifier.testTag("search-locations"), supporting = if (query.worldwide) "Vazio = todos os países. Ex.: Lisboa, Portugal." else "Vazio = todo o Brasil. Ex.: São Paulo, Campinas. País estrangeiro exige Buscar no mundo.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Workplace.REMOTE, Workplace.HYBRID, Workplace.ONSITE).forEach { mode ->
                        FilterChip(mode in query.workplaces, { query = query.copy(workplaces = if (mode in query.workplaces) query.workplaces - mode else query.workplaces + mode) }, { Text(workplaceLabel(mode)) })
                    }
                }
                Row { Checkbox(query.remoteAnywhere, { query = query.copy(remoteAnywhere = it) }, Modifier.testTag("filter-remote-anywhere")); Text("Aceitar remoto fora das cidades listadas", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                if (query.remoteAnywhere) Text(if (query.worldwide) "Remoto pode ter restrições de país. Confira a descrição antes de se candidatar." else "Esta opção amplia as cidades, mantendo a exigência de Brasil.", style = MaterialTheme.typography.bodySmall)

                FilterGroupHeading("Datas", if (query.postedWithinDays == null) "Qualquer data" else "Últimos ${query.postedWithinDays} dias · ${if (query.dateBasis == DateBasis.PUBLICATION) "publicação" else "encontrada no app"}",
                    dateFilters, { dateFilters = !dateFilters }, "filters-dates")
                if (dateFilters) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "Qualquer data", 1 to "24h", 3 to "3 dias", 7 to "7 dias", 14 to "14 dias", 30 to "30 dias").forEach { (days, label) ->
                            FilterChip(query.postedWithinDays == days, { query = query.copy(postedWithinDays = days) }, { Text(label) }, modifier = Modifier.testTag("date-${days ?: "any"}"))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(query.dateBasis == DateBasis.PUBLICATION, { query = query.copy(dateBasis = DateBasis.PUBLICATION) }, { Text("Publicada pela empresa") }, modifier = Modifier.testTag("date-publication"))
                        FilterChip(query.dateBasis == DateBasis.DISCOVERY, { query = query.copy(dateBasis = DateBasis.DISCOVERY) }, { Text("Encontrada pelo app") }, modifier = Modifier.testTag("date-discovery"))
                    }
                    if (query.dateBasis == DateBasis.PUBLICATION) {
                        if (query.postedWithinDays != null) FilterCheckbox("Incluir vagas sem data de publicação", query.includeUnknownDates,
                            { query = query.copy(includeUnknownDates = it) }, "date-unknown")
                        Text("A data precisa vir da publicação. Data do índice do Google ou da leitura da página não substitui a data da vaga.", style = MaterialTheme.typography.bodySmall)
                        if (query.postedWithinDays != null) Text("No Google, o período restringe a atualização das páginas no índice. Incluir datas desconhecidas controla os cards locais, sem retirar esse período da consulta.", style = MaterialTheme.typography.bodySmall)
                    } else Text("Usa a primeira vez em que o app encontrou o link. Uma nova leitura não torna uma vaga antiga recém-encontrada.", style = MaterialTheme.typography.bodySmall)
                }

                FilterGroupHeading("Perfil da vaga", "Contrato e nível de experiência", profileFilters, { profileFilters = !profileFilters }, "filters-profile")
                if (profileFilters) {
                    Text("Contrato publicado", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "Qualquer", "CLT" to "CLT", "PJ" to "PJ", "Estágio" to "Estágio", "Aprendiz" to "Aprendiz", "Temporário" to "Temporário").forEach { (contract, label) ->
                            FilterChip(normalized(query.employment) == normalized(contract), { query = query.copy(employment = contract) }, { Text(label) })
                        }
                    }
                    Text("Só contratos explicitamente informados passam. Full-time não confirma CLT ou PJ.", style = MaterialTheme.typography.bodySmall)
                    Text("Senioridade pelo título", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Seniority.entries.filterNot { it == Seniority.UNKNOWN }.forEach { level -> FilterChip(level in query.seniorities,
                            { query = query.copy(seniorities = if (level in query.seniorities) query.seniorities - level else query.seniorities + level) }, { Text(seniorityLabel(level)) }) }
                    }
                    Text("Sem seleção aceita todos os níveis. A estimativa usa somente o título; não comprova requisitos ou experiência.", style = MaterialTheme.typography.bodySmall)
                }

                FilterGroupHeading("Qualidade e acompanhamento", "O que confirmar e o que ocultar", qualityFilters, { qualityFilters = !qualityFilters }, "filters-quality")
                if (qualityFilters) {
                    FilterCheckbox("Somente página original lida", query.onlyVerifiedPages, { query = query.copy(onlyVerifiedPages = it) }, "filter-verified")
                    Text("Leitura da publicação confirma o conteúdo disponível, não garante ausência de golpe nem contratação.", style = MaterialTheme.typography.bodySmall)
                    FilterCheckbox("Excluir banco de talentos / cadastro reserva", query.excludeTalentPools, { query = query.copy(excludeTalentPools = it) }, "filter-talent-pools")
                    FilterCheckbox("Mostrar links com local a confirmar", query.includeUnknownLocations, { query = query.copy(includeUnknownLocations = it) }, "filter-unknown-location")
                    Text("Falta de local não significa Brasil. País estrangeiro explícito continua excluído quando Mundo está desmarcado.", style = MaterialTheme.typography.bodySmall)
                    FilterCheckbox("Ocultar vagas já vistas", query.hideViewed, { query = query.copy(hideViewed = it) }, "filter-hide-viewed")
                    FilterCheckbox("Ocultar candidaturas já iniciadas", query.hideApplied, { query = query.copy(hideApplied = it) }, "filter-hide-applied")
                    Text("Métodos de descoberta", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(query.methods.isEmpty(), { query = query.copy(methods = emptySet()) }, { Text("Todos") })
                        listOf(DiscoveryMethod.DORKS, DiscoveryMethod.HTML, DiscoveryMethod.API, DiscoveryMethod.RSS, DiscoveryMethod.SHARED_LINK).forEach { method ->
                            FilterChip(method in query.methods, { query = query.copy(methods = if (method in query.methods) query.methods - method else query.methods + method) }, { Text(method.label) })
                        }
                    }
                }

                FilterGroupHeading("Mais critérios", "Palavras, empresas e salário divulgado", advanced, { advanced = !advanced }, "filters-additional")
                if (advanced) {
                    Field("Palavras obrigatórias (todas)", keywordText, { keywordText = it }, Modifier.testTag("search-required-keywords"),
                        supporting = "Separe por vírgula: inglês, SQL. Todos os termos devem constar no título, descrição ou competências. Inglês também procura English; não precisa estar no título.")
                    if (keywordText.isNotBlank()) Text("Uma prévia Google pode omitir requisitos. Links sem confirmação desses termos ficam em Capturados, com o motivo, até haver conteúdo suficiente.", style = MaterialTheme.typography.bodySmall)
                    Field("Excluir palavras", excludedText, { excludedText = it })
                    Field("Empresa contém", query.company, { query = query.copy(company = it) })
                    Field("Excluir empresas", excludedCompanyText, { excludedCompanyText = it })
                    Field("Contrato publicado contém (opcional)", query.employment, { query = query.copy(employment = it) }, supporting = "Use para um termo diferente dos botões de contrato. Vagas sem esse dado não passam se preenchido.")
                    Field("Salário mínimo divulgado", query.salaryMin?.toLong()?.toString().orEmpty(), { value ->
                        if (value.isEmpty() || value.toLongOrNull()?.let { it >= 0 } == true) query = query.copy(salaryMin = value.toDoubleOrNull())
                    })
                    Field("Moeda do salário", query.salaryCurrency, { query = query.copy(salaryCurrency = it.uppercase().take(3)) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("month" to "Mês", "year" to "Ano", "hour" to "Hora").forEach { (key, label) -> FilterChip(query.salaryPeriod == key, { query = query.copy(salaryPeriod = key) }, { Text(label) }) }
                    }
                    Text("Sem conversão cambial. Salário mínimo exige dados divulgados com moeda e período compatíveis e é verificado no app. Histórico de vistas/candidaturas, página verificada e primeira descoberta também são critérios locais, sem operador Google equivalente. Raio não disponível: as fontes não fornecem coordenadas.", style = MaterialTheme.typography.bodySmall)
                    extra()
                }
    }
}
