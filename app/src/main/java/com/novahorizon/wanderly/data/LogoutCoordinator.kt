package com.novahorizon.wanderly.data

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.novahorizon.wanderly.notifications.NotificationStateStore
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.widgets.WanderlyStreakWidgetProvider

enum class LogoutCleanupStep {
    SIGN_OUT,
    STOP_REALTIME,
    CANCEL_USER_WORK,
    CLEAR_NOTIFICATION_STATE,
    CLEAR_LOCAL_STATE,
    CANCEL_WIDGET_REFRESH
}

data class LogoutCleanupFailure(
    val step: LogoutCleanupStep,
    val errorType: String,
    val message: String?
)

data class LogoutResult(
    val failures: List<LogoutCleanupFailure>
) {
    val signedOut: Boolean = failures.none { it.step == LogoutCleanupStep.SIGN_OUT }
    val success: Boolean = failures.isEmpty()
}

class LogoutCoordinator(
    private val signOut: suspend () -> Unit,
    private val stopRealtime: suspend () -> Unit,
    private val cancelUserWork: suspend () -> Unit,
    private val clearNotificationState: suspend () -> Unit,
    private val clearLocalState: suspend () -> Unit,
    private val cancelWidgetRefresh: suspend () -> Unit
) {

    suspend fun logoutCompletely(): LogoutResult {
        val failures = mutableListOf<LogoutCleanupFailure>()

        runStep(LogoutCleanupStep.SIGN_OUT, failures, signOut)
        runStep(LogoutCleanupStep.STOP_REALTIME, failures, stopRealtime)
        runStep(LogoutCleanupStep.CANCEL_USER_WORK, failures, cancelUserWork)
        runStep(LogoutCleanupStep.CLEAR_NOTIFICATION_STATE, failures, clearNotificationState)
        runStep(LogoutCleanupStep.CLEAR_LOCAL_STATE, failures, clearLocalState)
        runStep(LogoutCleanupStep.CANCEL_WIDGET_REFRESH, failures, cancelWidgetRefresh)

        return LogoutResult(failures)
    }

    private suspend fun runStep(
        step: LogoutCleanupStep,
        failures: MutableList<LogoutCleanupFailure>,
        block: suspend () -> Unit
    ) {
        runCatching { block() }
            .onFailure { throwable ->
                val failure = LogoutCleanupFailure(
                    step = step,
                    errorType = throwable.javaClass.simpleName,
                    message = LogRedactor.redact(throwable.message)
                )
                failures += failure
                runCatching {
                    AppLogger.e("LogoutCoordinator", "Logout cleanup failed at $step [${failure.errorType}: ${failure.message}]")
                }
            }
    }

    companion object {
        fun create(
            context: Context,
            authRepository: AuthRepository,
            repository: WanderlyRepository,
            workManager: WorkManager = WorkManager.getInstance(context.applicationContext)
        ): LogoutCoordinator {
            val appContext = context.applicationContext
            return LogoutCoordinator(
                signOut = { authRepository.logout() },
                stopRealtime = {
                    appContext.stopService(Intent(appContext, HiveRealtimeService::class.java))
                },
                cancelUserWork = {
                    workManager.cancelUniqueWork("StreakCheckWork")
                    workManager.cancelUniqueWork("SocialCheckWork")
                    workManager.cancelAllWorkByTag("StreakCheckWork")
                    workManager.cancelAllWorkByTag("SocialCheckWork")
                },
                clearNotificationState = {
                    NotificationStateStore(appContext).clearAll()
                },
                clearLocalState = {
                    repository.clearLocalState()
                },
                cancelWidgetRefresh = {
                    WanderlyStreakWidgetProvider.cancelScheduledUpdates(appContext)
                }
            )
        }
    }
}
