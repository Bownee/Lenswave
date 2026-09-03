package com.bownee.lenswave.proton

import android.content.Context
import androidx.work.WorkManager
import com.bownee.lenswave.LenswaveTheme
import com.bownee.lenswave.storage.DatabasePassphraseStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import kotlinx.coroutines.flow.first
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.auth.data.db.AuthDatabase
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.auth.domain.usecase.PostLoginAccountSetup
import me.proton.core.auth.presentation.DefaultHelpOptionHandler
import me.proton.core.auth.presentation.DefaultUserCheck
import me.proton.core.auth.presentation.HelpOptionHandler
import me.proton.core.challenge.data.db.ChallengeDatabase
import me.proton.core.compose.theme.AppTheme
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.devicemigration.domain.usecase.GenerateEdmCode
import me.proton.core.devicemigration.domain.usecase.IsEasyDeviceMigrationAvailable
import me.proton.core.devicemigration.domain.usecase.ObserveEdmCode
import me.proton.core.domain.entity.AppStore
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.eventmanager.data.db.EventMetadataDatabase
import me.proton.core.eventmanager.domain.EventListener
import me.proton.core.featureflag.data.db.FeatureFlagDatabase
import me.proton.core.featureflag.domain.FeatureFlagOverrider
import me.proton.core.humanverification.data.db.HumanVerificationDatabase
import me.proton.core.humanverification.presentation.HumanVerificationApiHost
import me.proton.core.humanverification.presentation.utils.HumanVerificationVersion
import me.proton.core.key.data.db.KeySaltDatabase
import me.proton.core.key.data.db.PublicAddressDatabase
import me.proton.core.keytransparency.data.local.KeyTransparencyDatabase
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.di.AlternativeApiPins
import me.proton.core.network.data.di.BaseProtonApiUrl
import me.proton.core.network.data.di.CertificatePins
import me.proton.core.network.data.di.Constants
import me.proton.core.network.data.di.DohProviderUrls
import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ExtraHeaderProvider
import me.proton.core.network.domain.serverconnection.DohAlternativesListener
import me.proton.core.notification.data.local.db.NotificationDatabase
import me.proton.core.observability.data.db.ObservabilityDatabase
import me.proton.core.payment.data.local.db.PaymentDatabase
import me.proton.core.plan.domain.SupportSignupPaidPlans
import me.proton.core.plan.domain.SupportUpgradePaidPlans
import me.proton.core.push.data.local.db.PushDatabase
import me.proton.core.telemetry.data.db.TelemetryDatabase
import me.proton.core.telemetry.data.repository.TelemetryLocalDataSourceImpl
import me.proton.core.telemetry.data.repository.TelemetryRemoteDataSourceImpl
import me.proton.core.telemetry.data.repository.TelemetryRepositoryImpl
import me.proton.core.telemetry.data.worker.TelemetryWorkerManagerImpl
import me.proton.core.telemetry.domain.TelemetryWorkerManager
import me.proton.core.telemetry.domain.repository.TelemetryLocalDataSource
import me.proton.core.telemetry.domain.repository.TelemetryRemoteDataSource
import me.proton.core.telemetry.domain.repository.TelemetryRepository
import me.proton.core.telemetry.domain.usecase.IsTelemetryEnabled
import me.proton.core.user.data.db.AddressDatabase
import me.proton.core.user.data.db.UserDatabase
import me.proton.core.user.domain.UserManager
import me.proton.core.userrecovery.data.db.DeviceRecoveryDatabase
import me.proton.core.usersettings.data.db.OrganizationDatabase
import me.proton.core.usersettings.data.db.UserSettingsDatabase
import me.proton.core.usersettings.domain.usecase.ObserveUserSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProtonCoreModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseStore: DatabasePassphraseStore,
    ): ProtonCoreDatabase = ProtonCoreDatabase.create(context, passphraseStore)

    @Provides fun provideProduct(): Product = Product.Drive

    @Provides fun provideAppStore(): AppStore = AppStore.GooglePlay

    @Provides fun provideAccountType(): AccountType = AccountType.External

    @Provides
    @BaseProtonApiUrl
    fun provideBaseUrl(): HttpUrl = ProtonPhotosClientProvider.BASE_URL.toHttpUrl()

    @Provides
    @HumanVerificationApiHost
    fun provideHumanVerificationHost(): String = "https://verify.proton.me/"

    @Provides fun provideHumanVerificationVersion(): HumanVerificationVersion = HumanVerificationVersion.HV3

    @Provides fun provideExtraHeaders(): ExtraHeaderProvider = ExtraHeaderProviderImpl()

    @Provides @DohProviderUrls
    fun provideDohUrls(): Array<String> = Constants.DOH_PROVIDERS_URLS

    @Provides @CertificatePins
    fun providePins(): Array<String> = Constants.DEFAULT_SPKI_PINS

    @Provides @AlternativeApiPins
    fun provideAlternativePins(): List<String> = Constants.ALTERNATIVE_API_SPKI_PINS

    @Provides fun provideDohListener(): DohAlternativesListener? = null

    @Provides fun provideFeatureFlagOverrider(): FeatureFlagOverrider = FeatureFlagOverrider { null }

    @Provides fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides fun provideAppTheme(): AppTheme = AppTheme { content -> LenswaveTheme { content() } }

    @Provides fun provideHelpOptionHandler(): HelpOptionHandler = DefaultHelpOptionHandler()

    @Provides @SupportSignupPaidPlans
    fun provideSupportSignupPaidPlans(): Boolean = false

    @Provides @SupportUpgradePaidPlans
    fun provideSupportUpgradePaidPlans(): Boolean = false

    @Provides
    fun provideDeviceMigrationAvailability(): IsEasyDeviceMigrationAvailable =
        object : IsEasyDeviceMigrationAvailable {
            override suspend fun invoke(userId: UserId?): Boolean = false
        }

    @Provides
    fun provideGenerateDeviceMigrationCode(
        apiClient: ApiClient,
        authRepository: AuthRepository,
        cryptoContext: CryptoContext,
    ): GenerateEdmCode = GenerateEdmCode(apiClient, authRepository, cryptoContext)

    @Provides
    fun provideObserveDeviceMigrationCode(generateEdmCode: GenerateEdmCode): ObserveEdmCode =
        ObserveEdmCode(generateEdmCode)

    @Provides
    fun provideUserCheck(
        @ApplicationContext context: Context,
        accountManager: me.proton.core.accountmanager.domain.AccountManager,
        userManager: UserManager,
    ): PostLoginAccountSetup.UserCheck = DefaultUserCheck(context, accountManager, userManager)

    @Provides
    @ElementsIntoSet
    @JvmSuppressWildcards
    fun provideEventListeners(): Set<EventListener<*, *>> = emptySet()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProtonCoreBindings {
    @Binds abstract fun bindApiClient(client: LenswaveApiClient): ApiClient

    @Binds abstract fun bindAccountDatabase(database: ProtonCoreDatabase): AccountDatabase

    @Binds abstract fun bindAuthDatabase(database: ProtonCoreDatabase): AuthDatabase

    @Binds abstract fun bindChallengeDatabase(database: ProtonCoreDatabase): ChallengeDatabase

    @Binds abstract fun bindEventMetadataDatabase(database: ProtonCoreDatabase): EventMetadataDatabase

    @Binds abstract fun bindFeatureFlagDatabase(database: ProtonCoreDatabase): FeatureFlagDatabase

    @Binds abstract fun bindHumanVerificationDatabase(database: ProtonCoreDatabase): HumanVerificationDatabase

    @Binds abstract fun bindKeySaltDatabase(database: ProtonCoreDatabase): KeySaltDatabase

    @Binds abstract fun bindPublicAddressDatabase(database: ProtonCoreDatabase): PublicAddressDatabase

    @Binds abstract fun bindKeyTransparencyDatabase(database: ProtonCoreDatabase): KeyTransparencyDatabase

    @Binds abstract fun bindNotificationDatabase(database: ProtonCoreDatabase): NotificationDatabase

    @Binds abstract fun bindObservabilityDatabase(database: ProtonCoreDatabase): ObservabilityDatabase

    @Binds abstract fun bindPaymentDatabase(database: ProtonCoreDatabase): PaymentDatabase

    @Binds abstract fun bindPushDatabase(database: ProtonCoreDatabase): PushDatabase

    @Binds abstract fun bindTelemetryDatabase(database: ProtonCoreDatabase): TelemetryDatabase

    @Binds abstract fun bindAddressDatabase(database: ProtonCoreDatabase): AddressDatabase

    @Binds abstract fun bindUserDatabase(database: ProtonCoreDatabase): UserDatabase

    @Binds abstract fun bindDeviceRecoveryDatabase(database: ProtonCoreDatabase): DeviceRecoveryDatabase

    @Binds abstract fun bindOrganizationDatabase(database: ProtonCoreDatabase): OrganizationDatabase

    @Binds abstract fun bindUserSettingsDatabase(database: ProtonCoreDatabase): UserSettingsDatabase
}

/** Keeps Proton telemetry disabled while the authoritative account setting is unavailable. */
@Singleton
class LenswaveIsTelemetryEnabled
    @Inject
    constructor(
        private val observeUserSettings: ObserveUserSettings,
    ) : IsTelemetryEnabled {
        override suspend fun invoke(userId: UserId?): Boolean {
            userId ?: return false
            return runCatching {
                observeUserSettings(userId, refresh = false).first()?.telemetry == true
            }.getOrDefault(false)
        }
    }

/** Mirrors Proton's telemetry plumbing while replacing only its fail-open policy binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LenswaveTelemetryBindings {
    @Binds abstract fun bindIsTelemetryEnabled(impl: LenswaveIsTelemetryEnabled): IsTelemetryEnabled

    @Binds abstract fun bindTelemetryRepository(impl: TelemetryRepositoryImpl): TelemetryRepository

    @Binds abstract fun bindTelemetryLocalDataSource(impl: TelemetryLocalDataSourceImpl): TelemetryLocalDataSource

    @Binds abstract fun bindTelemetryRemoteDataSource(impl: TelemetryRemoteDataSourceImpl): TelemetryRemoteDataSource

    @Binds abstract fun bindTelemetryWorkerManager(impl: TelemetryWorkerManagerImpl): TelemetryWorkerManager
}
