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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.terminal.ExtraKeyState
import dev.drosh.terminal.TerminalManager
import dev.drosh.terminal.UbuntuSetupState
import dev.drosh.ui.setup.BootstrapStepperScreen
import dev.drosh.ui.setup.SetupRecoveryScreen
import dev.drosh.ui.setup.onboarding.OnboardingScreen
import dev.drosh.ui.terminal.TerminalScreen
import dev.drosh.ui.pin.PinEntryScreen
import dev.drosh.ui.theme.DroshTheme
import dev.drosh.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            DroshTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DroshNavHost()
                }
            }
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
            startDestination = "splash",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("splash") {
                val destination = if (firstCompleted == true) "terminal" else "onboarding"
                if (firstCompleted != null) {
                    LaunchedEffect(firstCompleted) {
                        navController.navigate(destination) {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                }
                SplashScreen()
            }

            composable("onboarding") {
                OnboardingScreen(
                    onCompleted = {
                        navController.navigate("bootstrap") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    },
                )
            }

            composable("bootstrap") {
                BootstrapStepperScreen(
                    onReady = {
                        navController.navigate("terminal") {
                            popUpTo("bootstrap") { inclusive = true }
                        }
                    },
                    onSetupFailed = {
                        navController.navigate("recovery") {
                            popUpTo("bootstrap") { inclusive = true }
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
                val context = LocalContext.current as ComponentActivity
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
                        ubuntuSetupState = UbuntuSetupState.Ready,
                        onRetry = { triggerBootstrap.retry() },
                        onOpenSettings = { navController.navigate("settings") },
                        extraKeyState = extraKeyState,
                        onExit = { context.finish() },
                    )
                }
            }

            composable("terminal_home") {
                val context = LocalContext.current as ComponentActivity
                TerminalScreen(
                    terminalManager = terminalManager,
                    ubuntuSetupState = UbuntuSetupState.Ready,
                    onRetry = { triggerBootstrap.retry() },
                    onOpenSettings = { navController.navigate("settings") },
                    extraKeyState = extraKeyState,
                    onExit = { context.finish() },
                )
            }

            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Color(0xFF3B82F6),
            strokeWidth = 2.dp,
        )
    }
}
