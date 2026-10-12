package dev.drosh

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.drosh.core.LocaleHelper
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.core.view.WindowCompat
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import dev.drosh.data.session.SessionManagerAdapter
import dev.drosh.data.settings.toml.readStoredLocaleTag
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.settings.toMillis
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.editor.EditorScreen
import dev.drosh.terminal.ExtraKeyState
import dev.drosh.terminal.TerminalManager
import dev.drosh.terminal.UbuntuSetupState
import dev.drosh.ui.setup.SetupFlowScreen
import dev.drosh.ui.setup.SetupRecoveryScreen
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshFonts
import dev.drosh.domain.settings.ThemeMode
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.ProvideLocale
import java.util.Locale
import dev.drosh.ui.splash.SplashScreen
import dev.drosh.ui.terminal.TerminalScreen
import dev.drosh.ui.pin.PinEntryScreen
import dev.drosh.ui.theme.DroshTheme
import dev.drosh.ui.agent.AgentChatScreen
import dev.drosh.ui.agent.AgentHomeScreen
import dev.drosh.ui.agent.AgentSettingsScreen
import dev.drosh.ui.settings.AboutCategory
import dev.drosh.ui.settings.AppearanceCategory
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.ui.settings.KeyboardCategory
import dev.drosh.ui.settings.LanguageCategory
import dev.drosh.ui.settings.SecurityCategory
import dev.drosh.ui.settings.SettingsCategoryRoute
import dev.drosh.ui.settings.OnSettingsUpdate
import dev.drosh.ui.settings.SettingsHomeScreen
import dev.drosh.ui.session.DeviceIdentityViewModel
import dev.drosh.ui.settings.SettingsViewModel
import dev.drosh.ui.settings.ShellCategory
import dev.drosh.ui.settings.TerminalCategory
import dev.drosh.ui.settings.ThemeStoreScreen
import dev.drosh.ui.workspace.WorkspaceScreen
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.BroadcastReceiver

import android.content.IntentFilter
import dev.drosh.ui.keyboard.KeyboardWindowModeState
import android.content.Intent
import android.os.Build


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context) {
        // The settings file, not DataStore: the language lives in the TOML now
        // and this runs before injection, so it is read straight off disk.
        val language = readStoredLocaleTag(base)
        super.attachBaseContext(LocaleHelper.applyLocale(base, language))
    }

    @Inject lateinit var terminalManager: TerminalManager
    @Inject lateinit var firstLaunchUseCase: ObserveFirstLaunchUseCase
    @Inject lateinit var triggerBootstrap: TriggerBootstrapUseCase
    @Inject lateinit var extraKeyState: ExtraKeyState
    @Inject lateinit var pinLock: PinLockRepository
    @Inject lateinit var settingsStore: SettingsStore

    /**
     * True while the app lock is standing. The gate over the whole nav host,
     * so leaving and coming back re-asks instead of showing the last screen.
     */
    private val locked = MutableStateFlow(true)

    /** Elapsed realtime when the window went away, 0 while it is up. */
    private var backgroundedAt = 0L

    /**
     * The auto-lock timeout as a duration. Collected once per start rather
     * than read per tick — the setting changes from the settings screen, and
     * the next backgrounding is soon enough.
     */
    private var autoLockTimeoutMs = 0L

    /** Digits the entry screens wait for, from the settings file. */
    private var pinLength = PinLockRepository.PIN_LENGTH
    @Inject lateinit var sessionManagerAdapter: SessionManagerAdapter
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // The terminal reports itself as a text editor, so Android opened the
        // keyboard every time the view took focus — and the terminal takes focus
        // on every tap. ALWAYS_HIDDEN stops focus from implying the keyboard;
        // an explicit showSoftInput still works, which is how the toolbar
        // button and entering the screen drive it. adjustResize is kept so
        // imePadding() keeps shrinking the terminal when the keyboard is up.
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            // A shown status bar over the terminal has to be see-through for the
            // clock to sit on the output rather than on a grey slab. Below API 35
            // Android paints a translucent scrim behind the bar on its own the
            // moment it is shown over content, and no amount of
            // `statusBarColor = TRANSPARENT` suppresses it. The terminal screen
            // shows the bar whenever the viewport is at the live edge, so this
            // would be a bar appearing and disappearing with its own background.
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            // Captured before the locale provider replaces the context, because
            // the localized wrapper is not an Activity and the routes below cast
            // the ambient context to one.
            val activity = LocalContext.current as ComponentActivity
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val fontPack by settingsViewModel.fontPack.collectAsStateWithLifecycle()
            val language by settingsViewModel.locale.collectAsStateWithLifecycle()

            // A language change is applied to the activity's own resources too,
            // so anything reading them outside the composition — the service,
            // the notification, a toaster — sees the same language. No
            // recreate: that tore down every screen and threw away scroll and
            // state, and the change was not visible until it finished.
            LaunchedEffect(language) {
                Locale.setDefault(language.toLocaleOrNull() ?: Locale.getDefault())
                val config = Configuration(resources.configuration).apply {
                    language.toLocaleOrNull()?.let { setLocale(it) }
                }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }

            CompositionLocalProvider(LocalDroshActivity provides activity) {
                ProvideLocale(language) {
                    DroshTheme(
                        dark = when (themeMode) {
                            ThemeMode.System -> null
                            ThemeMode.Light -> false
                            ThemeMode.Dark -> true
                        },
                        fontSet = DroshFonts.byName(fontPack.name),
                    ) {
                        // DroshBackground, not the Material default.
                        //
                        // Surface fills the window, so whatever colour it carries is
                        // what shows in the status bar strip — every screen paints its
                        // own background below it, and those start at DroshBackground.
                        // Left on the Material surface colour, the strip above every
                        // screen was a lighter band (#1A1A1A) against the screen's
                        // #0E0E0E, with a visible seam exactly where the status bar
                        // ended. Setting it to the app's base makes the window one
                        // colour and each screen's background continue into the strip.
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = DroshBackground,
                        ) {
                            DroshNavHost()
                        }
                    }
                }
            }
        }
    }

    /**
     * Non-null while listening for Drosh Keyboard's placement broadcast.
     * Registered per onStart so it cannot outlive a visible window.
     */
    private var keyboardModeReceiver: BroadcastReceiver? = null

    override fun onStart() {
        super.onStart()
        // The session adapter is a process singleton started once from
        // Application.onCreate, so anything it concluded about the previous
        // Activity would still hold here. Telling it the UI is back lets it
        // create a default session if the last one was closed.
        sessionManagerAdapter.onUiForegrounded()
        registerKeyboardModeReceiver()
        collectAutoLockPreferences()
        lifecycleScope.launch {
            // Re-read the settings file on every resume. The file lives where
            // a shell can edit it, and a FileObserver is not something to
            // trust alone: an edit made while the app was away, or in a
            // directory that only appeared later, would otherwise sit
            // unnoticed until the next write from here.
            settingsStore.reload()
            // With the lock off there is nothing to ask, and a stale locked
            // value from an earlier enable must not outlive the disabling.
            if (pinLock.isEnabled.first()) maybeRelock() else locked.value = false
        }
    }

    override fun onStop() {
        keyboardModeReceiver?.let {
            runCatching { unregisterReceiver(it) }
            keyboardModeReceiver = null
        }
        // A keyboard we cannot see may have gone away with another app.
        KeyboardWindowModeState.reset()
        backgroundedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    /**
     * Locks again if the user asked to be locked out.
     *
     * The elapsed-time check is what makes the timeout mean a timeout: an
     * app that was away for a second stays unlocked under [AutoLockTimeout.OneMinute],
     * and "Immediately" locks on the way out every time. "Never" holds the
     * window open for as long as it likes.
     */
    private suspend fun maybeRelock() {
        val wasAway = backgroundedAt != 0L
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        backgroundedAt = 0L
        // A fresh process is already locked — the field starts true — so
        // there is nothing to decide: the gate asks once, on whichever route
        // the user lands. Only a return from the background is a question.
        if (!wasAway) return
        locked.value = away >= autoLockTimeoutMs
    }

    /**
     * Reads the two security preferences that act on the window itself.
     *
     * Collected here rather than pushed from the settings screen: the screen
     * writes the file, the window reads the file, and nothing has to
     * remember to tell the other.
     */
    private fun collectAutoLockPreferences() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsStore.settings.collect { snapshot ->
                    autoLockTimeoutMs = snapshot.security.autoLock.toMillis()
                    pinLength = snapshot.security.pinLength
                    val secure = snapshot.security.blockScreenshots
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }

    /**
     * Listens for Drosh Keyboard telling us where it is docked.
     *
     * Only that app can send this — the receiver demands the signature-level
     * [KeyboardWindowModeState.PERMISSION_SEND_KEYBOARD_MODE] — and only while
     * Drosh is in the foreground: a floating keyboard must not push the layout
     * around, a docked one must.
     */
    private fun registerKeyboardModeReceiver() {
        if (keyboardModeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != KeyboardWindowModeState.ACTION_WINDOW_MODE) return
                KeyboardWindowModeState.onBroadcast(
                    intent.getStringExtra(KeyboardWindowModeState.EXTRA_MODE),
                )
            }
        }
        keyboardModeReceiver = receiver
        val filter = IntentFilter(KeyboardWindowModeState.ACTION_WINDOW_MODE)
        val permission = KeyboardWindowModeState.PERMISSION_SEND_KEYBOARD_MODE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The permission is what gates the sender; RECEIVER_EXPORTED only
            // says "another app may reach me", and without a holder of the
            // permission that reach is dead. Without it, a third-party app
            // could flip the keyboard padding while Drosh is in front.
            registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter, permission, null)
        }
    }

    /**
     * Renders one settings category, editing the file directly.
     *
     * The snapshot is read once here and handed down: every row writes through
     * the store, and the flow re-emits, so a row never holds the value it just
     * wrote — it holds the file's answer, which is the same thing said by one
     * source instead of two.
     */
    @Composable
    private fun SettingsCategoryHost(
        category: SettingsCategoryRoute?,
        onBack: () -> Unit,
        onOpenThemeStore: () -> Unit,
    ) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val settings by viewModel.snapshot.collectAsStateWithLifecycle()
        val onUpdate: OnSettingsUpdate = { transform -> viewModel.update(transform) }

        if (category == null) {
            // An unknown id is a bad link, not a crash: drop back to the list.
            LaunchedEffect(Unit) { onBack() }
            return
        }

        when (category) {
            SettingsCategoryRoute.Appearance ->
                AppearanceCategory(settings, onBack, onUpdate, onOpenThemeStore, viewModel)
            SettingsCategoryRoute.ThemeStore ->
                ThemeStoreScreen(onBack = onBack)
            SettingsCategoryRoute.Terminal ->
                TerminalCategory(settings, onBack, onUpdate)
            SettingsCategoryRoute.Keyboard ->
                KeyboardCategory(settings, onBack, onUpdate)
            SettingsCategoryRoute.Shell ->
                ShellCategory(settings, onBack, onUpdate)
            SettingsCategoryRoute.Security ->
                SecurityCategory(settings, onBack, onUpdate, viewModel)
            SettingsCategoryRoute.Language ->
                LanguageCategory(settings, onBack, onUpdate)
            SettingsCategoryRoute.About ->
                AboutCategory(
                    onBack = onBack,
                    viewModel = viewModel,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    gitSha = BuildConfig.GIT_SHA,
                )
        }
    }

    @Composable
    private fun DroshNavHost() {
        // The lock stands in front of the whole host, not in front of one
        // route: the setting is about the app, so wherever the user left off
        // is what they come back to find it behind.
        //
        // One gate, not two. The terminal route used to ask on its own as
        // well, and passing one only ever queued the other: unlocking here
        // revealed the terminal route, which asked again because it had been
        // reading the same enabled flag all along.
        val pinEnabled by pinLock.isEnabled.collectAsStateWithLifecycle(initialValue = false)
        val isLocked by locked.collectAsStateWithLifecycle()
        var pinError by rememberSaveable { mutableStateOf<String?>(null) }

        // The settings file was edited while the app was running — by a shell,
        // a script or the agent. The new values are already live, but some of
        // them (shell, rootfs, the editor) are only read at the moment they
        // are used, and a restart is the honest thing to offer.
        val externalEdit by settingsStore.changedExternally.collectAsStateWithLifecycle()
        if (externalEdit) {
            AlertDialog(
                onDismissRequest = { settingsStore.acknowledgeExternalChange() },
                containerColor = DroshSurfaceVariant,
                title = { Text(stringResource(R.string.settings_external_title), color = DroshText) },
                text = { Text(stringResource(R.string.settings_external_body), color = DroshTextSecondary) },
                confirmButton = {
                    TextButton(onClick = { restartApp() }) {
                        Text(stringResource(R.string.settings_external_restart), color = DroshPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { settingsStore.acknowledgeExternalChange() }) {
                        Text(stringResource(R.string.settings_external_ok), color = DroshTextSecondary)
                    }
                },
            )
        }

        val navController = rememberNavController()
        val coroutineScope = rememberCoroutineScope()

        // The nav host stays alive behind a *returning* lock: the terminal is
        // already up, the session is already warm, and a curtain over that is
        // instant. A cold start does not use this — it goes loading → lock →
        // terminal as destinations, so the lock never waits on a terminal that
        // has not been built yet.
        val currentRoute by navController.currentBackStackEntryAsState()
        Box(modifier = Modifier.fillMaxSize()) {
            DroshNavGraph(navController, coroutineScope)

            // Only over a screen that is alive. The cold-start lock is a
            // destination, not a curtain — so the gate shows for a return from
            // the background and nothing else.
            val lockable = currentRoute?.destination?.route !in setOf(null, "loading", "lock", "setup_flow", "recovery")
            AnimatedVisibility(
                visible = pinEnabled && isLocked && lockable,
                // Entering, the lock drops onto a live terminal; leaving, it
                // lifts off one. A fade alone read as a screen swap; the scale
                // makes it read as the same screen being uncovered.
                enter = fadeIn(tween(220)) + scaleIn(initialScale = 1.05f, animationSpec = tween(280)),
                exit = fadeOut(tween(240)) + scaleOut(targetScale = 1.05f, animationSpec = tween(280)),
            ) {
                PinEntryScreen(
                    title = "Enter PIN",
                    subtitle = "App lock enabled",
                    errorMessage = pinError,
                    pinLength = pinLength,
                    onPinReady = { pin ->
                        lifecycleScope.launch {
                            if (pinLock.verify(pin)) {
                                pinError = null
                                locked.value = false
                            } else {
                                pinError = "Wrong PIN"
                            }
                        }
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    @Composable
    private fun DroshNavGraph(navController: NavHostController, coroutineScope: CoroutineScope) {

        var firstCompleted by rememberSaveable { mutableStateOf<Boolean?>(null) }
        var settingsReady by remember { mutableStateOf(false) }
        var pinEnabled by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            firstLaunchUseCase.isCompleted().collect { firstCompleted = it }
        }

        // The settings file's first read happens here rather than wherever
        // the terminal happens to need it first: the splash is the one screen
        // that can honestly show work being done, and a lock screen that has
        // to read a file under the user's fingers is a lock screen that janks.
        LaunchedEffect(Unit) {
            pinEnabled = pinLock.isEnabled.first()
            settingsStore.settings.first()
            settingsReady = true
        }

        // The lock, cold. A return from the background locks over a terminal
        // that is already alive — that one is the host's overlay, below — and
        // the difference matters: a lock that arrives before the terminal
        // exists must not be the reason the terminal takes three seconds to
        // appear, so it takes its own destination instead.
        LaunchedEffect(locked.value) {
            val current = navController.currentBackStackEntry?.destination?.route
            if (locked.value && pinEnabled && current == "terminal") {
                navController.navigate("lock") { popUpTo("terminal") { inclusive = true } }
            }
        }

        LaunchedEffect(firstCompleted) {
            if (firstCompleted == true && triggerBootstrap.state == TriggerBootstrapUseCase.State.NotStarted) {
                triggerBootstrap.start()
            }
        }

        NavHost(
            navController = navController,
            startDestination = "loading",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("loading") {
                val destination = when {
                    firstCompleted != true -> "setup_flow"
                    locked.value && pinEnabled -> "lock"
                    else -> "terminal"
                }
                var splashDone by rememberSaveable { mutableStateOf(false) }
                var left by rememberSaveable { mutableStateOf(false) }

                // Three halves now: the word, the stored flag, and the file's
                // first read. Any one of them alone races.
                LaunchedEffect(splashDone, firstCompleted, settingsReady) {
                    if (splashDone && firstCompleted != null && settingsReady && !left) {
                        left = true
                        navController.navigate(destination) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                }

                SplashScreen(
                    onFinished = { splashDone = true },
                    ready = firstCompleted != null && settingsReady,
                )
            }

            composable("lock") {
                // The lock's own screen. Its background is the app's, its
                // keypad raises no keyboard, and the terminal behind it does
                // not exist yet — nothing here can stall on the terminal's
                // work.
                var pinError by rememberSaveable { mutableStateOf<String?>(null) }
                val pinLength by settingsStore.settings
                    .map { it.security.pinLength }
                    .collectAsStateWithLifecycle(initialValue = 4)

                PinEntryScreen(
                    title = "Enter PIN",
                    subtitle = "App lock enabled",
                    errorMessage = pinError,
                    pinLength = pinLength,
                    onPinReady = { pin ->
                        lifecycleScope.launch {
                            if (pinLock.verify(pin)) {
                                locked.value = false
                                navController.navigate("terminal") {
                                    popUpTo("lock") { inclusive = true }
                                }
                            } else {
                                pinError = "Wrong PIN"
                            }
                        }
                    },
                    onCancel = { finish() },
                )
            }

            composable("setup_flow") {
                SetupFlowScreen(
                    onReady = {
                        // The flag used to be set by the bootstrap launcher, which
                        // only runs if the user reaches the bootstrap page by
                        // pressing its button. They can also get there by
                        // swiping through the pages, and then the flag was never
                        // written and every later launch opened onboarding again.
                        // Reaching the terminal is the actual signal that setup
                        // is done, so mark it here.
                        coroutineScope.launch { firstLaunchUseCase.markCompleted() }
                        navController.navigate("terminal") {
                            popUpTo("setup_flow") { inclusive = true }
                        }
                    },
                    onSetupFailed = {
                        navController.navigate("recovery") {
                            popUpTo("setup_flow") { inclusive = true }
                        }
                    },
                )
            }

            composable("recovery") {
                SetupRecoveryScreen(
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable("terminal") {
                val context = LocalDroshActivity.current
                TerminalScreen(
                    terminalManager = terminalManager,
                    settingsRepository = settingsRepository,
                    ubuntuSetupState = UbuntuSetupState.Ready,
                    onRetry = { triggerBootstrap.retry() },
                    onOpenSettings = { navController.navigate("settings") },
                    extraKeyState = extraKeyState,
                    onExit = { context.finish() },
                    onOpenEditor = { path -> navController.openEditor(path) },
                    onOpenAgent = { navController.navigate("agent_home") },
                    onOpenSsh = { navController.navigate("ssh") },
                    onOpenProjects = { navController.navigate("workspace") },
                )
            }


            composable("agent_home") {
                AgentHomeScreen(
                    onOpenChat = { id -> navController.navigate("agent_chat/$id") },
                    onNewChat = { navController.navigate("agent_chat/new") },
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("agent_settings") },
                )
            }

            composable("agent_chat/{chatId}") { entry ->
                val chatId = entry.arguments?.getString("chatId")
                AgentChatScreen(
                    chatId = chatId,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("agent_settings") },
                    // A chat created on the first send replaces this entry rather
                    // than stacking on it, so Back from it returns to Agent Home
                    // instead of to a placeholder chat that no longer exists.
                    onChatCreated = { createdId ->
                        navController.navigate("agent_chat/$createdId") {
                            popUpTo("agent_chat/$chatId") { inclusive = true }
                        }
                    },
                )
            }

            composable("agent_settings") {
                AgentSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable("settings") {
                SettingsHomeScreen(
                    onBack = { navController.popBackStack() },
                    // The categories are routes of their own: the screen has
                    // to survive a configuration change and a deep link, and
                    // eight hidden composables behind one destination would
                    // have made both ambiguous.
                    onOpenCategory = { category ->
                        navController.navigate("settings/${category.id}")
                    },
                    onOpenProjects = { navController.navigate("workspace") },
                    // Reached from Settings rather than from a gear on the agent
                    // screens: an OpenRouter key is the user's, not a chat's.
                    onOpenAgentSettings = { navController.navigate("agent_settings") },
                )
            }

            composable("settings/{category}") { entry ->
                SettingsCategoryHost(
                    category = SettingsCategoryRoute.fromId(entry.arguments?.getString("category")),
                    onBack = { navController.popBackStack() },
                    onOpenThemeStore = { navController.navigate("settings/theme_store") },
                )
            }

            // Projects — workspaces and the sessions filed under them. Reached
            // from Settings, which is why opening a session from here has to pop
            // all the way back to the terminal rather than just one entry.
            // SSH hosts: sidebar → SSH opens this route; tapping a host
            // connects and drops back into the terminal with the live session.
            composable("ssh") {
                dev.drosh.ui.ssh.SshHomeScreen(
                    onBack = { navController.popBackStack() },
                    onConnected = { navController.popBackStack() },
                )
            }

            composable("workspace") {
                WorkspaceScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSession = { navController.returnToTerminal() },
                )
            }

            // `editor <path>` lands here. The path goes in a query argument, not a
            // path segment: a guest path contains slashes, and a path segment
            // would be matched against the route pattern and fail. Callers pass
            // it through Uri.encode, which also covers the `?`, `#` and space a
            // filename is allowed to contain.
            composable(
                route = "editor?guestPath={guestPath}",
                arguments = listOf(
                    navArgument("guestPath") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val guestPath = entry.arguments?.getString("guestPath").orEmpty()
                EditorScreen(
                    guestPath = guestPath,
                    onClose = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Blank means "follow the system", where no override applies. */
private fun String.toLocaleOrNull() =
    takeIf { it.isNotBlank() }?.let { java.util.Locale.forLanguageTag(it) }

/**
 * Come back to a terminal from a screen the user reached through Settings.
 *
 * The stack when this is called is terminal → settings → workspace, so a single
 * `popBackStack()` would land on Settings — the screen they left to do
 * something else, and the one thing they are not waiting for. Popping to the
 * named destination clears everything in between. `popBackStack` reports
 * whether it found the destination, so the fallback is a real check rather
 * than a guess — and reaching for the terminal still has to leave something on
 * the stack for Back to work.
 */
private fun NavHostController.returnToTerminal() {
    if (!popBackStack(TERMINAL_ROUTE, inclusive = false)) navigate(TERMINAL_ROUTE)
}

private const val TERMINAL_ROUTE = "terminal"

/**
 * Starts the app again from its launcher intent.
 *
 * A fresh task rather than `recreate()`: the point of the restart is that
 * everything reads its settings from the beginning, and a recreated activity
 * shares its singletons with the run that is being left behind.
 */
private fun Activity.restartApp() {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return
    finishAffinity()
    startActivity(intent)
}

/**
 * Opens the editor for a guest path.
 *
 * Uri.encode, not string interpolation: a path may contain spaces, `?`, `#` or
 * `&`, all of which would otherwise truncate or split the query argument and
 * send the editor to the wrong file — or to none.
 */
private fun NavHostController.openEditor(guestPath: String) {
    navigate("editor?guestPath=${android.net.Uri.encode(guestPath)}")
}
