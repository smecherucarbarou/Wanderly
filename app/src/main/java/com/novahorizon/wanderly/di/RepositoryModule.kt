package com.novahorizon.wanderly.di

import android.content.Context
import androidx.work.WorkManager
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.data.LogoutCoordinator
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun providePreferencesStore(@ApplicationContext context: Context): PreferencesStore =
        PreferencesStore(context)

    @Provides
    @Singleton
    fun provideWanderlyRepository(@ApplicationContext context: Context): WanderlyRepository =
        WanderlyRepository(context)

    @Provides
    @Singleton
    fun provideProfileStateProvider(repository: WanderlyRepository): ProfileStateProvider =
        ProfileStateProvider(repository)

    @Provides
    @Singleton
    fun provideAuthRepository(): AuthRepository = AuthRepository()

    @Provides
    @Singleton
    fun provideMissionDetailsRepository(): MissionDetailsRepository = MissionDetailsRepository()

    @Provides
    fun provideLogoutCoordinator(
        @ApplicationContext context: Context,
        authRepository: AuthRepository,
        repository: WanderlyRepository
    ): LogoutCoordinator =
        LogoutCoordinator.create(
            context = context,
            authRepository = authRepository,
            repository = repository,
            workManager = WorkManager.getInstance(context)
        )
}
