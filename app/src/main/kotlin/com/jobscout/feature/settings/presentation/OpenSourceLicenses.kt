package com.jobscout.feature.settings.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun OpenSourceLicenses() {
    val assets = LocalContext.current.assets
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(emptyList<String>()) }
    var content by remember { mutableStateOf("") }
    LaunchedEffect(show, selected) {
        if (show) withContext(Dispatchers.IO) {
            if (selected == null) files = assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted()
            else content = assets.open("licenses/$selected").bufferedReader().use { it.readText() }
        }
    }
    OutlinedButton({ selected = null; show = true }) { Text("Licenças de código aberto") }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Licenças e créditos") },
        text = {
            Column {
                if (selected != null) TextButton({ selected = null; content = "" }) { Text("Voltar à lista") }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (selected == null) items(files) { file -> TextButton({ content = ""; selected = file }) { Text(file) } }
                    else items(content.split("\n\n")) { paragraph -> Text(paragraph, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)) }
                }
            }
        }, confirmButton = { TextButton({ show = false }) { Text("Fechar") } })
}
