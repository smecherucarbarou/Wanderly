package com.novahorizon.wanderly

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.osmdroid.config.Configuration

object OsmdroidInitializer {
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var initJob: Deferred<Unit>? = null

    fun start(context: Context, scope: CoroutineScope): Deferred<Unit> {
        val appContext = context.applicationContext
        return synchronized(lock) {
            initJob ?: scope.async(Dispatchers.IO) {
                configure(appContext)
            }.also { initJob = it }
        }
    }

    suspend fun awaitInitialized(context: Context) {
        val job = synchronized(lock) { initJob } ?: start(context, fallbackScope)
        job.await()
    }

    private fun configure(appContext: Context) {
        Configuration.getInstance().load(
            appContext,
            appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = appContext.packageName
        Configuration.getInstance().cacheMapTileCount = 9.toShort()
        Configuration.getInstance().cacheMapTileOvershoot = 9.toShort()
        Configuration.getInstance().osmdroidTileCache =
            appContext.cacheDir.resolve("osmdroid")
    }
}
