package com.jobscout.feature.settings.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jobscout.domain.FreehireCredentialSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CredentialUiState(val present: Boolean = false, val busy: Boolean = true,
    val message: String? = null, val revision: Int = 0)

class ApiCredentialsViewModel(private val store: FreehireCredentialSettings) : ViewModel() {
    private val mutable = MutableStateFlow(CredentialUiState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        try { mutable.value = CredentialUiState(present = store.hasStoredToken(), busy = false) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutable.value = CredentialUiState(busy = false, message = "Não foi possível ler o estado da credencial.") }
    } }
    fun save(value: String) = change { store.save(value); true }
    fun remove() = change { store.clear(); false }
    private fun change(block: suspend () -> Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { mutable.value = CredentialUiState(block(), false, "Credencial atualizada neste aparelho.", mutable.value.revision + 1) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false,
                message = "Não foi possível atualizar. Use somente a chave, sem prefixo Bearer, espaços ou quebras de linha.") }
        }
    }
}

@Composable
fun FreehireCredentialsCard(store: FreehireCredentialSettings) {
    val factory = remember(store) { viewModelFactory { initializer { ApiCredentialsViewModel(store) } } }
    val model: ApiCredentialsViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    // Deliberately not rememberSaveable: never put a credential in saved instance state.
    var input by remember { mutableStateOf("") }
    LaunchedEffect(state.revision) { input = "" }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sua chave Freehire", style = MaterialTheme.typography.titleMedium)
            Text(if (state.present) "Chave salva neste aparelho." else "Nenhuma chave cadastrada.")
            Text("Opcional. Use uma chave da sua conta e habilite a fonte na lista. Planos, limites e permissão dos dados dependem do Freehire.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(input, { input = it.take(2048) }, label = { Text("Chave pessoal") },
                modifier = Modifier.fillMaxWidth().testTag("freehire-key-input"), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                enabled = !state.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ model.save(input) }, enabled = input.isNotBlank() && !state.busy) { Text("Salvar chave") }
                TextButton({ input = ""; model.remove() }, enabled = state.present && !state.busy) { Text("Remover chave") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Criptografada com chave do Android Keystore. Remover aqui não revoga a chave na sua conta Freehire.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
