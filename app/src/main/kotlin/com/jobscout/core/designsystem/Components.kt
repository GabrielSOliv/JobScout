package com.jobscout.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobscout.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun PageHeading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
                      singleLine: Boolean = true, supporting: String? = null) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, supportingText = supporting?.let { { Text(it) } })
}
@Composable fun Notice(text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}
fun splitTerms(value: String): List<String> = value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun dateLabel(time: Long?): String = time?.let { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "Não informada"
fun workplaceLabel(value: Workplace): String = when (value) { Workplace.REMOTE -> "Remoto"; Workplace.HYBRID -> "Híbrido"; Workplace.ONSITE -> "Presencial"; Workplace.UNKNOWN -> "Modalidade não informada" }
fun seniorityLabel(value: Seniority): String = when (value) { Seniority.INTERN -> "Estágio"; Seniority.JUNIOR -> "Júnior"; Seniority.MID -> "Pleno"; Seniority.SENIOR -> "Sênior / liderança"; Seniority.UNKNOWN -> "Não informada" }
fun statusLabel(value: ApplicationStatus): String = when (value) {
    ApplicationStatus.NOT_APPLIED -> "Não candidatado"; ApplicationStatus.INTERESTED -> "Tenho interesse"; ApplicationStatus.APPLIED -> "Candidatado"
    ApplicationStatus.INTERVIEWING -> "Entrevista"; ApplicationStatus.OFFER -> "Proposta"; ApplicationStatus.REJECTED -> "Recusado"; ApplicationStatus.WITHDRAWN -> "Desisti"
}
