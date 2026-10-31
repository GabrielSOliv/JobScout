package com.jobscout.feature.search.presentation

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.SystemClock
import android.view.View
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jobscout.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only DOM observation in this WebView; no JS bridge, reload or external requests. */
private suspend fun WebView.googleDom(includeHtml: Boolean): JSONObject = suspendCancellableCoroutine { continuation ->
    val script = """(function(){
        var links=Array.from(document.querySelectorAll('a[href]')).filter(function(a){return a.querySelector('h3,[role="heading"][aria-level="3"],.CVA68e');}).slice(0,100);
        var text=(document.body ? document.body.innerText : '').slice(0,30000).toLowerCase();
        return JSON.stringify({url:location.href,complete:document.readyState==='complete',headings:links.length,
            signature:links.map(function(a){return a.href+' '+a.innerText;}).join('|'),
            needsUser:location.pathname.indexOf('/sorry')===0 || location.pathname.indexOf('/recaptcha/')===0 || location.hostname.indexOf('consent.google.')===0 ||
                !!document.querySelector('#captcha-form,form[action*="/sorry"],input[name="captcha"]') ||
                text.indexOf('unusual traffic')>=0 || text.indexOf('tráfego incomum')>=0 || text.indexOf('before you continue to google')>=0 || text.indexOf('antes de continuar para o google')>=0,
            html:${if (includeHtml) "document.documentElement.outerHTML.slice(0,2000000)" else "null"}});
    })()"""
    evaluateJavascript(script) { value ->
        if (continuation.isActive) runCatching { JSONObject(JSONTokener(value).nextValue() as String) }
            .onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
    }
}

@Composable
fun GoogleSearchCard(state: ScoutUiState, open: () -> Unit, start: () -> Unit, captured: () -> Unit = open) {
    if (!state.preferences.googleEnabled) return
    val session = state.preferences.googleSession
    OutlinedCard(Modifier.fillMaxWidth().testTag("google-status")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Dorks · Google", style = MaterialTheme.typography.titleSmall)
            Text(when (session?.phase) {
                GooglePhase.RUNNING -> when {
                    session.tasks.any { it.board.startsWith("site:") } -> "Buscando LinkedIn, Gupy e seus sites cadastrados"
                    else -> "Buscando LinkedIn e Gupy"
                }
                GooglePhase.NEEDS_USER -> if (session.emptyPagePending) "Google informou busca sem resultados" else "Verificação pendente no Google"
                GooglePhase.PAUSED -> "Pesquisa Google pausada"
                GooglePhase.COMPLETE -> "${session.foundIds.size} links encontrados no Google"
                GooglePhase.ERROR -> "A pesquisa precisa de atenção"
                null -> "Pesquise vagas no Google sem sair do app"
            }, style = MaterialTheme.typography.bodyMedium)
            session?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            session?.let { Text("${it.query.titles.size} cargos · ${it.tasks.count { task -> task.strategy == DorkStrategy.TITLE && task.board != "careers" }} consultas principais por grupos e sites",
                style = MaterialTheme.typography.bodySmall) }
            if (session?.phase == GooglePhase.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(if (session == null) start else if (session.phase == GooglePhase.COMPLETE) captured else open, Modifier.testTag("google-open")) {
                Text(when (session?.phase) {
                    GooglePhase.NEEDS_USER -> if (session.emptyPagePending) "Conferir busca vazia" else "Resolver verificação"
                    GooglePhase.PAUSED, GooglePhase.ERROR -> "Abrir pesquisa e retomar"
                    GooglePhase.COMPLETE -> "Ver capturados nos cards"
                    GooglePhase.RUNNING -> "Acompanhar no navegador"
                    null -> "Iniciar busca Google"
                })
            }
            if (session != null && session.foundIds.isNotEmpty() && session.phase != GooglePhase.COMPLETE) {
                TextButton(captured, Modifier.testTag("google-captured")) { Text("Ver capturados (${session.foundIds.size})") }
            }
            if (session?.phase == GooglePhase.COMPLETE) {
                TextButton(open, Modifier.testTag("google-browser")) { Text("Abrir navegador Google") }
            }
        }
    }
}

/** A single browser stays mounted behind the native UI. A challenge never navigates the app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleBrowserRuntime(state: ScoutUiState, visible: Boolean, viewModel: ScoutViewModel,
                         showCaptured: () -> Unit,
                         pageLoader: ((WebView, String) -> Unit)? = null) {
    val session = state.preferences.googleSession ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(state)
    val shown by rememberUpdatedState(visible)
    var live by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var loading by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var displayedUrl by remember { mutableStateOf<String?>(null) }
    var inspectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectionSerial by remember { mutableLongStateOf(0) }
    var pendingConfirmation by remember(session.id, session.requestId) { mutableStateOf(false) }
    var interceptedTarget by remember { mutableStateOf<String?>(null) }
    val browser = remember(context) { runCatching { WebView(context) }.getOrNull() }

    fun inspect(confirmed: Boolean) {
        val web = browser ?: return
        val current = latest.preferences.googleSession ?: return
        if (!live || inspectJob?.isActive == true || (confirmed && (!shown || loading))) return
        interceptedTarget?.let { target ->
            if (confirmed) { pendingConfirmation = false; viewModel.googleResolvedTarget(target, current.id, current.requestId, true) }
            return
        }
        inspecting = true
        val serial = ++inspectionSerial
        inspectJob = scope.launch {
            try {
                withTimeout(20_000) {
                val started = SystemClock.elapsedRealtime()
                var previous = ""
                var stableSince = started
                // Wait for a quiet result DOM, or at most 12s for an empty/unrecognized page.
                // This observes one load; it never retries Google or interacts with a challenge.
                while (isActive && live) {
                    delay(500)
                    val checkpoint = latest.preferences.googleSession ?: return@withTimeout
                    if (current.id != checkpoint.id || current.requestId != checkpoint.requestId || interceptedTarget != null) return@withTimeout
                    val signal = web.googleDom(false)
                    val now = SystemClock.elapsedRealtime()
                    val signature = signal.getString("url") + signal.getString("signature")
                    if (signature != previous) { previous = signature; stableSince = now }
                    val ready = signal.optBoolean("needsUser") || now - started >= 12_000 ||
                        (signal.optBoolean("complete") && signal.optInt("headings") > 0 && now - stableSince >= 1_000)
                    if (!ready) continue
                    val payload = web.googleDom(true)
                    if (confirmed) pendingConfirmation = false
                    viewModel.googleSnapshot(payload.getString("html"), payload.getString("url"), current.id, current.requestId, confirmed)
                    return@withTimeout
                }
                }
            } catch (_: TimeoutCancellationException) {
                pendingConfirmation = false
                viewModel.googleFailed("A leitura da página demorou. Confira a página e tente confirmar novamente, pular ou finalizar.", current.id, current.requestId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pendingConfirmation = false; viewModel.googleFailed("Não foi possível ler a página. Confira o navegador e confirme novamente.", current.id, current.requestId) }
            finally { if (serial == inspectionSerial) inspecting = false }
        }
    }

    DisposableEffect(browser, owner) {
        browser?.apply {
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean { result.cancel(); return true }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    if (GoogleDorksPlanner.allowedNavigation(url)) return false
                    val current = latest.preferences.googleSession
                    if (current != null && loadedKey == "${current.id}:${current.requestId}" && current.pendingLinks.isNotEmpty()) {
                        inspectJob?.cancel(); loading = false; interceptedTarget = url
                        if (current.phase == GooglePhase.RUNNING) viewModel.googleResolvedTarget(url, current.id, current.requestId, false)
                        return true
                    }
                    // The browser is a collection tool. External listings open only from
                    // an explicit action on a native job card, never from browser callbacks.
                    if (request.url.host in setOf("www.google.com", "google.com", "www.google.com.br", "google.com.br")) {
                        current?.let { viewModel.googleFailed("O Google tentou uma navegação interna não reconhecida (${request.url.path}). Confira a página; isso não confirma uma busca vazia.", it.id, it.requestId) }
                    }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && GoogleDorksPlanner.allowedNavigation(url)) displayedUrl = url
                    if (url != null && url != "about:blank" && !GoogleDorksPlanner.allowedNavigation(url)) {
                        view.stopLoading(); loading = false; inspectJob?.cancel()
                        val current = latest.preferences.googleSession
                        if (current != null && loadedKey == "${current.id}:${current.requestId}" && current.pendingLinks.isNotEmpty()) {
                            interceptedTarget = url
                            if (current.phase == GooglePhase.RUNNING) viewModel.googleResolvedTarget(url, current.id, current.requestId, false)
                        }
                        return
                    }
                    loading = true; inspectJob?.cancel()
                    if (latest.preferences.googleSession?.phase == GooglePhase.NEEDS_USER) interceptedTarget = null
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null && GoogleDorksPlanner.allowedNavigation(url)) displayedUrl = url
                    loading = false
                    val current = latest.preferences.googleSession ?: return
                    if (interceptedTarget == null && loadedKey == "${current.id}:${current.requestId}" && view.url == url) {
                        if (pendingConfirmation && current.phase == GooglePhase.NEEDS_USER) inspect(true)
                        else if (current.phase == GooglePhase.RUNNING) inspect(false)
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    latest.preferences.googleSession?.let { viewModel.googleFailed("O navegador recusou o certificado da conexão.", it.id, it.requestId) }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    loading = false
                    latest.preferences.googleSession?.let { viewModel.googleFailed("Falha de rede no navegador. Abra para conferir e tentar novamente.", it.id, it.requestId) }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400)
                        latest.preferences.googleSession?.let { viewModel.googleFailed(
                            if (response.statusCode in setOf(400, 414)) "O Google recusou a consulta (HTTP ${response.statusCode}), possivelmente pelo tamanho ou sintaxe. Nenhum filtro foi removido pelo app. Confira ou divida os critérios."
                            else "O Google restringiu ou não concluiu a consulta (HTTP ${response.statusCode}). Confira a página; a coleta está pausada.", it.id, it.requestId) }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { live = true; browser?.onResume() }
            if (event == Lifecycle.Event.ON_STOP) {
                live = false; pendingConfirmation = false; inspectJob?.cancel(); browser?.stopLoading(); browser?.onPause(); viewModel.pauseGoogle(background = true)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer); inspectJob?.cancel()
            browser?.stopLoading(); browser?.clearCache(true); browser?.clearHistory(); browser?.destroy()
        }
    }
    LaunchedEffect(browser) {
        if (browser == null) viewModel.googleFailed("Android System WebView indisponível. Atualize o componente e reabra a pesquisa.", session.id, session.requestId)
    }
    LaunchedEffect(session.id, session.requestId, loading, live) {
        if (!loading || !live) return@LaunchedEffect
        delay(45_000)
        browser?.stopLoading(); loading = false
        viewModel.googleFailed("A página demorou para carregar. Abra a pesquisa para conferir ou recarregar.", session.id, session.requestId)
    }
    LaunchedEffect(session.id, session.requestId, session.phase, live, visible) {
        if (browser == null || !live) return@LaunchedEffect
        val key = "${session.id}:${session.requestId}"
        val canLoad = latest.preferences.googleEnabled && session.phase == GooglePhase.RUNNING || (visible && loadedKey == null)
        if (!canLoad || loadedKey == key) return@LaunchedEffect
        if (session.tasks.getOrNull(session.taskIndex)?.board == "careers") { viewModel.skipGoogle(); return@LaunchedEffect }
        delay((session.navigationReadyAt - System.currentTimeMillis()).coerceAtLeast(0))
        val url = session.currentUrl?.let { GoogleDorksPlanner.scopedUrl(it, session.tasks.getOrNull(session.taskIndex)?.board.orEmpty()) } ?: return@LaunchedEffect
        if (!GoogleDorksPlanner.allowedNavigation(url)) {
            viewModel.googleFailed("Endereço fora da navegação Google permitida.", session.id, session.requestId); return@LaunchedEffect
        }
        inspectJob?.cancel(); browser.stopLoading(); interceptedTarget = null; loadedKey = key; loading = true; displayedUrl = url
        if (pageLoader == null) browser.loadUrl(url) else pageLoader(browser, url)
    }
    // Counter changes only after a deliberate tap. Loading or reopening never confirms a CAPTCHA.
    var handledConfirmation by remember { mutableLongStateOf(state.activity.googleInspectRequest) }
    LaunchedEffect(state.activity.googleInspectRequest) {
        if (state.activity.googleInspectRequest != handledConfirmation) {
            handledConfirmation = state.activity.googleInspectRequest; pendingConfirmation = true
        }
    }
    LaunchedEffect(pendingConfirmation, loading, inspecting, live, visible) {
        if (pendingConfirmation && !loading && !inspecting && live && visible) inspect(true)
    }
    Column(Modifier.fillMaxSize().zIndex(if (visible) 1f else -1f).alpha(if (visible) 1f else 0f)
        .testTag(if (visible) "screen-google" else "google-background")) {
        if (visible) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Google · página1 por consulta · ${session.foundIds.size} links", style = MaterialTheme.typography.titleSmall)
                val actualQuery = displayedUrl?.let(GoogleDorksPlanner::queryText)
                Text(actualQuery?.let { "Página exibida: $it" } ?: "Aguardando a primeira página", maxLines = 2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("google-displayed-query"))
                if (session.phase == GooglePhase.RUNNING && loadedKey != "${session.id}:${session.requestId}")
                    Text("Próxima consulta após o intervalo: ${session.tasks.getOrNull(session.taskIndex)?.query.orEmpty()}", maxLines = 2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("google-next-query"))
                Text(session.message.orEmpty(), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                interceptedTarget?.takeIf { session.phase == GooglePhase.NEEDS_USER }?.let {
                    Text("Destino identificado após a verificação: $it", maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(showCaptured, Modifier.testTag("google-links")) { Text("Ver cards no app (${session.foundIds.size})") }
                    when (session.phase) {
                        GooglePhase.RUNNING -> TextButton({ viewModel.pauseGoogle() }) { Text("Pausar") }
                        GooglePhase.PAUSED, GooglePhase.ERROR -> Button(viewModel::resumeGoogle, Modifier.testTag("google-resume")) { Text("Retomar") }
                        GooglePhase.COMPLETE -> TextButton({ viewModel.startGoogle() }) { Text("Nova busca") }
                        GooglePhase.NEEDS_USER -> Unit
                    }
                }
                if (session.phase == GooglePhase.NEEDS_USER) {
                    Button(viewModel::confirmGoogle, Modifier.fillMaxWidth().testTag("google-confirm"), enabled = !loading && !inspecting && !pendingConfirmation) {
                        Text(if (inspecting || pendingConfirmation) "Conferindo a página…" else if (session.emptyPagePending) "Conferir página e continuar" else "Já resolvi · continuar coleta")
                    }
                    Text(if (session.emptyPagePending) "Se os links apareceram, o app confere e captura. Se o Google continuar informando busca vazia, a fila segue para a próxima consulta."
                        else "Resolva a verificação abaixo. O botão confere a página; se o desafio continuar, a coleta permanece pausada.", style = MaterialTheme.typography.bodySmall)
                    TextButton(viewModel::reloadGoogle) { Text("Restaurar consulta") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(viewModel::skipGoogle, Modifier.testTag("google-skip"), enabled = session.phase != GooglePhase.COMPLETE) { Text("Pular consulta") }
                    TextButton({ viewModel.finishGoogle(); showCaptured() }, Modifier.testTag("google-finish")) { Text("Finalizar coleta") }
                }
                if (loading || inspecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (browser != null) AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize(), update = { web ->
                // Keep Chromium rendering while hidden behind the native UI (alpha/z-index).
                // INVISIBLE can suspend visual/animation work needed by dynamic search pages.
                web.visibility = View.VISIBLE
                web.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            })
        }
    }
}
