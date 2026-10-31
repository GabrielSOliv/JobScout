package com.jobscout.feature.settings.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jobscout.core.designsystem.*
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutUiState

@Composable
fun SettingsScreen(state: ScoutUiState,
                   save: (List<SourceConfig>, ScoreWeights, Boolean, Long, Int, Map<String, List<String>>, Boolean, Int, String, Boolean) -> Unit,
                   clearAll: () -> Unit, credentialSettings: FreehireCredentialSettings? = null) {
    val prefs = state.preferences
    var sources by remember(prefs.sources) { mutableStateOf(prefs.sources) }
    var sourceKind by remember { mutableStateOf("rss") }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var sourceValue by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var autoOpen by rememberSaveable(prefs.autoScanOnOpen) { mutableStateOf(prefs.autoScanOnOpen) }
    var googleEnabled by rememberSaveable(prefs.googleEnabled) { mutableStateOf(prefs.googleEnabled) }
    var cooldown by rememberSaveable(prefs.startupCooldownMinutes) { mutableStateOf(prefs.startupCooldownMinutes.toString()) }
    var name by rememberSaveable(prefs.displayName) { mutableStateOf(prefs.displayName) }
    var synonyms by rememberSaveable(prefs.synonyms) { mutableStateOf(prefs.synonyms.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" }) }
    var background by rememberSaveable(prefs.background) { mutableStateOf(prefs.background) }
    var interval by rememberSaveable(prefs.intervalHours) { mutableStateOf(prefs.intervalHours.toString()) }
    var threshold by rememberSaveable(prefs.notificationThreshold) { mutableFloatStateOf(prefs.notificationThreshold.toFloat()) }
    var weights by remember(prefs.weights) { mutableStateOf(prefs.weights) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun permissionGranted(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember { mutableStateOf(permissionGranted()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = permissionGranted() }
    LazyColumn(Modifier.testTag("screen-settings"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("AJUSTES DO RADAR", "Você no controle.", "Escolha as empresas, os critérios de compatibilidade e quando procurar novas oportunidades.") }
        item {
            Field("Como quer ser chamado?", name, { name = it.take(60) })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Varrer ao abrir o app", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(autoOpen, { autoOpen = it })
            }
            Field("Repetir ao abrir após quantos minutos?", cooldown, { cooldown = it.filter(Char::isDigit).take(4) }, supporting = "Usamos o cache entre varreduras para economizar rede e bateria. Atualização manual continua disponível.")
        }
        item {
            Text("Fontes de oportunidades", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Google · dorks no navegador do app", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(googleEnabled, { googleEnabled = it })
            }
            Text("Google funciona com o app aberto. CAPTCHA vira uma pendência para você resolver e confirmar. HTML, índices, feeds e quadros complementam os resultados.", style = MaterialTheme.typography.bodySmall)
            sources.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.company, style = MaterialTheme.typography.titleSmall)
                        Text(when (source.kind) { "dorks" -> "Dorks automáticos · DuckDuckGo · links individuais"; "web" -> "Coleta de páginas HTML"; "freehire" -> "Índice global · várias empresas e ATSs"; "rss" -> "Feed público RSS / Atom"; else -> "Quadro de empresa · ${source.kind}" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(source.enabled, { enabled -> sources = sources.map { if (it.key == source.key) it.copy(enabled = enabled) else it } })
                    IconButton({ sources = sources.filterNot { it.key == source.key } }) { Icon(Icons.Outlined.Close, "Remover ${source.company}") }
                }
            }
            OutlinedButton({ adding = !adding }) { Text(if (adding) "Fechar adição" else "Adicionar fonte") }
            if (adding) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("web" to "Página web", "rss" to "RSS", "greenhouse" to "Greenhouse", "lever" to "Lever").forEach { (kind, label) -> FilterChip(sourceKind == kind, { sourceKind = kind }, { Text(label) }) }
                }
                Field("Nome da fonte/empresa", sourceName, { sourceName = it })
                Field(if (sourceKind in setOf("rss", "web")) "Endereço HTTPS da fonte" else "Identificador do quadro", sourceValue, { sourceValue = it })
                TextButton({
                    runCatching {
                        require(sourceName.isNotBlank() && sourceValue.isNotBlank()) { "Informe nome e endereço/identificador." }
                        require(sources.size < 12) { "Use até 12 fontes." }
                        val endpoint = if (sourceKind in setOf("rss", "web")) JobLinks.canonical(sourceValue) ?: error("Use um endereço HTTPS público.") else null
                        val board = if (endpoint != null) JobLinks.stableId("feed", endpoint).take(12) else sourceValue.trim()
                        require(board.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Identificador inválido." }
                        val source = SourceConfig(sourceKind, board, sourceName.trim(), endpoint = endpoint)
                        require(sources.none { it.key == source.key || endpoint != null && it.endpoint == endpoint }) { "Essa fonte já foi adicionada." }
                        sources = sources + source; sourceName = ""; sourceValue = ""; adding = false; error = null
                    }.onFailure { error = it.message }
                }) { Text("Incluir na lista") }
            }
        }
        if (credentialSettings != null) item { FreehireCredentialsCard(credentialSettings) }
        item {
            Text("Pesos de compatibilidade", style = MaterialTheme.typography.titleLarge)
            Text("Os valores são normalizados para 100. Se todos forem zero, usamos 40/25/20/15. Campos ausentes não ganham pontos.", style = MaterialTheme.typography.bodySmall)
            WeightSlider("Habilidades", weights.skills) { weights = weights.copy(skills = it) }
            WeightSlider("Experiência (sobreposição textual)", weights.experience) { weights = weights.copy(experience = it) }
            WeightSlider("Objetivos", weights.targets) { weights = weights.copy(targets = it) }
            WeightSlider("Local e modalidade", weights.geography) { weights = weights.copy(geography = it) }
        }
        item {
            Text("Português ↔ inglês", style = MaterialTheme.typography.titleLarge)
            Field("Dicionário de sinônimos editável", synonyms, { synonyms = it }, singleLine = false,
                supporting = "Um grupo por linha: palavra=variação,outra variação. Aplicado em títulos, palavras, locais e objetivos; não cria habilidades comprovadas.")
        }
        item {
            Text("Consultas em segundo plano", style = MaterialTheme.typography.titleLarge)
            Row { Switch(background, { background = it }); Text(" Procurar automaticamente", Modifier.padding(top = 12.dp)) }
            Field("Intervalo aproximado em horas (3–168)", interval, { interval = it.filter(Char::isDigit).take(3) })
            Text("Android pode atrasar ou suspender consultas por bateria ou restrições do fabricante. Requer rede e bateria não baixa. A busca manual continua disponível. A primeira consulta automática espera o intervalo escolhido.", style = MaterialTheme.typography.bodySmall)
            Text("Notificar novas vagas a partir de ${threshold.toInt()}/100")
            Slider(threshold, { threshold = it }, valueRange = 0f..100f, steps = 19)
            Text(if (notifications) "Notificações permitidas." else "Notificações desativadas ou sem permissão.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33 && !notifications) OutlinedButton({ permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificações") }
        }
        if (error != null) item { Notice(error!!, true) }
        item {
            Button(onClick = {
                runCatching {
                    val hours = interval.toLongOrNull()
                    require(hours != null && hours in 3..168) { "Use um intervalo de 3 a 168 horas." }
                    val cooldownMinutes = cooldown.toIntOrNull()
                    require(cooldownMinutes != null && cooldownMinutes in 1..1440) { "Use intervalo ao abrir entre 1 e 1440 minutos." }
                    val dictionary = synonyms.lines().filter { it.isNotBlank() }.associate { line ->
                        val parts = line.split('=', limit = 2)
                        require(parts.size == 2 && parts[0].isNotBlank() && splitTerms(parts[1]).isNotEmpty()) { "Confira o dicionário: palavra=variação,outra variação." }
                        parts[0].trim() to splitTerms(parts[1])
                    }
                    require(dictionary.size <= 200) { "Use até 200 grupos de sinônimos." }
                    error = null
                    save(sources, weights, background, hours, threshold.toInt(), dictionary, autoOpen, cooldownMinutes, name, googleEnabled)
                }.onFailure { error = it.message }
            }, modifier = Modifier.fillMaxWidth()) { Text("Salvar configurações") }
        }
        item {
            HorizontalDivider()
            Text("Privacidade", style = MaterialTheme.typography.titleLarge)
            OpenSourceLicenses()
            Text("Sites recebem os termos da pesquisa. Dorks usam Google; o navegador mantém cookies e dados de sites somente neste app. Currículo bruto e histórico não são enviados. Apagar dados também remove a sessão do navegador. Resumos podem estar desatualizados; perfil e acompanhamento ficam no aparelho, sem telemetria ou backend próprio.", style = MaterialTheme.typography.bodySmall)
            TextButton({ confirmDelete = true }, enabled = !state.activity.searching && !state.activity.importing) { Text("Apagar todos os dados locais") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Apagar dados do JobScout?") },
        text = { Text("Vagas, favoritos, anotações, perfil e preferências serão apagados. As consultas automáticas serão desativadas. Esta ação não pode ser desfeita.") },
        confirmButton = { TextButton({ confirmDelete = false; clearAll() }) { Text("Apagar tudo") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}
@Composable private fun WeightSlider(label: String, value: Int, change: (Int) -> Unit) {
    Text("$label · $value", style = MaterialTheme.typography.bodyMedium)
    Slider(value.toFloat(), { change(it.toInt()) }, valueRange = 0f..100f, steps = 19)
}
