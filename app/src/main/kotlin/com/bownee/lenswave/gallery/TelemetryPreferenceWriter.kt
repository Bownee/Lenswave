package com.bownee.lenswave.gallery

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId
import me.proton.core.usersettings.domain.usecase.PerformUpdateTelemetry
import javax.inject.Inject
import javax.inject.Singleton

/** Writes the account's telemetry preference to Proton; throws when the write does not land. */
interface TelemetryPreferenceWriter {
    suspend fun setTelemetryEnabled(
        userId: UserId,
        enabled: Boolean,
    )
}

@Singleton
internal class ProtonTelemetryPreferenceWriter
    @Inject
    constructor(
        private val updateTelemetry: PerformUpdateTelemetry,
    ) : TelemetryPreferenceWriter {
        override suspend fun setTelemetryEnabled(
            userId: UserId,
            enabled: Boolean,
        ) {
            updateTelemetry(userId, enabled)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TelemetryPreferenceModule {
    @Binds abstract fun bindTelemetryPreferenceWriter(
        implementation: ProtonTelemetryPreferenceWriter,
    ): TelemetryPreferenceWriter
}
