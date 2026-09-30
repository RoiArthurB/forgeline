package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo

/**
 * Measures a forge's Trending from its star counts, for forges without a trending list: once a day, each measurement
 * adding to the history kept from the one before. [TrendingMeasurement.state] is that history, opaque to the caller,
 * handed back as `previousState` next time.
 */
interface TrendingMeter {
    suspend fun measure(token: String?, previousState: String?): ForgeResult<TrendingMeasurement>
}

data class TrendingMeasurement(val state: String, val lists: Map<TrendingPeriod, List<TrendingRepo>>)
