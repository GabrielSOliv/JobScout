package com.jobscout.feature.profile.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobscout.core.designsystem.SearchQuerySaver
import com.jobscout.feature.search.presentation.SearchFilterFields
import com.jobscout.core.designsystem.Field
import com.jobscout.core.designsystem.splitTerms
import com.jobscout.domain.*

private val areas = linkedMapOf(
    "Desenvolvimento" to "desenvolvedor, software engineer", "Dados e inteligência artificial" to "data analyst, data engineer",
    "Automação" to "automation developer, analista de automação", "Administração" to "assistente administrativo, analista administrativo",
    "Finanças e contabilidade" to "analista financeiro, accountant", "Design" to "designer", "Marketing" to "marketing",
    "Vendas" to "vendas, sales", "Atendimento" to "customer support, atendimento", "Recursos humanos" to "recursos humanos, recruiter",
    "Engenharia" to "engenheiro", "Educação" to "professor, education", "Saúde" to "enfermagem, health", "Logística" to "logistica, supply chain",
)

@Composable
fun OnboardingScreen(preferences: AppPreferences, submitting: Boolean = false, error: String? = null, complete: (String, List<String>, SearchQuery) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = step > 0) { step-- }
    var remote by rememberSaveable { mutableStateOf(Workplace.REMOTE in preferences.query.workplaces) }
    var selected by rememberSaveable { mutableStateOf(preferences.interests) }
    var name by rememberSaveable { mutableStateOf(preferences.displayName) }
    val titleState = rememberSaveable { mutableStateOf(preferences.query.titles.ifEmpty { preferences.profile.targets }.joinToString(", ")) }
    var titles by titleState
    val queryState = rememberSaveable(stateSaver = SearchQuerySaver) { mutableStateOf(preferences.query) }
    val locationState = rememberSaveable { mutableStateOf(preferences.query.locations.joinToString(", ")) }
    val keywordState = rememberSaveable { mutableStateOf(preferences.query.keywords.joinToString(", ")) }
    val excludedState = rememberSaveable { mutableStateOf(preferences.query.excludedKeywords.joinToString(", ")) }
    val excludedCompanyState = rememberSaveable { mutableStateOf(preferences.query.excludedCompanies.joinToString(", ")) }
    var custom by rememberSaveable { mutableStateOf("") }
    Scaffold(modifier = Modifier.testTag("screen-onboarding"), bottomBar = {
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) { Column(Modifier.navigationBarsPadding().padding(horizontal = 26.dp, vertical = 16.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Button(onClick = {
                if (step == 1) {
                    // Only what the person chose or wrote goes into Cargos; it stays visible and editable in the next step.
                    if (titles.isBlank()) titles = selected.filter { it in areas.keys }.mapNotNull(areas::get).joinToString(", ")
                    (selected.filter { it !in areas.keys } + listOfNotNull(custom.trim().take(40).takeIf(String::isNotBlank)))
                        .distinct().forEach { own ->
                            val current = splitTerms(titles)
                            if (current.none { it.equals(own, ignoreCase = true) }) titles = (current + own).joinToString(", ")
                        }
                    custom = ""
                }
                if (step < 2) {
                    if (step == 0) queryState.value = queryState.value.copy(workplaces = if (remote) setOf(Workplace.REMOTE) else emptySet())
                    step++
                } else {
                    complete(name, selected, queryState.value.copy(titles = splitTerms(titles), locations = splitTerms(locationState.value),
                        keywords = splitTerms(keywordState.value), excludedKeywords = splitTerms(excludedState.value), excludedCompanies = splitTerms(excludedCompanyState.value)))
                }
            }, enabled = !submitting && (step != 1 || selected.isNotEmpty() || titles.isNotBlank() || custom.isNotBlank()) && (step != 2 || titles.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(6.dp)) {
                Text(if (step == 2) "Começar varredura" else "Continuar")
            }
        } }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(if (step == 1) 10.dp else 18.dp)) {
            if (step > 0) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ step-- }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", Modifier.size(20.dp)) }
                    Text(if (step == 2) "Configure sua busca" else "Voltar", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${step + 1} de 3", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (step == 0) {
                item { Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.jobscout.core.designsystem.JobScoutEmblem(Modifier.size(126.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("Como você quer trabalhar?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Escolha o formato das oportunidades que você procura.", style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 18.dp, end = 18.dp))
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceCard("Todos os formatos", "Presencial, híbrido e remoto", !remote, { remote = false }, Modifier.weight(1f), false)
                    ChoiceCard("Quero remoto", "Oportunidades para o Brasil", remote, { remote = true }, Modifier.weight(1f), true)
                } }
            } else if (step == 1) {
                item { Text("Qual é a sua área de atuação?", style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("Selecione suas áreas de interesse (até 5) ou escreva uma que não estiver na lista.", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium) }
                items(areas.keys.toList()) { area ->
                    Surface(onClick = { selected = if (area in selected) selected - area else if (selected.size < 5) selected + area else selected },
                        border = BorderStroke(1.dp, if (area in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(area in selected, onCheckedChange = null)
                            Text(area, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(selected.filter { it !in areas.keys }, key = { "custom-$it" }) { own ->
                    Surface(onClick = { selected = selected - own },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(true, onCheckedChange = null)
                            Text(own, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Close, "Remover $own", Modifier.size(20.dp))
                        }
                    }
                }
                item {
                    val trimmed = custom.trim()
                    Field("Outra área de atuação", custom, { custom = it.take(40) }, Modifier.testTag("onboarding-custom-area"),
                        supporting = "Não está na lista? Escreva e toque em Incluir. O texto entra como cargo em Cargos (etapa 3).")
                    OutlinedButton({ if (selected.none { it.equals(trimmed, ignoreCase = true) } && selected.size < 5) selected = selected + trimmed; custom = "" },
                        enabled = trimmed.isNotBlank() && selected.size < 5, modifier = Modifier.testTag("onboarding-add-area")) { Text("Incluir") }
                }
            } else {
                item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(76.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                } }
                item { Field("Como quer ser chamado? (opcional)", name, { name = it.take(60) }) }
                item { Text("Todos os filtros", style = MaterialTheme.typography.titleLarge)
                    Text("Defina seus critérios antes da primeira varredura. Você pode alterá-los depois em Vagas.", style = MaterialTheme.typography.bodyMedium) }
                item { SearchFilterFields(queryState, titleState, locationState, keywordState, excludedState, excludedCompanyState, expandedInitially = true) }

                item { Text("O app encontra e lê páginas de vagas automaticamente. Perfil e resultados ficam neste aparelho.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, click: () -> Unit, modifier: Modifier, remote: Boolean) {
    Surface(click, modifier.height(202.dp), shape = RoundedCornerShape(7.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(shape = CircleShape, color = if (remote) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                Icon(if (remote) Icons.Outlined.Public else Icons.Outlined.WorkOutline, null, Modifier.padding(16.dp).size(24.dp),
                    tint = if (remote) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
