package com.bownee.lenswave.proton

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.storage.AtomicFileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** One worker run's time under the foreground service, by the wall clock. */
internal data class ProtonForegroundRun(
    val endedAtMillis: Long,
    val durationMillis: Long,
) {
    init {
        require(durationMillis >= 0L) { "A run cannot have a negative duration" }
    }
}

/**
 * Keeps the worker within the platform's foreground allowance. Android 15 gives a dataSync
 * foreground service six hours in every rolling 24 across all its runs; once they are spent
 * the promotion is refused and a run continues invisibly until the platform stops it. Three
 * two-hour runs back to back spend it all, so the runs of the last day are remembered
 * ([ProtonThumbnailForegroundBudgetStore]) and a follow-up that would take the total past
 * [BUDGET_MILLIS] is held back until enough of the window has passed.
 */
internal object ProtonThumbnailForegroundBudgetPolicy {
    const val WINDOW_MILLIS = 24L * 60L * 60L * 1_000L

    /** Well under the platform's six hours, so a refusal never has to be discovered the hard way. */
    const val BUDGET_MILLIS = 4L * 60L * 60L * 1_000L

    /** More runs than this in one window means something is wrong; the oldest are forgotten. */
    const val MAX_RECORDED_RUNS = 64

    /**
     * A run whose promotion was refused goes on for a little without a notification, because a
     * device that refuses every background promotion would otherwise never download anything;
     * the platform stops a plain job at ten minutes, so the run ends well before that.
     */
    const val BACKGROUND_ONLY_RUN_MILLIS = 5L * 60L * 1_000L

    /** How long a run waits after its promotion was refused before it is worth trying again. */
    const val FOREGROUND_REFUSED_DELAY_MILLIS = 15L * 60L * 1_000L

    private const val RUN_SEPARATOR = ';'
    private const val FIELD_SEPARATOR = ':'

    /** The runs still inside the window ending at [nowMillis], newest last, at most [MAX_RECORDED_RUNS]. */
    fun prune(
        runs: List<ProtonForegroundRun>,
        nowMillis: Long,
    ): List<ProtonForegroundRun> =
        runs
            .filter { run -> run.endedAtMillis > nowMillis - WINDOW_MILLIS && run.endedAtMillis <= nowMillis }
            .sortedBy(ProtonForegroundRun::endedAtMillis)
            .takeLast(MAX_RECORDED_RUNS)

    fun record(
        runs: List<ProtonForegroundRun>,
        run: ProtonForegroundRun,
        nowMillis: Long,
    ): List<ProtonForegroundRun> = prune(runs + run, nowMillis)

    fun usedMillis(
        runs: List<ProtonForegroundRun>,
        nowMillis: Long,
    ): Long = prune(runs, nowMillis).sumOf(ProtonForegroundRun::durationMillis)

    /**
     * How long a run of [nextRunMillis] has to wait so that it fits in the budget with what the
     * window still holds: zero when it fits now, otherwise the moment the oldest runs have left
     * the window. A run longer than the whole budget waits for the window to empty.
     */
    fun delayUntilAffordableMillis(
        runs: List<ProtonForegroundRun>,
        nowMillis: Long,
        nextRunMillis: Long = ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS,
    ): Long {
        val inWindow = prune(runs, nowMillis)
        var remaining = inWindow.sumOf(ProtonForegroundRun::durationMillis)
        if (remaining + nextRunMillis <= BUDGET_MILLIS) return 0L
        for (run in inWindow) {
            remaining -= run.durationMillis
            if (remaining + nextRunMillis <= BUDGET_MILLIS) return run.endedAtMillis + WINDOW_MILLIS - nowMillis
        }
        return inWindow.last().endedAtMillis + WINDOW_MILLIS - nowMillis
    }

    fun encode(runs: List<ProtonForegroundRun>): String =
        runs.joinToString(
            RUN_SEPARATOR.toString(),
        ) { run -> "${run.endedAtMillis}$FIELD_SEPARATOR${run.durationMillis}" }

    /** A value this code did not write (or a damaged one) reads as no runs; the budget errs open. */
    fun decode(encoded: String?): List<ProtonForegroundRun> {
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(RUN_SEPARATOR).mapNotNull { field ->
            val parts = field.split(FIELD_SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            val endedAt = parts[0].toLongOrNull() ?: return@mapNotNull null
            val duration = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
            ProtonForegroundRun(endedAt, duration)
        }
    }
}

/** The foreground runs of the last day per user; see [ProtonThumbnailForegroundBudgetPolicy]. */
internal interface ProtonThumbnailForegroundBudgetStore {
    fun runs(userId: UserId): List<ProtonForegroundRun>

    fun record(
        userId: UserId,
        run: ProtonForegroundRun,
        nowMillis: Long,
    )
}

@Singleton
internal class SharedPreferencesProtonThumbnailForegroundBudgetStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProtonThumbnailForegroundBudgetStore {
        private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

        override fun runs(userId: UserId): List<ProtonForegroundRun> =
            ProtonThumbnailForegroundBudgetPolicy.decode(preferences.getString(key(userId), null))

        override fun record(
            userId: UserId,
            run: ProtonForegroundRun,
            nowMillis: Long,
        ) {
            val runs = ProtonThumbnailForegroundBudgetPolicy.record(runs(userId), run, nowMillis)
            preferences.edit { putString(key(userId), ProtonThumbnailForegroundBudgetPolicy.encode(runs)) }
        }

        private fun key(userId: UserId): String = "$KEY_RUNS_PREFIX${AtomicFileStore.safeName(userId.id)}"

        private companion object {
            /** Shared with the pause flag: one small file for everything the worker remembers. */
            const val PREFERENCES_NAME = "thumbnail-downloads"
            const val KEY_RUNS_PREFIX = "foreground-runs-"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonThumbnailForegroundBudgetModule {
    @Binds
    abstract fun bindProtonThumbnailForegroundBudgetStore(
        implementation: SharedPreferencesProtonThumbnailForegroundBudgetStore,
    ): ProtonThumbnailForegroundBudgetStore
}
