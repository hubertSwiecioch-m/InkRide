package com.speedevand.inkride.history.data

import com.speedevand.inkride.core.database.LifetimeStatsAggregate
import com.speedevand.inkride.core.database.RideHistoryDao
import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLifetimeStatsRepository(
    private val dao: RideHistoryDao
) : LifetimeStatsRepository {

    override fun observeLifetimeStats(): Flow<LifetimeStats> =
        dao.observeLifetimeStats().map { it.toDomain() }
}

private fun LifetimeStatsAggregate.toDomain() = LifetimeStats(
    totalRides = totalRides,
    totalDistanceKm = totalDistanceKm,
    totalMovingTimeSeconds = totalMovingTimeSeconds,
    totalElevationGainM = totalElevationGainM,
    maxSpeedKmh = maxSpeedKmh,
    totalCaloriesKcal = totalCaloriesKcal
)
