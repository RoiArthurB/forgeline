package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeasurement
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter

/** Answers [next], and records the history each measurement was handed. */
class FakeTrendingMeter : TrendingMeter {
    var next: ForgeResult<TrendingMeasurement> = ForgeResult.Success(TrendingMeasurement("state", emptyMap()))
    val calls = mutableListOf<Pair<String?, String?>>()

    override suspend fun measure(token: String?, previousState: String?): ForgeResult<TrendingMeasurement> {
        calls += token to previousState
        return next
    }
}
