package com.novahorizon.wanderly.ui.gems

import com.novahorizon.wanderly.ui.common.UiText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemsLoadGateTest {

    @Test
    fun `auto loads gems when screen has not requested anything yet`() {
        assertTrue(GemsLoadGate.shouldAutoLoad(null))
        assertTrue(GemsLoadGate.shouldAutoLoad(GemsViewModel.GemsState.Idle))
    }

    @Test
    fun `does not auto load gems again after the first request state`() {
        assertFalse(GemsLoadGate.shouldAutoLoad(GemsViewModel.GemsState.Loading(UiText.DynamicString("Loading"))))
        assertFalse(GemsLoadGate.shouldAutoLoad(GemsViewModel.GemsState.Empty(UiText.DynamicString("No gems"))))
        assertFalse(GemsLoadGate.shouldAutoLoad(GemsViewModel.GemsState.Error(UiText.DynamicString("Nope"))))
    }
}
