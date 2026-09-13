package com.iris.irisshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.iris.irisshell.domain.settings.PinLockRepository
import com.iris.irisshell.domain.terminal.ObserveFirstLaunchUseCase
import com.iris.irisshell.domain.terminal.TriggerBootstrapUseCase
import com.iris.irisshell.terminal.ExtraKeyState
import com.iris.irisshell.terminal.TerminalManager
import com.iris.irisshell.terminal.UbuntuSetupState
import com.iris.irisshell.ui.setup.BootstrapStepperScreen
import com.iris.irisshell.ui.setup.SetupRecoveryScreen
import com.iris.irisshell.ui.setup.onboarding.OnboardingScreen
import com.iris.irisshell.ui.terminal.TerminalScreen
import com.iris.irisshell.ui.pin.PinEntryScreen
import com.iris.irisshell.ui.theme.IrisTheme
import com.iris.irisshell.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val ANIM_DURATION_MS = 300
private val FLOAT_TWEEN = tween<Float>(durationMillis = ANIM_DURATION_MS)
private val INT_TWEEN = tween<IntOffset>(durationMillis = ANIM_DURATION_MS)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
            IrisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IrisNavHost()
                }
            }
        }
    }

    @Composable
    private fun IrisNavHost() {
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
                AnimatedScreen(targetState = "splash") {
                    SplashScreen()
                }
            }

            composable("onboarding") {
                AnimatedScreen(targetState = "onboarding") {
                    OnboardingScreen(
                        onCompleted = {
                            navController.navigate("bootstrap") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
            }

            composable("bootstrap") {
                AnimatedScreen(targetState = "bootstrap") {
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
            }

            composable("recovery") {
                AnimatedScreen(targetState = "recovery") {
                    SetupRecoveryScreen(
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            composable("terminal") {
                val context = LocalContext.current as ComponentActivity
                val isPinLockEnabled by pinLock.isEnabled.collectAsStateWithLifecycle(initialValue = false)

                if (isPinLockEnabled) {
                    AnimatedScreen(targetState = "pin_entry") {
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
                    }
                } else {
                    AnimatedScreen(targetState = "terminal") {
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
            }

            composable("terminal_home") {
                val context = LocalContext.current as ComponentActivity
                AnimatedScreen(targetState = "terminal_home") {
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

            composable("settings") {
                AnimatedScreen(targetState = "settings", isModal = true) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedScreen(
    targetState: String,
    isModal: Boolean = false,
    content: @Composable () -> Unit,
) {
    var previousState by rememberSaveable { mutableStateOf<String?>(null) }
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            val forward = previousState != null && targetState != previousState
            val isForwardEnter = targetState > (previousState ?: "")
            val enter = if (isModal) {
                fadeIn(animationSpec = FLOAT_TWEEN) + slideInHorizontally(animationSpec = INT_TWEEN) { it / 10 }
            } else if (isForwardEnter) {
                slideInHorizontally(animationSpec = INT_TWEEN) { it } + fadeIn(animationSpec = FLOAT_TWEEN)
            } else {
                slideInHorizontally(animationSpec = INT_TWEEN) { -it } + fadeIn(animationSpec = FLOAT_TWEEN)
            }
            val exit = if (isModal) {
                fadeOut(animationSpec = FLOAT_TWEEN) + slideOutHorizontally(animationSpec = INT_TWEEN) { -it / 10 }
            } else if (isForwardEnter) {
                slideOutHorizontally(animationSpec = INT_TWEEN) { -it } + fadeOut(animationSpec = FLOAT_TWEEN)
            } else {
                slideOutHorizontally(animationSpec = INT_TWEEN) { it } + fadeOut(animationSpec = FLOAT_TWEEN)
            }
            ContentTransform(
                targetContentEnter = enter,
                initialContentExit = exit,
            )
        },
        contentKey = { it },
    ) { target ->
        previousState = target
        content()
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
