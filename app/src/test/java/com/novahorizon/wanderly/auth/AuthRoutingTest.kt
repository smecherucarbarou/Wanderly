package com.novahorizon.wanderly.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRoutingTest {

    @Test
    fun opensMainOnlyWhenSessionAndRememberMeArePresent() {
        assertTrue(AuthRouting.shouldOpenMain(hasSession = true, rememberMe = true))
        assertFalse(AuthRouting.shouldOpenMain(hasSession = true, rememberMe = false))
        assertFalse(AuthRouting.shouldOpenMain(hasSession = false, rememberMe = true))
    }

    @Test
    fun startsSessionServicesOnlyWhenSessionExists() {
        assertTrue(AuthRouting.shouldStartSessionServices(hasSession = true))
        assertFalse(AuthRouting.shouldStartSessionServices(hasSession = false))
    }
}
