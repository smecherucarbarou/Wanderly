package com.novahorizon.wanderly.util

interface Clock {
    fun nowMillis(): Long
}

object RealClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
