package pl.marketradar.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The user explicitly asked to bundle their own EODHD backup token.
 * An obfuscated token embedded in an APK is not a fully secure server-side secret.
 */
object BundledEodhdKey {
    private const val PREF_NAME = "eodhd_api_key"

    fun ensure(context: Context) {
        if (SecretVault.read(context, PREF_NAME).isNotBlank()) return
        val encoded = BuildConfig.EODHD_API_KEY_B64
        if (encoded.isBlank()) return
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        val buildSecret = String(decoded, Charsets.UTF_8)
        if (buildSecret.isNotBlank()) SecretVault.save(context, PREF_NAME, buildSecret)
    }

    fun read(context: Context): String {
        ensure(context)
        return SecretVault.read(context, PREF_NAME)
    }
}

data class EodhdQuota(val usedCalls: Int, val dailyLimit: Int, val extraCalls: Int, val remainingCalls: Int)

object EodhdClient {
    private const val BASE = "https://eodhd.com/api"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun symbol(instrument: Instrument): String {
        val raw = instrument.symbol.substringBefore(":")
        val exchange = when {
            instrument.market == "GPW" -> "WAR"
            instrument.symbol.endsWith(":XETR") -> "XETRA"
            instrument.symbol.endsWith(":XAMS") -> "AS"
            instrument.market == "EUROPA" -> "XETRA"
            else -> "US"
        }
        return "$raw.$exchange"
    }

    private fun request(path: String, params: Map<String, String>, apiKey: String): String {
        val query = (params + ("api_token" to apiKey)).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val connection = (URL("$BASE$path?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketRadar-Android/1.3 personal-use")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                val reason = when (code) {
                    401 -> "nieprawidłowy klucz EODHD (HTTP 401)"
                    402 -> "wyczerpany dzienny limit EODHD (HTTP 402)"
                    403 -> "plan EODHD nie obejmuje tego zasobu (HTTP 403)"
                    429 -> "limit zapytań na minutę EODHD (HTTP 429)"
                    else -> "EODHD HTTP $code"
                }
                throw IllegalStateException(if (detail.isBlank()) reason else "$reason: $detail")
            }
            return body
        } finally { connection.disconnect() }
    }

    fun usage(apiKey: String): EodhdQuota {
        val json = JSONObject(request("/user", mapOf("fmt" to "json"), apiKey))
        val limit = json.intValue("dailyRateLimit")
        val usageDate = json.optString("apiRequestsDate", "")
        // EODHD resets its counter lazily: the first request after midnight GMT triggers the new day.
        val used = if (usageDate == LocalDate.now(ZoneOffset.UTC).toString()) json.intValue("apiRequests") else 0
        val extra = json.intValue("extraLimit")
        if (limit <= 0) throw IllegalStateException("EODHD nie zwrócił limitu dziennego dla tego klucza.")
        return EodhdQuota(used, limit, extra, (limit - used).coerceAtLeast(0) + extra.coerceAtLeast(0))
    }

    fun quote(apiKey: String, instrument: Instrument): Quote {
        val ticker = symbol(instrument)
        val json = JSONObject(request("/real-time/$ticker", mapOf("fmt" to "json"), apiKey))
        val price = json.numberValue("close") ?: json.numberValue("price")
            ?: throw IllegalStateException("EODHD nie zwrócił bieżącej ceny dla $ticker.")
        val previous = json.numberValue("previousClose") ?: price
        val change = json.numberValue("change") ?: (price - previous)
        val percent = json.numberValue("change_p") ?: if (previous != 0.0) change / previous * 100.0 else 0.0
        val timestamp = json.longValue("timestamp")
        val currency = when (instrument.market) { "GPW" -> "PLN"; "EUROPA" -> "EUR"; else -> "USD" }
        return Quote(
            instrument.symbol, instrument.name, instrument.market, currency, price, change, percent, previous,
            json.numberValue("high") ?: price, json.numberValue("low") ?: price,
            json.longValue("volume"), 0L,
            if (timestamp > 0L) Instant.ofEpochSecond(timestamp).toString() else "czas dostawcy niepodany",
            System.currentTimeMillis(), "EODHD (notowanie opóźnione)"
        )
    }

    fun technical(apiKey: String, instrument: Instrument): TechnicalSignal {
        val ticker = symbol(instrument)
        val array = JSONArray(request("/eod/$ticker", mapOf(
            "fmt" to "json", "period" to "d", "order" to "a",
            "from" to LocalDate.now().minusDays(180).toString()
        ), apiKey))
        val candles = mutableListOf<DailyCandle>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val close = row.numberValue("close") ?: continue
            val high = row.numberValue("high") ?: close
            val low = row.numberValue("low") ?: close
            candles.add(DailyCandle(row.optString("date", i.toString().padStart(5, '0')), high, low, close, row.longValue("volume")))
        }
        return TechnicalCalculator.calculate(instrument, candles, "EODHD • dane dzienne")
    }

    private fun JSONObject.numberValue(key: String): Double? =
        optString(key, "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    private fun JSONObject.intValue(key: String): Int =
        optString(key, "").toDoubleOrNull()?.toInt() ?: 0
    private fun JSONObject.longValue(key: String): Long =
        optString(key, "").toDoubleOrNull()?.toLong() ?: 0L
}

/** 30 manually initiated quote scans per local calendar day; background jobs do not consume this quota. */
object ManualScanQuota {
    const val DAILY_LIMIT = 30
    private const val PREFS = "market_radar"
    private const val DATE_KEY = "manual_scan_date"
    private const val USED_KEY = "manual_scan_used"
    private fun today() = LocalDate.now().toString()

    fun used(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(DATE_KEY, "") == today()) prefs.getInt(USED_KEY, 0).coerceIn(0, DAILY_LIMIT) else 0
    }

    fun remaining(context: Context): Int = (DAILY_LIMIT - used(context)).coerceAtLeast(0)

    fun consume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = today()
        val current = if (prefs.getString(DATE_KEY, "") == today) prefs.getInt(USED_KEY, 0) else 0
        if (current >= DAILY_LIMIT) return false
        prefs.edit().putString(DATE_KEY, today).putInt(USED_KEY, current + 1).apply()
        return true
    }

    fun canConsume(used: Int): Boolean = used < DAILY_LIMIT
}
