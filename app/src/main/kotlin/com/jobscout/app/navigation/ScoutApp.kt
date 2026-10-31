package com.jobscout.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import com.jobscout.core.designsystem.splitTerms
import com.jobscout.feature.search.presentation.*
import com.jobscout.feature.jobs.presentation.*
import com.jobscout.feature.profile.presentation.ResumeBuilderScreen
import com.jobscout.feature.profile.presentation.OnboardingScreen
import com.jobscout.feature.settings.presentation.SettingsScreen

@Serializable data object Explore
@Serializable data object Home
@Serializable data object WebDiscovery
@Serializable data object WebImport
@Serializable data object GoogleBrowser
@Serializable data object GoogleSitesRoute
@Serializable data object Saved
@Serializable data object Profile
@Serializable data object Settings
@Serializable data class JobDetail(val id: String)
private data class Destination(val route: Any, val title: String, val icon: ImageVector)
private val destinations = listOf(Destination(Home, "Início", Icons.Outlined.Home), Destination(Explore, "Vagas", Icons.Outlined.WorkOutline),
    Destination(Saved, "Salvas", Icons.Outlined.Bookmarks), Destination(Profile, "Currículo", Icons.Outlined.Description))

@Composable
fun ScoutApp(viewModel: ScoutViewModel = hiltViewModel(), incomingLink: String? = null,
             googlePageLoader: ((android.webkit.WebView, String) -> Unit)? = null, consumeIncoming: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onAppOpened() }
    LaunchedEffect(state.activity.message) { state.activity.message?.let { snackbar.showSnackbar(it); viewModel.messageRead() } }
    when {
        state.readError != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Não foi possível carregar seus dados", style = MaterialTheme.typography.titleLarge)
            Text("Seus dados continuam no aparelho. Tente novamente.")
            Text(state.readError.orEmpty(), style = MaterialTheme.typography.bodySmall)
            Button(viewModel::retryLoading) { Text("Tentar novamente") }
        }
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !state.preferences.onboardingComplete -> OnboardingScreen(state.preferences, state.activity.savingSetup, state.activity.setupError, viewModel::completeSetup)
        else -> ScoutNavigation(state, viewModel, snackbar, incomingLink, consumeIncoming, googlePageLoader)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScoutNavigation(state: ScoutUiState, viewModel: ScoutViewModel, snackbar: SnackbarHostState,
                                       incomingLink: String?, consumeIncoming: () -> Unit, googlePageLoader: ((android.webkit.WebView, String) -> Unit)?) {
    val nav = rememberNavController()
    val navigator = remember(nav) { ScoutNavigator(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val latest = rememberUpdatedState(state)
    val keyboard = LocalSoftwareKeyboardController.current
    val googleVisible = entry?.destination?.hasRoute<GoogleBrowser>() == true
    val openGoogle: () -> Unit = { keyboard?.hide(); navigator.push(GoogleBrowser) }
    val openCaptured: () -> Unit = { keyboard?.hide(); viewModel.requestCapturedResults(); navigator.tab(Explore) }
    val session = state.preferences.googleSession
    var handledHumanConfirmation by remember(session?.id) { mutableLongStateOf(session?.humanConfirmations ?: 0) }
    LaunchedEffect(session?.humanConfirmations) {
        val count = session?.humanConfirmations ?: 0
        if (count > handledHumanConfirmation && googleVisible) { keyboard?.hide(); navigator.back() }
        handledHumanConfirmation = count
    }
    val filters: () -> Unit = remember(navigator, viewModel, keyboard) { {
        keyboard?.hide(); viewModel.requestSearchEditor(); navigator.tab(Explore)
    } }
    val openJob: (String) -> Unit = remember(navigator, viewModel, keyboard) { { id ->
        keyboard?.hide(); navigator.push(JobDetail(id)); viewModel.viewed(id)
    } }
    LaunchedEffect(incomingLink, entry != null) {
        if (incomingLink != null && entry != null) {
            keyboard?.hide(); navigator.push(WebImport); viewModel.importWebLink(incomingLink); consumeIncoming()
        }
    }
    LaunchedEffect(state.activity.savedWebId, entry != null) {
        if (state.activity.savedWebId != null && entry != null) {
            if (nav.currentDestination?.hasRoute<WebImport>() == true) navigator.tab(Saved)
            viewModel.webSaveHandled()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (googleVisible || entry?.destination?.hasRoute<GoogleSitesRoute>() == true || entry?.destination?.hasRoute<Settings>() == true || entry?.destination?.hasRoute<WebDiscovery>() == true || entry?.destination?.hasRoute<WebImport>() == true) {
            TopAppBar(title = { Text(if (googleVisible) "Pesquisa Google" else if (entry?.destination?.hasRoute<GoogleSitesRoute>() == true) "Sites para dorks" else if (entry?.destination?.hasRoute<Settings>() == true) "Configurações" else if (entry?.destination?.hasRoute<WebImport>() == true) "Revisar vaga" else "Coleta pela internet") },
                navigationIcon = { IconButton({ keyboard?.hide(); navigator.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } })
        }
    }, bottomBar = {
        if (entry != null && !googleVisible && entry?.destination?.hasRoute<JobDetail>() != true && entry?.destination?.hasRoute<WebDiscovery>() != true && entry?.destination?.hasRoute<WebImport>() != true) Surface(modifier = Modifier.imePadding(), shadowElevation = 5.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 15.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                destinations.forEach { dest ->
                    val selected = entry?.destination?.hasRoute(dest.route::class) == true || dest.route == Explore &&
                        (entry?.destination?.hasRoute<Settings>() == true || entry?.destination?.hasRoute<GoogleSitesRoute>() == true)
                    Surface(onClick = { keyboard?.hide(); navigator.tab(dest.route) }, modifier = Modifier.weight(if (selected) 1.45f else 1f).height(40.dp).testTag("nav-${dest.title}"),
                        shape = RoundedCornerShape(9.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(dest.icon, dest.title, Modifier.size(21.dp))
                            if (selected) { Spacer(Modifier.width(5.dp)); Text(dest.title, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        // The graph captures stable state holders, not a fresh graph/lambda for every scan update.
        NavHost(nav, startDestination = Home, modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
            composable<Home> {
                HomeScreen(latest.value, { viewModel.search(latest.value.preferences.query) }, viewModel::cancelSearch,
                    { navigator.tab(Explore) }, { navigator.push(WebDiscovery) }, { navigator.tab(Profile) }, openJob, viewModel::bookmark,
                    { text ->
                        keyboard?.hide()
                        if (text.isBlank()) filters() else { viewModel.search(latest.value.preferences.query.copy(titles = splitTerms(text))); navigator.tab(Explore) }
                    }, filters, openGoogle, { viewModel.startGoogle() }, openCaptured)
            }
            composable<Explore> { SearchScreen(latest.value, { query -> keyboard?.hide(); viewModel.search(query) }, viewModel::savePreset, viewModel::removePreset, openJob, viewModel::bookmark, viewModel::cancelSearch, openGoogle, { viewModel.startGoogle() }, openCaptured, { navigator.push(GoogleSitesRoute) }, { navigator.push(Settings) }, viewModel::clearUnsavedJobs) }
            composable<Saved> { SavedScreen(latest.value, openJob, viewModel::bookmark, viewModel::dismiss) }
            composable<Profile> { ResumeBuilderScreen(latest.value, viewModel::importPdf, viewModel::saveResume, viewModel::exportResume, viewModel::discardDraft, viewModel::deleteResume) }
            composable<GoogleSitesRoute> { GoogleSitesScreen(latest.value.preferences.googleSites.orEmpty(), viewModel::saveGoogleSites) }
            composable<Settings> { SettingsScreen(latest.value, viewModel::saveSettings, viewModel::clearAll, viewModel.credentialSettings) }
            composable<WebDiscovery> { WebDiscoveryScreen(latest.value, { url -> keyboard?.hide(); navigator.push(WebImport); viewModel.importWebLink(url) },
                { viewModel.search(latest.value.preferences.query) }, viewModel::cancelSearch, filters, openGoogle, { viewModel.startGoogle() }, openCaptured, { navigator.push(GoogleSitesRoute) }) }
            composable<GoogleBrowser> { if (latest.value.preferences.googleSession == null) TextButton({ viewModel.startGoogle() }) { Text("Iniciar busca Google") } }
            composable<WebImport> { WebImportScreen(latest.value, viewModel::saveWebDraft, viewModel::discardWebDraft) { navigator.back() } }
            composable<JobDetail> { details ->
                val item = latest.value.all.firstOrNull { it.tracked.job.id == details.toRoute<JobDetail>().id }
                if (item == null) TextButton({ navigator.back() }) { Text("Vaga indisponível · voltar") }
                else JobDetailsScreen(item, { navigator.back() }, { viewModel.bookmark(item.tracked) },
                    { status, notes -> viewModel.track(item.tracked.job.id, status, notes) }, { viewModel.dismiss(item.tracked) },
                    { viewModel.verifyJob(item.tracked.job.id) }, item.tracked.job.id in latest.value.activity.verifyingIds)
            }
        }
        GoogleBrowserRuntime(state, googleVisible, viewModel, openCaptured, googlePageLoader)
        }
    }
}
