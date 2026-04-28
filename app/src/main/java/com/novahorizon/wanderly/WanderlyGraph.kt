package com.novahorizon.wanderly

import android.annotation.SuppressLint
import android.content.Context
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.WanderlyRepository

object WanderlyGraph {
    @Volatile
    @SuppressLint("StaticFieldLeak")
    // Safe for the current manual graph: WanderlyRepository stores only applicationContext.
    private var repository: WanderlyRepository? = null
    @Volatile
    @SuppressLint("StaticFieldLeak")
    // Test-only override follows the same applicationContext repository contract.
    private var repositoryOverride: WanderlyRepository? = null
    @Volatile
    private var emailAuthServiceOverride: EmailAuthService? = null
    @Volatile
    private var authRepository: AuthRepository? = null
    @Volatile
    private var authRepositoryOverride: AuthRepository? = null
    @Volatile
    private var missionGenerationServiceOverride: MissionGenerationService? = null
    @Volatile
    private var missionLocationProviderOverride: MissionLocationProvider? = null
    @Volatile
    private var missionCityResolverOverride: MissionCityResolver? = null
    @Volatile
    private var missionDetailsRepository: MissionDetailsRepository? = null

    fun repository(context: Context): WanderlyRepository {
        repositoryOverride?.let { return it }
        return repository ?: synchronized(this) {
            repository ?: WanderlyRepository(context.applicationContext).also { repository = it }
        }
    }

    fun emailAuthService(): EmailAuthService =
        emailAuthServiceOverride ?: SupabaseEmailAuthService

    fun authRepository(): AuthRepository {
        authRepositoryOverride?.let { return it }
        return authRepository ?: synchronized(this) {
            authRepository ?: AuthRepository().also { authRepository = it }
        }
    }

    fun missionGenerationService(): MissionGenerationService =
        missionGenerationServiceOverride ?: DefaultMissionGenerationService

    fun missionLocationProvider(): MissionLocationProvider =
        missionLocationProviderOverride ?: DefaultMissionLocationProvider

    fun missionCityResolver(): MissionCityResolver =
        missionCityResolverOverride ?: DefaultMissionCityResolver

    fun missionDetailsRepository(): MissionDetailsRepository {
        return missionDetailsRepository ?: synchronized(this) {
            missionDetailsRepository ?: MissionDetailsRepository().also { missionDetailsRepository = it }
        }
    }

    fun setRepositoryForTesting(testRepository: WanderlyRepository?) {
        repositoryOverride = testRepository
    }

    fun setEmailAuthServiceForTesting(service: EmailAuthService?) {
        emailAuthServiceOverride = service
    }

    fun setAuthRepositoryForTesting(testRepository: AuthRepository?) {
        authRepositoryOverride = testRepository
    }

    fun setMissionGenerationServiceForTesting(service: MissionGenerationService?) {
        missionGenerationServiceOverride = service
    }

    fun setMissionLocationProviderForTesting(provider: MissionLocationProvider?) {
        missionLocationProviderOverride = provider
    }

    fun setMissionCityResolverForTesting(resolver: MissionCityResolver?) {
        missionCityResolverOverride = resolver
    }

    fun resetTestOverrides() {
        repositoryOverride = null
        emailAuthServiceOverride = null
        authRepositoryOverride = null
        authRepository = null
        missionGenerationServiceOverride = null
        missionLocationProviderOverride = null
        missionCityResolverOverride = null
        missionDetailsRepository = null
        repository = null
    }
}
