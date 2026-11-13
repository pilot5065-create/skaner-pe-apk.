package pl.marketradar.app

import kotlin.math.abs

object MarketRules {
    fun crossesAlert(previousPercent: Double, currentPercent: Double, level: Double): Boolean {
        if (!previousPercent.isFinite() || !currentPercent.isFinite() || !level.isFinite() || level <= 0.0) return false
        val crossesMagnitude = abs(previousPercent) < level && abs(currentPercent) >= level
        val reversesDirection = previousPercent * currentPercent < 0.0 && abs(currentPercent) >= level
        return crossesMagnitude || reversesDirection
    }
}
