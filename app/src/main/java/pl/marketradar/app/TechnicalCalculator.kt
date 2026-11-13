package pl.marketradar.app

import kotlin.math.abs

data class DailyCandle(
    val date: String,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

object TechnicalCalculator {
    fun calculate(instrument: Instrument, input: List<DailyCandle>, source: String): TechnicalSignal {
        val candles = input
            .filter { it.close.isFinite() && it.close > 0.0 && it.high.isFinite() && it.low.isFinite() && it.high >= it.low }
            .sortedBy { it.date }
        require(candles.size >= 50) { "Potrzeba co najmniej 50 poprawnych świec dziennych dla ${instrument.symbol}." }

        val closes = candles.map { it.close }
        val last = candles.last()
        val sma20 = closes.takeLast(20).average()
        val sma50 = closes.takeLast(50).average()
        val deltas = (1..14).map { closes[closes.lastIndex - it + 1] - closes[closes.lastIndex - it] }
        val averageGain = deltas.filter { it > 0.0 }.sum() / 14.0
        val averageLoss = deltas.filter { it < 0.0 }.sumOf { -it } / 14.0
        val rsi = when {
            averageGain == 0.0 && averageLoss == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
        }

        val alpha = 2.0 / 10.0
        var ema9 = closes.first()
        closes.drop(1).forEach { ema9 = it * alpha + ema9 * (1.0 - alpha) }

        val trueRanges = (1 until candles.size).map { i ->
            val c = candles[i]
            val previous = candles[i - 1].close
            maxOf(c.high - c.low, abs(c.high - previous), abs(c.low - previous))
        }
        val atr14 = trueRanges.takeLast(14).average()
        val last20 = candles.takeLast(20)
        val support20 = last20.minOf { it.low }
        val resistance20 = last20.maxOf { it.high }
        val priorVolumes = candles.dropLast(1).takeLast(20).map { it.volume }.filter { it > 0L }
        val averageVolume20 = priorVolumes.average().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val relativeVolume = if (averageVolume20 > 0.0 && last.volume > 0L) last.volume / averageVolume20 else 0.0
        val change5d = (last.close / candles[candles.size - 6].close - 1.0) * 100.0

        val label: String
        val explanation: String
        when {
            rsi >= 70.0 -> {
                label = "Rozgrzany – ryzyko cofnięcia"
                explanation = "RSI ${rsi.fmt(1)} wskazuje wykupienie. Nie traktuj wysokiego momentum jako automatycznego sygnału kupna."
            }
            last.close > sma20 && sma20 > sma50 && rsi in 50.0..69.9 -> {
                label = "Trend wzrostowy"
                explanation = "Cena > SMA20 > SMA50, EMA9 ${ema9.fmt(2)}, RSI ${rsi.fmt(1)}. Trend dodatni; obserwuj retest i wolumen."
            }
            last.close > sma50 && rsi in 40.0..55.0 -> {
                label = "Możliwy pullback w trendzie"
                explanation = "Cena pozostaje nad SMA50, RSI ${rsi.fmt(1)}. Czekaj na odzyskanie EMA9/SMA20 i potwierdzenie."
            }
            last.close < sma50 && rsi < 45.0 -> {
                label = "Trend słaby"
                explanation = "Cena poniżej SMA50, RSI ${rsi.fmt(1)}. Ryzyko kontynuacji spadku jest podwyższone."
            }
            else -> {
                label = "Neutralnie / bez przewagi"
                explanation = "Brak zgodnego układu średnich i RSI. Lepiej poczekać na potwierdzenie."
            }
        }

        return TechnicalSignal(
            symbol = instrument.symbol, name = instrument.name, market = instrument.market,
            price = last.close, sma20 = sma20, sma50 = sma50, rsi14 = rsi, change5d = change5d,
            averageVolume20 = averageVolume20, latestVolume = last.volume,
            label = label, explanation = explanation, calculatedAt = System.currentTimeMillis(),
            ema9 = ema9, atr14 = atr14, support20 = support20, resistance20 = resistance20,
            relativeVolume = relativeVolume, source = source
        )
    }
}
