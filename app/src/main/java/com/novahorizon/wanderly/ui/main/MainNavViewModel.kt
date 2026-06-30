package com.novahorizon.wanderly.ui.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * One-shot navigation commands a hosted Fragment (rendered via `AndroidFragment`) emits to the
 * Compose `NavHost` in [com.novahorizon.wanderly.MainActivity]. During the B interop phase the
 * screens still live in Fragments, but the Compose NavController owns the back stack — so a Fragment
 * cannot call `findNavController()` anymore and routes through this bridge instead.
 */
sealed interface MainNavCommand {
    /** Go to the Missions tab (from Map's "browse missions" / Social's "browse missions"). */
    data object ToMissions : MainNavCommand

    /** Push the Wanderly Guide (from Map). */
    data object ToGuide : MainNavCommand

    /** Push the Dev Dashboard (from Profile, debug only). */
    data object ToDevDashboard : MainNavCommand

    /** Onboarding finished: go to Map and drop Onboarding from the back stack. */
    data object AfterOnboarding : MainNavCommand

    /** Pop the current destination (Guide back, Dev Dashboard up / access denied). */
    data object Back : MainNavCommand
}

@HiltViewModel
class MainNavViewModel @Inject constructor() : ViewModel() {

    private val _commands = Channel<MainNavCommand>(Channel.BUFFERED)
    val commands: Flow<MainNavCommand> = _commands.receiveAsFlow()

    fun send(command: MainNavCommand) {
        _commands.trySend(command)
    }
}
