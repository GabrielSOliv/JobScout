package com.jobscout.feature.search.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jobscout.core.designsystem.Field
import com.jobscout.domain.GoogleSites

@Composable fun GoogleSitesScreen(sites: List<String>, save: (List<String>) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(sites) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("screen-google-sites"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Onde pesquisar", style = MaterialTheme.typography.headlineSmall)
            Text("Cadastre os sites adicionais que deseja pesquisar. O app pesquisa cada domínio no Google e traz os links reconhecidos para os cards.") }
        item { Text("Sempre incluídos", style = MaterialTheme.typography.titleMedium); Text("linkedin.com  ·  gupy.io") }
        item { Field("Domínio ou endereço HTTPS", input, { input = it.take(500); error = null }, Modifier.testTag("google-site-input"),
            supporting = "O endereço é convertido para o domínio. Ex.: empresa.com.br. Sem inurl na consulta.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton({
                val site = GoogleSites.normalize(input)
                error = when {
                    site == null -> "Informe um domínio público válido ou um endereço HTTPS."
                    site in listOf("linkedin.com", "gupy.io") || site in draft -> "Este site já está incluído."
                    else -> { draft = draft + site; input = ""; null }
                }
            }, Modifier.testTag("google-site-add"), enabled = input.isNotBlank()) { Text("Adicionar site") }
        }
        items(draft.size) { index -> val site = draft[index]
            Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(site, Modifier.weight(1f)); IconButton({ draft = draft - site }) { Icon(Icons.Outlined.DeleteOutline, "Remover $site") }
            } }
        }
        item { Text("Todos os cargos e sites cadastrados entram na fila, em grupos de consultas. Apenas a primeira página de cada consulta, com intervalo entre navegações. Mais critérios e sites podem tornar a busca mais longa; você pode pausar. Verificações do Google continuam como pendências para você resolver.", style = MaterialTheme.typography.bodySmall)
            Text("A captura aceita páginas individuais de vagas reconhecidas. Um site cadastrado pode não trazer resultados ou ter formatos ainda não suportados.", style = MaterialTheme.typography.bodySmall) }
        item { Button({ save(draft) }, Modifier.fillMaxWidth().testTag("google-sites-save")) { Text("Salvar sites") }
            Text("A lista vale para a próxima busca Google. Uma sessão em andamento mantém seus sites e resultados.", style = MaterialTheme.typography.bodySmall) }
    }
}
