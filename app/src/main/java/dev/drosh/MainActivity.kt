package dev.drosh

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.core.LocaleHelper
import dev.drosh.data.local.irisShellDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import dev.drosh.data.session.SessionManagerAdapter
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.terminal.ExtraKeyState
import dev.drosh.terminal.TerminalManager
import dev.drosh.terminal.UbuntuSetupState
import dev.drosh.ui.setup.SetupFlowScreen
import dev.drosh.ui.setup.SetupRecoveryScreen
import dev.drosh.design.system.DroshBackground
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.ui.settings.SettingsViewModel
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.ProvideLocale
import java.util.Locale
import dev.drosh.ui.splash.SplashScreen
import dev.drosh.ui.terminal.TerminalScreen
import dev.drosh.ui.pin.PinEntryScreen
import dev.drosh.ui.theme.DroshTheme
import dev.drosh.ui.settings.SettingsScreen
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

    private val localeKey = stringPreferencesKey("locale")

    override fun attachBaseContext(base: Context) {
        val language = runBlocking(Dispatchers.IO) {
            base.irisShellDataStore.data.map { prefs -> prefs[localeKey] ?: "" }.first()
        }
        super.attachBaseContext(LocaleHelper.applyLocale(base, language))
    }

    @Inject lateinit var terminalManager: TerminalManager
    @Inject lateinit var firstLaunchUseCase: ObserveFirstLaunchUseCase
    @Inject lateinit var triggerBootstrap: TriggerBootstrapUseCase
    @Inject lateinit var extraKeyState: ExtraKeyState
    @Inject lateinit var pinLock: PinLockRepository
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
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
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
    }

    override fun onStop() {
        keyboardModeReceiver?.let {
            runCatching { unregisterReceiver(it) }
            keyboardModeReceiver = null
        }
        // A keyboard we cannot see may have gone away with another app.
        KeyboardWindowModeState.reset()
        super.onStop()
    }

    /**
     * Listens for Drosh Keyboard telling us where it is docked.
     *
     * Only that app can send this, and only while Drosh is in the foreground:
     * a floating keyboard must not push the layout around, a docked one must.
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // NOT_EXPORTED would reject Drosh Keyboard, which is a separate app
            // and therefore a different uid. The action is only protected
            // because nothing in this app acts on it blindly — it just records
            // a placement mode — so a foreign sender can do no harm.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    @Composable
    private fun DroshNavHost() {
        val navController = rememberNavController()
        val coroutineScope = rememberCoroutineScope()

        var firstCompleted by rememberSaveable { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(Unit) {
            firstLaunchUseCase.isCompleted().collect { firstCompleted = it }
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
                val destination = if (firstCompleted == true) "terminal" else "setup_flow"
                var splashDone by rememberSaveable { mutableStateOf(false) }
                var left by rememberSaveable { mutableStateOf(false) }

                // Wait for both halves: the word to finish being drawn, and the
                // stored flag to say where to go. Either alone races — leaving on
                // the flag cuts the word short, waiting on the splash's timer
                // can leave it waiting for a callback that already fired.
                LaunchedEffect(splashDone, firstCompleted) {
                    if (splashDone && firstCompleted != null && !left) {
                        left = true
                        navController.navigate(destination) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                }

                SplashScreen(onFinished = { splashDone = true })
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
                val isPinLockEnabled by pinLock.isEnabled.collectAsStateWithLifecycle(initialValue = false)

                if (isPinLockEnabled) {
                    PinEntryScreen(
                        title = "Enter PIN",
                        subtitle = "App lock enabled",
                        onPinReady = { pin ->
                            coroutineScope.launch {
                                if (pinLock.verify(pin)) {
                                    navController.navigate("terminal_home") {
                                        popUpTo("terminal") { inclusive = true }
                                    }
                                }
                            }
                        },
                    )
                } else {
                    TerminalScreen(
                        terminalManager = terminalManager,
                        settingsRepository = settingsRepository,
                        ubuntuSetupState = UbuntuSetupState.Ready,
                        onRetry = { triggerBootstrap.retry() },
                        onOpenSettings = { navController.navigate("settings") },
                        extraKeyState = extraKeyState,
                        onExit = { context.finish() },
                        onOpenAgent = { navController.navigate("agent_home") },
                    )
                }
            }

            composable("terminal_home") {
                val context = LocalDroshActivity.current
                TerminalScreen(
                    terminalManager = terminalManager,
                    settingsRepository = settingsRepository,
                    ubuntuSetupState = UbuntuSetupState.Ready,
                    onRetry = { triggerBootstrap.retry() },
                    onOpenSettings = { navController.navigate("settings") },
                    extraKeyState = extraKeyState,
                    onExit = { context.finish() },
                    onOpenAgent = { navController.navigate("agent_home") },
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
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Blank means "follow the system", where no override applies. */
private fun String.toLocaleOrNull() =
    takeIf { it.isNotBlank() }?.let { java.util.Locale.forLanguageTag(it) }
