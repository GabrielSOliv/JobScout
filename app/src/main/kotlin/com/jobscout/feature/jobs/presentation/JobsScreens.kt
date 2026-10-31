package com.jobscout.feature.jobs.presentation

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobscout.core.designsystem.*
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.*
import java.util.Locale

fun shareLinks(context: Context, jobs: List<Job>) {
    val text = jobs.take(150).mapNotNull { job -> JobLinks.canonical(job.url)?.let { "${job.title} — ${job.company}\n$it" } }.joinToString("\n\n")
    if (text.isBlank()) return
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Compartilhar links das vagas"))
}
fun openListing(context: Context, raw: String) {
    val url = JobLinks.canonical(raw) ?: return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Nenhum navegador disponível. Copie o link.", Toast.LENGTH_LONG).show() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobCard(item: ScoredJob, open: () -> Unit, bookmark: () -> Unit) {
    val job = item.tracked.job
    val context = LocalContext.current
    Card(onClick = open, modifier = Modifier.fillMaxWidth().testTag("job-${job.id}"), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(job.company.split(' ', '-', '&').filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase(),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (job.logoUrl != null) coil.compose.AsyncImage(coil.request.ImageRequest.Builder(context).data(job.logoUrl)
                            .setHeader("Accept", "image/avif,image/webp,image/*").size(120).build(), null, Modifier.fillMaxSize().padding(4.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(job.company, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(bookmark, modifier = Modifier.size(30.dp)) { Icon(if (item.tracked.saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder,
                    if (item.tracked.saved) "Remover dos favoritos" else "Salvar vaga", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(job.location.ifBlank { "Local não informado" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (job.salaryMin == null && job.salaryMax == null) "Salário não informado" else salaryLabel(job),
                    style = if (job.salaryMin == null && job.salaryMax == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    color = if (job.salaryMin == null && job.salaryMax == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                job.employment?.takeIf(String::isNotBlank)?.let { JobTag(it.replace('_', ' ').take(20)) }
                if (job.workplace != Workplace.UNKNOWN) JobTag(workplaceLabel(job.workplace))
                Spacer(Modifier.weight(1f))
                OutlinedButton(open, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(3.dp), modifier = Modifier.height(29.dp).testTag("details-${job.id}"), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Text("Detalhes", style = MaterialTheme.typography.labelSmall); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JobProvenance.origins(job).take(2).forEach { DiscoveryBadge(it.method, it.sourceName) }
                if (JobProvenance.origins(job).size > 2) Text("+${JobProvenance.origins(job).size - 2}", style = MaterialTheme.typography.labelSmall)
            }
            VerificationBadge(job.verification)
            Text((job.postedAt?.let { "Publicada em ${dateLabel(it).substringBefore(' ')}" } ?: "Data de publicação não informada") +
                " · Encontrada em ${dateLabel(job.firstSeen).substringBefore(' ')}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ openListing(context, job.url) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(25.dp)) {
                Text(job.url.toUri().host.orEmpty(), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(5.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Abrir publicação original", Modifier.size(12.dp))
            }
            if (item.tracked.stale) Text("Confira a disponibilidade na publicação original", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable private fun JobTag(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedScreen(state: ScoutUiState, open: (String) -> Unit, bookmark: (TrackedJob) -> Unit, dismiss: (TrackedJob) -> Unit) {
    var showDismissed by rememberSaveable { mutableStateOf(false) }
    var selectedStatus by remember { mutableStateOf<ApplicationStatus?>(null) }
    val saved = state.all.filter { (if (showDismissed) it.tracked.dismissed else it.tracked.saved || it.tracked.status != ApplicationStatus.NOT_APPLIED) &&
        (selectedStatus == null || it.tracked.status == selectedStatus) }
    val context = LocalContext.current
    LazyColumn(Modifier.testTag("screen-saved"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("SUA JORNADA", "Vagas que importam.", "Guarde oportunidades e acompanhe cada candidatura no seu aparelho.") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selectedStatus == null, { selectedStatus = null }, { Text("Todas") })
            ApplicationStatus.entries.forEach { status -> FilterChip(selectedStatus == status, { selectedStatus = status }, { Text(statusLabel(status)) }) }
        } }
        item { Row { Switch(showDismissed, { showDismissed = it }); Text(" Ver descartadas", Modifier.padding(top = 12.dp)) } }
        if (saved.isEmpty()) item { Notice("Nenhuma vaga nesta seleção. Salve uma oportunidade no radar ou registre uma candidatura.") }
        else item { OutlinedButton({ shareLinks(context, saved.map { it.tracked.job }) }) { Text("Compartilhar links desta seleção (até 150)") } }
        items(saved, key = { it.tracked.job.id }) { item ->
            JobCard(item, { open(item.tracked.job.id) }, { bookmark(item.tracked) })
            if (showDismissed) TextButton({ dismiss(item.tracked) }) { Text("Restaurar no radar") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobDetailsScreen(item: ScoredJob, back: () -> Unit, bookmark: () -> Unit,
                     track: (ApplicationStatus, String) -> Unit, dismiss: () -> Unit,
                     verify: () -> Unit = {}, verifying: Boolean = false) {
    val job = item.tracked.job
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var notes by rememberSaveable(job.id, item.tracked.notes) { mutableStateOf(item.tracked.notes) }
    var status by remember(job.id, item.tracked.status) { mutableStateOf(item.tracked.status) }
    LazyColumn(Modifier.testTag("screen-details-${job.id}"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text(" Voltar") } }
        item { PageHeading(job.company, job.title, "${job.location.ifBlank { "Local não informado" }} · ${workplaceLabel(job.workplace)}") }
        item {
            Text("Como encontramos esta vaga", style = MaterialTheme.typography.titleMedium)
            JobProvenance.origins(job).forEach { origin ->
                Spacer(Modifier.height(8.dp)); DiscoveryBadge(origin.method, origin.sourceName)
                Text("Encontrada: ${dateLabel(origin.firstFound)} · Última leitura: ${dateLabel(origin.lastFound)}", style = MaterialTheme.typography.bodySmall)
                origin.query?.let { Text("Consulta: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Text("O que foi confirmado", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp)); VerificationBadge(job.verification)
            Text("Última checagem: ${dateLabel(job.verification.checkedAt)}", style = MaterialTheme.typography.bodySmall)
            job.verification.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            job.verification.validThrough?.let { Text("Prazo publicado pela fonte: ${dateLabel(it)}", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(verify, enabled = !verifying, modifier = Modifier.testTag("verify-${job.id}")) {
                if (verifying) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (verifying) "Verificando página…" else "Verificar página original")
            }
            Text("A leitura confirma o conteúdo disponível naquela página. Não valida a identidade da empresa nem garante que a vaga esteja aberta ou seja livre de golpe.",
                style = MaterialTheme.typography.bodySmall)
        }
        item { Button({ openListing(context, job.url) }, Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Text(" Abrir vaga original") } }
        item {
            SelectionContainer { Text(job.url, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Link da vaga", job.url))) } }) { Text("Copiar link") }
                OutlinedButton(bookmark) { Text(if (item.tracked.saved) "Remover salva" else "Salvar vaga") }
                OutlinedButton({ shareLinks(context, listOf(job)) }) { Text("Compartilhar") }
            }
        }
        if (item.tracked.stale) item { Notice("Esta vaga não apareceu na última coleta completa do quadro. Pode ter encerrado; seu histórico foi preservado.") }
        item {
            Text("Compatibilidade · ${item.score.total}/100", style = MaterialTheme.typography.titleLarge)
            Text("Comparação determinística com seu perfil. Não indica chance de contratação, nem garante que você cumpra os requisitos.", style = MaterialTheme.typography.bodySmall)
        }
        items(item.score.parts) { part -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${part.name} · ${String.format(Locale.ROOT, "%.1f", part.earned)} de ${String.format(Locale.ROOT, "%.1f", part.weight)}", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { if (part.weight > 0) (part.earned / part.weight).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
            Text(part.explanation, style = MaterialTheme.typography.bodySmall)
        } }
        item { Notice("No texto: ${item.score.matched.joinToString().ifBlank { "nenhuma habilidade do perfil identificada" }}.\nNão encontradas: ${item.score.missing.joinToString().ifBlank { "nenhuma ou perfil sem habilidades" }}. Ausência no texto não prova ausência na vaga.") }
        item {
            Text("Dados publicados", style = MaterialTheme.typography.titleLarge)
            Text("Fonte: ${job.source}\nPublicada em: ${dateLabel(job.postedAt)}\nEncontrada em: ${dateLabel(job.firstSeen)}\nÚltima coleta: ${dateLabel(job.lastSeen)}\nContrato: ${job.employment ?: "Não informado"}\nSenioridade pelo título: ${seniorityLabel(job.seniority)}\nSalário: ${salaryLabel(job)}", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text("Descrição", style = MaterialTheme.typography.titleLarge)
            if (!job.descriptionComplete) Notice("Esta fonte fornece um resumo. Consulte a publicação original para requisitos e descrição completos.")
            SelectionContainer { Text(job.description.ifBlank { "Descrição não fornecida pela fonte. Consulte a publicação original." }) }
        }
        item {
            Text("Minha candidatura", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ApplicationStatus.entries.forEach { value -> FilterChip(status == value, { status = value }, { Text(statusLabel(value)) }) }
            }
            Field("Anotações pessoais", notes, { notes = it.take(10_000) }, singleLine = false)
            Text("Última atualização: ${dateLabel(item.tracked.statusAt)}", style = MaterialTheme.typography.bodySmall)
            Button({ track(status, notes) }) { Text("Salvar acompanhamento") }
            TextButton(dismiss) { Text(if (item.tracked.dismissed) "Restaurar no radar" else "Descartar do radar") }
        }
    }
}
private fun salaryLabel(job: Job): String = if (job.salaryMin == null && job.salaryMax == null) "Não divulgado" else
    "${job.currency ?: "Moeda desconhecida"} ${job.salaryMin ?: "?"} – ${job.salaryMax ?: "?"} / ${when (job.salaryPeriod) { "month" -> "mês"; "year" -> "ano"; "hour" -> "hora"; else -> "período desconhecido" }}"
