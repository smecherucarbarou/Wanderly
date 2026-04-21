package com.novahorizon.wanderly.auth

object AuthRouting {
    fun shouldOpenMain(hasSession: Boolean, rememberMe: Boolean): Boolean = hasSession && rememberMe

    fun shouldStartSessionServices(hasSession: Boolean): Boolean = hasSession
}
