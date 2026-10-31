package com.jobscout.feature.search.presentation

import com.jobscout.domain.*
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short checkpoint operations only: a human wait never holds a network/repository mutex. */
internal class GoogleSearchCoordinator(private val repository: ScoutRepository, private val scope: CoroutineScope) {
    private val processing = Mutex()
    private val controls = Mutex()
    private var pageJob: kotlinx.coroutines.Job? = null
    private var generation = 0L

    suspend fun prepare(query: SearchQuery, force: Boolean = false) = controls.withLock {
        val prefs = repository.preferences.first()
        if (!prefs.googleEnabled) return@withLock
        val old = prefs.googleSession
        if (!force && old?.query == query && old.phase in setOf(GooglePhase.RUNNING, GooglePhase.NEEDS_USER)) return@withLock
        generation++
        pageJob?.cancelAndJoin()
        val tasks = GoogleDorksPlanner.plan(query, prefs.profile, dictionary = prefs.synonyms, sites = prefs.googleSites.orEmpty())
        if (tasks.isEmpty()) {
            val reason = if (query.methods.isNotEmpty() && DiscoveryMethod.DORKS !in query.methods) "Inclua Dorks nos métodos de descoberta para pesquisar no Google." else "Informe um cargo nos filtros para iniciar a pesquisa Google."
            repository.updatePreferences { it.copy(googleSession = it.googleSession?.copy(phase = GooglePhase.PAUSED, message = reason)) }
            require(!force) { reason }
            return@withLock
        }
        val session = GoogleSession(UUID.randomUUID().toString(), query, tasks, currentUrl = tasks.first().url,
            message = "Consultando o Google no app. Se houver verificação, você poderá resolver quando quiser.")
        repository.updatePreferences { it.copy(query = query, googleSession = session) }
    }

    fun snapshot(html: String, url: String, sessionId: String, requestId: Long, confirmed: Boolean) {
        val previous = pageJob
        if (previous?.isActive == true && !confirmed) return
        val stamp = generation
        pageJob = scope.launch {
            if (confirmed) previous?.cancelAndJoin()
            processing.withLock {
                val session = repository.preferences.first().googleSession ?: return@withLock
                if (stamp != generation || session.id != sessionId || session.requestId != requestId) return@withLock
                if (session.phase != GooglePhase.RUNNING && !(confirmed && session.phase == GooglePhase.NEEDS_USER)) return@withLock
                val task = session.tasks.getOrNull(session.taskIndex) ?: return@withLock
                try {
                    if (task.board == "careers") {
                        commit(session, finishPage(session).copy(pagesRead = session.pagesRead), emptyList())
                        return@withLock
                    }
                    val page = repository.inspectGooglePage(html.take(2_000_000), url, task, session.query)
                    if (stamp != generation) return@withLock
                    when (page.kind) {
                        GooglePageKind.CAPTCHA, GooglePageKind.CONSENT, GooglePageKind.UNKNOWN -> update(session.id, session.requestId) {
                            it.copy(phase = GooglePhase.NEEDS_USER, currentUrl = url, emptyPagePending = false,
                                message = page.message ?: "Verificação necessária. Abra o Google, conclua a página e confirme para continuar.", updatedAt = System.currentTimeMillis())
                        }
                        GooglePageKind.RESULTS, GooglePageKind.EMPTY -> {
                            if (page.kind == GooglePageKind.EMPTY && !confirmed) {
                                update(session.id, session.requestId) { it.copy(phase = GooglePhase.NEEDS_USER,
                                    currentUrl = url, emptyPagePending = true,
                                    message = "O próprio Google informou busca sem resultados. Confira a página; você pode recarregar ou conferir novamente antes de seguir.",
                                    updatedAt = System.currentTimeMillis()) }
                                return@withLock
                            }
                            // Old checkpoints may point at page2. Never import it after this update.
                            val laterPage = runCatching { URI(url).rawQuery?.split('&')?.any {
                                it.substringBefore('=') == "start" && (it.substringAfter('=').toIntOrNull() ?: 0) > 0
                            } == true }.getOrDefault(false)
                            if (session.pageInTask > 0 || laterPage) {
                                val advanced = finishPage(session).copy(pagesRead = session.pagesRead,
                                    humanConfirmations = session.humanConfirmations + if (confirmed) 1 else 0)
                                commit(session, advanced, emptyList())
                                return@withLock
                            }
                            if (session.pendingLinks.isNotEmpty()) {
                                // Human returned from a challenge to a recognized SERP. Retry only
                                // the published redirect after this explicit confirmation.
                                update(session.id, session.requestId) { it.copy(phase = GooglePhase.RUNNING,
                                    currentUrl = it.pendingLinks.first().wrapperUrl, requestId = it.requestId + 1,
                                    humanConfirmations = it.humanConfirmations + if (confirmed) 1 else 0,
                                    navigationReadyAt = System.currentTimeMillis() + 8_000, message = "Verificação concluída. Conferindo o destino publicado.") }
                                return@withLock
                            }
                            val ids = repository.mergeDiscoveredJobs(page.jobs)
                            if (stamp != generation) return@withLock
                            val checkpoint = session.copy(foundIds = session.foundIds + ids, taskAccepted = session.taskAccepted + ids.size, pageUrl = url, nextPageUrl = null, emptyPagePending = false,
                                humanConfirmations = session.humanConfirmations + if (confirmed) 1 else 0)
                            val updated = if (page.pendingLinks.isEmpty()) finishPage(checkpoint) else checkpoint.copy(phase = GooglePhase.RUNNING,
                                pendingLinks = page.pendingLinks, currentUrl = page.pendingLinks.first().wrapperUrl,
                                requestId = session.requestId + 1, navigationReadyAt = System.currentTimeMillis() + 8_000,
                                message = "${checkpoint.foundIds.size} links. Conferindo ${page.pendingLinks.size} destinos orgânicos publicados pelo Google.")
                            commit(session, updated, ids)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { update(session.id, session.requestId) { it.copy(phase = GooglePhase.ERROR,
                    message = "Não foi possível ler essa página. Os links anteriores continuam no aparelho.", updatedAt = System.currentTimeMillis()) } }
            }
        }
    }

    /** Called for an observed browser redirect before the external page is loaded. */
    fun resolvedTarget(url: String, sessionId: String, requestId: Long, confirmed: Boolean) {
        val previous = pageJob
        if (previous?.isActive == true && !confirmed) return
        val stamp = generation
        pageJob = scope.launch { if (confirmed) previous?.cancelAndJoin(); processing.withLock {
            val session = repository.preferences.first().googleSession ?: return@withLock
            if (stamp != generation || session.id != sessionId || session.requestId != requestId ||
                (session.phase != GooglePhase.RUNNING && !(confirmed && session.phase == GooglePhase.NEEDS_USER))) return@withLock
            val candidate = session.pendingLinks.firstOrNull() ?: return@withLock
            try {
                val job = repository.resolveGoogleLink(candidate, url, session.query)
                if (confirmed && session.phase == GooglePhase.NEEDS_USER && job == null) {
                    update(session.id, session.requestId) { it.copy(message = "Esse destino não confirma uma vaga da consulta. Conclua a verificação no Google e confirme novamente.") }
                    return@withLock
                }
                if (session.pageInTask > 0) {
                    commit(session, finishPage(session).copy(pagesRead = session.pagesRead,
                        humanConfirmations = session.humanConfirmations + if (confirmed && job != null) 1 else 0), emptyList())
                    return@withLock
                }
                val ids = if (job == null) emptyList() else repository.mergeDiscoveredJobs(listOf(job))
                if (stamp != generation) return@withLock
                val left = session.pendingLinks.drop(1)
                val checkpoint = session.copy(foundIds = session.foundIds + ids, taskAccepted = session.taskAccepted + ids.size, pendingLinks = left,
                    humanConfirmations = session.humanConfirmations + if (confirmed && job != null) 1 else 0)
                val updated = if (left.isEmpty()) finishPage(checkpoint) else checkpoint.copy(phase = GooglePhase.RUNNING,
                    currentUrl = left.first().wrapperUrl, requestId = session.requestId + 1,
                    navigationReadyAt = System.currentTimeMillis() + 8_000,
                    message = "${checkpoint.foundIds.size} links encontrados. Conferindo ${left.size} destinos desta página.")
                commit(session, updated, ids)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update(session.id, session.requestId) { it.copy(phase = GooglePhase.ERROR, message = "Não foi possível conferir o destino. Os links anteriores foram preservados.") } }
        } }
    }

    private fun finishPage(session: GoogleSession): GoogleSession {
        val url = session.pageUrl ?: session.currentUrl.orEmpty()
        val visited = session.visitedUrls + url
        val current = session.tasks.getOrNull(session.taskIndex)
        val counts = session.taskCounts.orEmpty() + listOfNotNull(current?.id?.let { it to session.taskAccepted })
        var index = session.taskIndex + 1
        while (index < session.tasks.size && (session.tasks[index].board == "careers" ||
                session.tasks[index].fallbackFor?.let { (counts[it] ?: 0) > 0 || it in session.skippedTaskIds.orEmpty() } == true)) index++
        val done = index >= session.tasks.size
        return session.copy(taskIndex = index, currentUrl = if (done) url else session.tasks[index].url,
            pagesRead = session.pagesRead + 1, pageInTask = 0,
            visitedUrls = visited, phase = if (done) GooglePhase.COMPLETE else GooglePhase.RUNNING,
            taskAccepted = 0, taskCounts = counts,
            pendingLinks = emptyList(), pageUrl = null, nextPageUrl = null, emptyPagePending = false,
            requestId = session.requestId + 1, navigationReadyAt = if (done) 0 else System.currentTimeMillis() + 8_000,
            updatedAt = System.currentTimeMillis(), message = if (done) "Google: ${session.foundIds.size} links individuais encontrados. Confira os que ainda são prévias."
                else "${session.foundIds.size} links capturados. Página1 concluída; seguindo a próxima consulta.")
    }
    private suspend fun commit(before: GoogleSession, after: GoogleSession, ids: List<String>) {
        repository.updatePreferences { prefs -> val current = prefs.googleSession
            if (current?.id == before.id && current.requestId == before.requestId && current.phase == before.phase)
                prefs.copy(googleSession = after, googleResultIds = prefs.googleResultIds + ids) else prefs }
    }

    fun pause(background: Boolean = false) = scope.launch { controls.withLock {
        pageJob?.cancelAndJoin()
        val session = repository.preferences.first().googleSession ?: return@withLock
        if (session.phase == GooglePhase.RUNNING) update(session.id, session.requestId) { it.copy(phase = GooglePhase.PAUSED,
            message = if (background) "Google pausado ao sair do app. Toque para retomar." else "Coleta Google pausada. Seus links foram preservados.") }
    } }
    fun resume(reload: Boolean = false) = scope.launch { controls.withLock {
        val session = repository.preferences.first().googleSession ?: return@withLock
        if (session.tasks.getOrNull(session.taskIndex)?.board == "careers") {
            update(session.id, session.requestId) { finishPage(it).copy(pagesRead = it.pagesRead) }
        } else if (session.phase in setOf(GooglePhase.PAUSED, GooglePhase.ERROR) && session.pageInTask > 0) {
            update(session.id, session.requestId) { finishPage(it).copy(pagesRead = it.pagesRead) }
        } else if (session.phase in setOf(GooglePhase.PAUSED, GooglePhase.ERROR) || reload && session.phase == GooglePhase.NEEDS_USER) update(session.id, session.requestId) {
            it.copy(phase = GooglePhase.RUNNING, requestId = it.requestId + 1, emptyPagePending = false,
                currentUrl = if (reload) it.pendingLinks.firstOrNull()?.wrapperUrl ?: it.tasks.getOrNull(it.taskIndex)?.url else it.currentUrl,
                navigationReadyAt = 0, message = "Retomando a consulta Google.")
        }
    } }
    fun skip() = scope.launch { controls.withLock {
        generation++
        pageJob?.cancelAndJoin()
        processing.withLock page@ {
            val session = repository.preferences.first().googleSession ?: return@page
            if (session.phase == GooglePhase.COMPLETE) return@page
            // Record an explicit skip separately from real accepted-link counts.
            val skipped = session.tasks.getOrNull(session.taskIndex)?.id
            val advanced = finishPage(session.copy(skippedTaskIds = session.skippedTaskIds.orEmpty() + listOfNotNull(skipped)))
            val updated = advanced.copy(pagesRead = session.pagesRead,
                message = if (advanced.phase == GooglePhase.COMPLETE) "Coleta encerrada. Os links capturados foram preservados." else "Consulta pulada. Seguindo para a próxima consulta dos sites configurados.")
            commit(session, updated, emptyList())
        }
    } }
    suspend fun finish() = controls.withLock {
        generation++
        pageJob?.cancelAndJoin()
        processing.withLock { repository.updatePreferences { it.copy(googleSession = null) } }
    }
    fun failed(message: String, sessionId: String, requestId: Long) = scope.launch {
        update(sessionId, requestId) { if (it.phase in setOf(GooglePhase.RUNNING, GooglePhase.NEEDS_USER))
            it.copy(phase = GooglePhase.NEEDS_USER, message = message, updatedAt = System.currentTimeMillis()) else it }
    }
    suspend fun stopForReset() = controls.withLock { generation++; pageJob?.cancelAndJoin(); Unit }
    private suspend fun update(id: String, request: Long, transform: (GoogleSession) -> GoogleSession) {
        repository.updatePreferences { prefs -> val current = prefs.googleSession
            if (current?.id == id && current.requestId == request) prefs.copy(googleSession = transform(current)) else prefs }
    }
}
