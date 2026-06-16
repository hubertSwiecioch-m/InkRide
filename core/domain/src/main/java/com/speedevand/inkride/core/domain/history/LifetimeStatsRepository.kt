package com.speedevand.inkride.core.domain.history

import kotlinx.coroutines.flow.Flow

/** Observes lifetime totals, recomputed whenever the ride history changes. */
interface LifetimeStatsRepository {
    fun observeLifetimeStats(): Flow<LifetimeStats>
}
