package com.jobscout.feature.profile.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.jobscout.core.designsystem.*
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutUiState

private val ResumeSaver = Saver<ResumeDocument, String>(save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, ResumeDocument::class.java) })

@Composable fun ResumeBuilderScreen(state: ScoutUiState, importPdf: (String) -> Unit, save: (ResumeDocument) -> Unit,
    export: (ResumeDocument, String) -> Unit, discard: () -> Unit, delete: () -> Unit) {
    val initial = state.preferences.resumeDocument ?: ResumeDocument.fromProfile(state.preferences.profile, state.preferences.displayName)
    var loaded by rememberSaveable(stateSaver = ResumeSaver) { mutableStateOf(initial) }
    var doc by rememberSaveable(stateSaver = ResumeSaver) { mutableStateOf(initial) }
    LaunchedEffect(initial) { if (loaded != initial) { if (doc == loaded) doc = initial; loaded = initial } }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val creator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val snapshot = exportSnapshot; exportSnapshot = null
        if (uri != null && snapshot != null) export(Gson().fromJson(snapshot, ResumeDocument::class.java), uri.toString())
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importPdf(it.toString()) } }
    LazyColumn(Modifier.fillMaxSize().testTag("screen-profile"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Seu currículo", style = MaterialTheme.typography.headlineMedium)
            Text("Escreva sua trajetória e gere um PDF com layout clássico, pronto para enviar. Os dados ficam neste aparelho.") }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Documento em PDF", style = MaterialTheme.typography.titleMedium)
            Text("Nome centralizado, divisórias e texto selecionável. Novas páginas são criadas quando necessário.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({ error = doc.validationError(); if (error == null) { exportSnapshot = Gson().toJson(doc); creator.launch("Curriculo.pdf") } },
                Modifier.fillMaxWidth().testTag("resume-export"), enabled = !state.activity.exportingResume) {
                Text(if (state.activity.exportingResume) "Gerando PDF…" else "Gerar e salvar PDF")
            }
            OutlinedButton({ save(doc) }, Modifier.fillMaxWidth().testTag("resume-save"), enabled = !state.activity.exportingResume) { Text("Salvar rascunho") }
        } } }
        item { Text("Dados pessoais", style = MaterialTheme.typography.titleLarge)
            Field("Nome completo", doc.fullName, { doc = doc.copy(fullName = it.take(160)) }, Modifier.testTag("resume-name")) }
        item { Field("Cidade / estado", doc.city, { doc = doc.copy(city = it.take(200)) }) }
        item { Field("Telefone", doc.phone, { doc = doc.copy(phone = it.take(100)) }) }
        item { Field("E-mail", doc.email, { doc = doc.copy(email = it.take(200)) }) }
        item { Field("LinkedIn, portfólio e outros links", doc.links, { doc = doc.copy(links = it.take(2000)) }, singleLine = false, supporting = "Um endereço por linha.") }
        item { Text("Resumo ou objetivo", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Resumo profissional", "Objetivo").forEach { label -> FilterChip(doc.summaryTitle == label, { doc = doc.copy(summaryTitle = label) }, { Text(label) }) }
            }
            Field("Apresente sua trajetória ou objetivo", doc.summary, { doc = doc.copy(summary = it.take(4000)) }, singleLine = false, supporting = "Use informações reais. O app não inventa qualificações nem resultados.") }
        item { Text("Formação", style = MaterialTheme.typography.titleLarge) }
        items(doc.education.size) { index -> val item = doc.education[index]
            fun change(value: ResumeEducation) { doc = doc.copy(education = doc.education.toMutableList().also { it[index] = value }) }
            ResumeCard("Formação ${index + 1}", { doc = doc.copy(education = doc.education.filterIndexed { i, _ -> i != index }) }) {
                ResumeField("Curso / certificação", item.degree) { change(item.copy(degree = it)) }
                ResumeField("Instituição", item.institution) { change(item.copy(institution = it)) }
                ResumeField("Local", item.location) { change(item.copy(location = it)) }
                ResumeField("Período ou conclusão", item.period) { change(item.copy(period = it)) }
                ResumeField("Detalhes da formação", item.details, true) { change(item.copy(details = it)) }
            }
        }
        item { OutlinedButton({ doc = doc.copy(education = doc.education + ResumeEducation()) }, enabled = doc.education.size < 15) { Text("Adicionar formação") } }
        item { Text("Habilidades e idiomas", style = MaterialTheme.typography.titleLarge)
            Field("Habilidades confirmadas por você", doc.skills, { doc = doc.copy(skills = it.take(6000)) }, Modifier.testTag("profile-skills"), singleLine = false,
                supporting = "Separe por vírgula. Para agrupar no PDF, use uma categoria por linha: Linguagens: Java, Kotlin.") }
        item { Field("Idiomas e níveis", doc.languages, { doc = doc.copy(languages = it.take(2000)) }, singleLine = false) }
        item { Text("Experiência profissional", style = MaterialTheme.typography.titleLarge) }
        items(doc.experience.size) { index -> val item = doc.experience[index]
            fun change(value: ResumeExperience) { doc = doc.copy(experience = doc.experience.toMutableList().also { it[index] = value }) }
            ResumeCard("Experiência ${index + 1}", { doc = doc.copy(experience = doc.experience.filterIndexed { i, _ -> i != index }) }) {
                ResumeField("Cargo exercido", item.role) { change(item.copy(role = it)) }
                ResumeField("Empresa", item.company) { change(item.copy(company = it)) }
                ResumeField("Local", item.location) { change(item.copy(location = it)) }
                ResumeField("Período (ex.: Jan 2024 - Atual)", item.period) { change(item.copy(period = it)) }
                ResumeField("Atividades e resultados · um por linha", item.details, true) { change(item.copy(details = it)) }
            }
        }
        item { OutlinedButton({ doc = doc.copy(experience = doc.experience + ResumeExperience()) }, enabled = doc.experience.size < 15) { Text("Adicionar experiência") } }
        item { Text("Projetos", style = MaterialTheme.typography.titleLarge) }
        items(doc.projects.size) { index -> val item = doc.projects[index]
            fun change(value: ResumeProject) { doc = doc.copy(projects = doc.projects.toMutableList().also { it[index] = value }) }
            ResumeCard("Projeto ${index + 1}", { doc = doc.copy(projects = doc.projects.filterIndexed { i, _ -> i != index }) }) {
                ResumeField("Nome do projeto", item.name) { change(item.copy(name = it)) }
                ResumeField("Link ou tecnologias", item.link) { change(item.copy(link = it)) }
                ResumeField("O que você desenvolveu · um item por linha", item.details, true) { change(item.copy(details = it)) }
            }
        }
        item { OutlinedButton({ doc = doc.copy(projects = doc.projects + ResumeProject()) }, enabled = doc.projects.size < 15) { Text("Adicionar projeto") } }
        item { ResumeField("Informações adicionais: cursos, voluntariado…", doc.activities, true) { doc = doc.copy(activities = it) } }
        item { Button({ save(doc) }, Modifier.fillMaxWidth(), enabled = !state.activity.exportingResume) { Text("Salvar rascunho") }
            Text("Ao salvar, habilidades, formação e experiência preenchidas atualizam a comparação de vagas. Cargos desejados continuam nos filtros de busca.", style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider(); TextButton({ picker.launch(arrayOf("application/pdf")) }, enabled = !state.activity.importing) { Text(if (state.activity.importing) "Lendo no aparelho…" else "Aproveitar texto de um PDF") }
            Text("Extração opcional, até 10 MB / 40 páginas. Revise antes de incorporar. O PDF original não é guardado.", style = MaterialTheme.typography.bodySmall) }
        state.activity.draft?.let { draft -> item {
            draft.warnings.forEach { Notice(it) }
            OutlinedButton({ val imported = ResumeDocument.fromProfile(draft.profile)
                doc = doc.copy(skills = imported.skills, experience = imported.experience, education = imported.education, languages = imported.languages); discard()
            }) { Text("Copiar extração para editar") }
            TextButton(discard) { Text("Descartar extração") }
        } }
        item { TextButton({ confirmDelete = true }, enabled = !state.activity.exportingResume) { Text("Excluir currículo do aparelho") } }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Excluir currículo?") },
        text = { Text("Apaga o rascunho e os dados profissionais usados no app. PDFs já exportados permanecem no local escolhido.") },
        confirmButton = { TextButton({ confirmDelete = false; doc = ResumeDocument(); delete() }) { Text("Excluir") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}

@Composable private fun ResumeField(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit) {
    Field(label, value, { change(it.take(if (multiline) 8000 else 500)) }, singleLine = !multiline)
}
@Composable private fun ResumeCard(title: String, remove: () -> Unit, fields: @Composable ColumnScope.() -> Unit) {
    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(remove) { Icon(Icons.Outlined.DeleteOutline, "Remover $title") }
        }; fields()
    } }
}
