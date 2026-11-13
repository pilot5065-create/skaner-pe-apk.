package pl.marketradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalCalculatorTest {
    @Test fun calculatesTechnicalMetricsFromChronologicalDailyCandles() {
        val instrument = Instrument("TEST", "Test spółka", "USA", "Test")
        val candles = (0 until 60).map { i ->
            val close = 100.0 + i
            DailyCandle(i.toString().padStart(5, '0'), close + 2.0, close - 1.0, close, 1_000L + i)
        }
        val signal = TechnicalCalculator.calculate(instrument, candles, "test")
        assertEquals(149.5, signal.sma20, 0.0001)
        assertEquals(134.5, signal.sma50, 0.0001)
        assertTrue(signal.ema9 > signal.sma20)
        assertTrue(signal.atr14 > 0.0)
        assertTrue(signal.resistance20 > signal.support20)
        assertTrue(signal.relativeVolume > 0.0)
        assertEquals("test", signal.source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsufficientHistory() {
        val instrument = Instrument("TEST", "Test spółka", "USA", "Test")
        TechnicalCalculator.calculate(
            instrument,
            (0 until 20).map { DailyCandle(it.toString(), 12.0, 9.0, 10.0, 100L) },
            "test"
        )
    }
}
