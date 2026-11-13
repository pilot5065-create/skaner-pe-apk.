package pl.marketradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Instrument(
    val symbol: String,
    val name: String,
    val market: String,
    val group: String
)

data class Quote(
    val symbol: String,
    val name: String,
    val market: String,
    val currency: String,
    val price: Double,
    val change: Double,
    val percentChange: Double,
    val previousClose: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val volume: Long,
    val averageVolume: Long,
    val timestamp: String,
    val fetchedAt: Long,
    val source: String
)

data class TechnicalSignal(
    val symbol: String,
    val name: String,
    val market: String,
    val price: Double,
    val sma20: Double,
    val sma50: Double,
    val rsi14: Double,
    val change5d: Double,
    val averageVolume20: Double,
    val latestVolume: Long,
    val label: String,
    val explanation: String,
    val calculatedAt: Long
)

data class MarketNews(
    val id: String,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val related: String,
    val publishedAt: Long
)

data class Holding(
    val symbol: String,
    val quantity: Double,
    val averagePrice: Double,
    val currency: String
)

data class RadarAlert(
    val symbol: String,
    val headline: String,
    val detail: String,
    val level: String,
    val createdAt: Long
)

object DefaultWatchlist {
    val instruments = listOf(
        Instrument("NVDA", "NVIDIA", "USA", "AI / półprzewodniki"),
        Instrument("SMCI", "Super Micro Computer", "USA", "AI / serwery"),
        Instrument("AMD", "AMD", "USA", "Półprzewodniki"),
        Instrument("AVGO", "Broadcom", "USA", "Półprzewodniki"),
        Instrument("MRVL", "Marvell Technology", "USA", "Półprzewodniki"),
        Instrument("ORCL", "Oracle", "USA", "Chmura / AI"),
        Instrument("GOOGL", "Alphabet", "USA", "Big Tech"),
        Instrument("AMZN", "Amazon", "USA", "Big Tech"),
        Instrument("QCOM", "Qualcomm", "USA", "Półprzewodniki"),
        Instrument("NOW", "ServiceNow", "USA", "Oprogramowanie"),
        Instrument("SNDK", "SanDisk", "USA", "Pamięci / storage"),
        Instrument("TTWO", "Take-Two Interactive", "USA", "Gaming"),
        Instrument("QQQ", "Invesco Nasdaq 100 ETF", "USA", "ETF / Nasdaq 100"),
        Instrument("SPY", "SPDR S&P 500 ETF", "USA", "ETF / S&P 500"),
        Instrument("SMH", "VanEck Semiconductor ETF", "USA", "ETF / półprzewodniki"),
        Instrument("EEM", "iShares MSCI Emerging Markets ETF", "USA", "ETF / rynki wschodzące"),
        Instrument("SAP:XETR", "SAP", "EUROPA", "Technologia"),
        Instrument("ASML:XAMS", "ASML", "EUROPA", "Półprzewodniki"),
        Instrument("KRU:WSE", "Kruk", "GPW", "Polska / finanse"),
        Instrument("LBW:WSE", "Lubawa", "GPW", "Polska / przemysł"),
        Instrument("PZU:WSE", "PZU", "GPW", "Polska / finanse"),
        Instrument("PEO:WSE", "Bank Pekao", "GPW", "Polska / banki")
    )

    fun load(context: Context): List<Instrument> {
        val raw = context.getSharedPreferences("market_radar", Context.MODE_PRIVATE)
            .getString("watchlist", null) ?: return instruments
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Instrument(
                    o.optString("symbol"),
                    o.optString("name"),
                    o.optString("market"),
                    o.optString("group")
                ).takeIf { it.symbol.isNotBlank() }
            }
        } catch (_: Exception) { instruments }
    }

    fun save(context: Context, values: List<Instrument>) {
        val arr = JSONArray()
        values.forEach { item ->
            arr.put(JSONObject().put("symbol", item.symbol).put("name", item.name)
                .put("market", item.market).put("group", item.group))
        }
        context.getSharedPreferences("market_radar", Context.MODE_PRIVATE)
            .edit().putString("watchlist", arr.toString()).apply()
    }

    fun add(context: Context, symbolInput: String, market: String = "USA"): Boolean {
        val symbol = symbolInput.trim().uppercase(Locale.US)
        if (symbol.isBlank() || symbol.length > 24 || symbol.any { !(it.isLetterOrDigit() || it in ".:-_") }) return false
        val current = load(context).toMutableList()
        if (current.any { it.symbol.equals(symbol, true) }) return false
        current.add(Instrument(symbol, symbol, market, "Dodany ręcznie"))
        save(context, current)
        return true
    }

    fun remove(context: Context, symbol: String) {
        save(context, load(context).filterNot { it.symbol == symbol })
    }
}

object MarketStore {
    private fun prefs(context: Context) =
        context.getSharedPreferences("market_radar", Context.MODE_PRIVATE)

    fun threshold(context: Context): Double = prefs(context).getString("alert_threshold", "3.0")?.toDoubleOrNull()?.coerceIn(1.0, 10.0) ?: 3.0
    fun setThreshold(context: Context, value: Double) { prefs(context).edit().putString("alert_threshold", value.coerceIn(1.0, 10.0).toString()).apply() }

    fun quote(context: Context, symbol: String): Quote? {
        val raw = prefs(context).getString("quote_$symbol", null) ?: return null
        return try { quoteFromJson(JSONObject(raw)) } catch (_: Exception) { null }
    }

    fun allQuotes(context: Context): List<Quote> =
        DefaultWatchlist.load(context).mapNotNull { quote(context, it.symbol) }

    fun saveQuote(context: Context, q: Quote) {
        prefs(context).edit().putString("quote_${q.symbol}", quoteJson(q).toString()).apply()
    }

    fun saveTechnical(context: Context, s: TechnicalSignal) {
        prefs(context).edit().putString("tech_${s.symbol}", technicalJson(s).toString()).apply()
    }

    fun allTechnical(context: Context): List<TechnicalSignal> =
        DefaultWatchlist.load(context).mapNotNull { item ->
            val raw = prefs(context).getString("tech_${item.symbol}", null) ?: return@mapNotNull null
            try { technicalFromJson(JSONObject(raw)) } catch (_: Exception) { null }
        }

    fun technicalDate(context: Context, symbol: String): String? =
        prefs(context).getString("tech_date_$symbol", null)

    fun setTechnicalDate(context: Context, symbol: String, date: String) {
        prefs(context).edit().putString("tech_date_$symbol", date).apply()
    }

    fun saveNews(context: Context, news: List<MarketNews>) {
        val arr = JSONArray()
        news.forEach { arr.put(newsJson(it)) }
        prefs(context).edit().putString("news", arr.toString()).apply()
    }

    fun news(context: Context): List<MarketNews> {
        val raw = prefs(context).getString("news", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try { newsFromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }.sortedByDescending { it.publishedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun addAlert(context: Context, alert: RadarAlert) {
        val current = alerts(context).toMutableList()
        current.add(0, alert)
        val arr = JSONArray()
        current.take(80).forEach { arr.put(alertJson(it)) }
        prefs(context).edit().putString("alerts", arr.toString()).apply()
    }

    fun alerts(context: Context): List<RadarAlert> {
        val raw = prefs(context).getString("alerts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    RadarAlert(o.optString("symbol"), o.optString("headline"), o.optString("detail"),
                        o.optString("level"), o.optLong("createdAt"))
                } catch (_: Exception) { null }
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    fun lastAlertKey(context: Context, symbol: String): String? = prefs(context).getString("alert_key_$symbol", null)
    fun setLastAlertKey(context: Context, symbol: String, key: String) { prefs(context).edit().putString("alert_key_$symbol", key).apply() }

    fun loadHoldings(context: Context): List<Holding> {
        val raw = prefs(context).getString("holdings", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Holding(o.optString("symbol"), o.optDouble("quantity"), o.optDouble("averagePrice"), o.optString("currency"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveHoldings(context: Context, values: List<Holding>) {
        val arr = JSONArray()
        values.forEach { h ->
            arr.put(JSONObject().put("symbol", h.symbol).put("quantity", h.quantity)
                .put("averagePrice", h.averagePrice).put("currency", h.currency))
        }
        prefs(context).edit().putString("holdings", arr.toString()).apply()
    }

    private fun quoteJson(q: Quote) = JSONObject()
        .put("symbol", q.symbol).put("name", q.name).put("market", q.market).put("currency", q.currency)
        .put("price", q.price).put("change", q.change).put("percentChange", q.percentChange)
        .put("previousClose", q.previousClose).put("dayHigh", q.dayHigh).put("dayLow", q.dayLow)
        .put("volume", q.volume).put("averageVolume", q.averageVolume).put("timestamp", q.timestamp)
        .put("fetchedAt", q.fetchedAt).put("source", q.source)

    private fun quoteFromJson(o: JSONObject) = Quote(
        o.optString("symbol"), o.optString("name"), o.optString("market"), o.optString("currency"),
        o.optDouble("price"), o.optDouble("change"), o.optDouble("percentChange"),
        o.optDouble("previousClose"), o.optDouble("dayHigh"), o.optDouble("dayLow"),
        o.optLong("volume"), o.optLong("averageVolume"), o.optString("timestamp"),
        o.optLong("fetchedAt"), o.optString("source")
    )

    private fun technicalJson(s: TechnicalSignal) = JSONObject()
        .put("symbol", s.symbol).put("name", s.name).put("market", s.market).put("price", s.price)
        .put("sma20", s.sma20).put("sma50", s.sma50).put("rsi14", s.rsi14).put("change5d", s.change5d)
        .put("averageVolume20", s.averageVolume20).put("latestVolume", s.latestVolume)
        .put("label", s.label).put("explanation", s.explanation).put("calculatedAt", s.calculatedAt)

    private fun technicalFromJson(o: JSONObject) = TechnicalSignal(
        o.optString("symbol"), o.optString("name"), o.optString("market"), o.optDouble("price"),
        o.optDouble("sma20"), o.optDouble("sma50"), o.optDouble("rsi14"), o.optDouble("change5d"),
        o.optDouble("averageVolume20"), o.optLong("latestVolume"), o.optString("label"),
        o.optString("explanation"), o.optLong("calculatedAt")
    )

    private fun newsJson(n: MarketNews) = JSONObject()
        .put("id", n.id).put("headline", n.headline).put("summary", n.summary)
        .put("source", n.source).put("url", n.url).put("related", n.related).put("publishedAt", n.publishedAt)

    private fun newsFromJson(o: JSONObject) = MarketNews(
        o.optString("id"), o.optString("headline"), o.optString("summary"), o.optString("source"),
        o.optString("url"), o.optString("related"), o.optLong("publishedAt")
    )

    private fun alertJson(a: RadarAlert) = JSONObject()
        .put("symbol", a.symbol).put("headline", a.headline).put("detail", a.detail)
        .put("level", a.level).put("createdAt", a.createdAt)
}
