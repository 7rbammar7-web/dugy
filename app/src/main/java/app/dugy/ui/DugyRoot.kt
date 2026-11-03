package app.dugy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.security.AppPhase
import app.dugy.ui.lock.LockScreen
import app.dugy.ui.lock.PinSetupScreen
import app.dugy.ui.main.MainContent
import app.dugy.ui.onboarding.OnboardingScreen

@Composable
fun DugyRoot(container: AppContainer) {
    val phase by container.lockManager.phase.collectAsStateWithLifecycle()
    when (phase) {
        AppPhase.ONBOARDING -> OnboardingScreen(onContinue = { container.lockManager.finishOnboarding() })
        AppPhase.PIN_SETUP -> PinSetupScreen(container)
        AppPhase.LOCKED -> LockScreen(container)
        AppPhase.UNLOCKED -> MainContent(container)
    }
}
