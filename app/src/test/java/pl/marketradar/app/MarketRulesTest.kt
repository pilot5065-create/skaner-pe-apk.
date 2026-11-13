package pl.marketradar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRulesTest {
    @Test fun alertFiresWhenMagnitudeCrossesConfiguredThreshold() {
        assertTrue(MarketRules.crossesAlert(2.7, 3.1, 3.0))
    }

    @Test fun alertDoesNotRepeatWhileMoveRemainsAboveThreshold() {
        assertFalse(MarketRules.crossesAlert(3.1, 3.8, 3.0))
    }

    @Test fun alertFiresWhenDirectionReversesBeyondThreshold() {
        assertTrue(MarketRules.crossesAlert(4.2, -3.6, 3.0))
    }

    @Test fun alertDoesNotFireBelowThreshold() {
        assertFalse(MarketRules.crossesAlert(1.1, 2.9, 3.0))
    }

    @Test fun invalidThresholdIsIgnored() {
        assertFalse(MarketRules.crossesAlert(0.0, 8.0, 0.0))
    }
}
