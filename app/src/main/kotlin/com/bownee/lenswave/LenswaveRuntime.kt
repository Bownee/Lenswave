package com.bownee.lenswave

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface LenswaveClock {
    fun nowMillis(): Long
}

interface LenswaveDispatchers {
    val io: CoroutineDispatcher
}

@Singleton
internal class SystemLenswaveClock @Inject constructor() : LenswaveClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

@Singleton
internal class DefaultLenswaveDispatchers @Inject constructor() : LenswaveDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RuntimeEnvironmentModule {
    @Binds abstract fun bindClock(implementation: SystemLenswaveClock): LenswaveClock
    @Binds abstract fun bindDispatchers(implementation: DefaultLenswaveDispatchers): LenswaveDispatchers
}
