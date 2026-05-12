package com.novahorizon.wanderly.di

import com.novahorizon.wanderly.data.WanderlyRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for classes that cannot use constructor injection
 * (e.g., Workers, BroadcastReceivers, plain Services).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WanderlyEntryPoint {
    fun wanderlyRepository(): WanderlyRepository
}
