package pl.marketradar.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretVault {
    private const val KEY_ALIAS = "market_radar_api_key_v1"
    private const val PREFS = "market_radar_secrets"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, name: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context, name: String): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 12) return ""
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun has(context: Context, name: String) = read(context, name).isNotBlank()
}

object TwelveDataClient {
    private const val BASE = "https://api.twelvedata.com"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun getJson(path: String, params: Map<String, String>, apiKey: String): JSONObject {
        val query = (params + ("apikey" to apiKey)).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val connection = (URL("$BASE$path?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketRadar-Android/1.0 personal-use")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) {
                throw IllegalStateException("Twelve Data zwrócił nieczytelną odpowiedź (HTTP $code).")
            }
            if (code !in 200..299 || json.optString("status") == "error" || json.has("code") && json.has("message")) {
                val message = json.optString("message", "Błąd API")
                val hint = when (json.optInt("code", code)) {
                    401 -> " Sprawdź klucz Twelve Data."
                    429 -> " Limit zapytań został osiągnięty. Zmniejsz częstotliwość odświeżania."
                    else -> ""
                }
                throw IllegalStateException("Twelve Data: $message$hint")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun quote(apiKey: String, instrument: Instrument): Quote {
        val json = getJson("/quote", mapOf("symbol" to instrument.symbol), apiKey)
        val price = json.number("close") ?: json.number("price")
            ?: throw IllegalStateException("Brak kursu dla ${instrument.symbol}: ${json.optString("message", "brak danych")}")
        val timestamp = json.optString("datetime").ifBlank {
            json.optLong("timestamp").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() } ?: "czas dostawcy niepodany"
        }
        return Quote(
            symbol = instrument.symbol,
            name = instrument.name,
            market = instrument.market,
            currency = json.optString("currency", if (instrument.market == "GPW") "PLN" else "USD"),
            price = price,
            change = json.number("change") ?: 0.0,
            percentChange = json.number("percent_change") ?: 0.0,
            previousClose = json.number("previous_close") ?: 0.0,
            dayHigh = json.number("high") ?: 0.0,
            dayLow = json.number("low") ?: 0.0,
            volume = json.longNumber("volume"),
            averageVolume = json.longNumber("average_volume"),
            timestamp = timestamp,
            fetchedAt = System.currentTimeMillis(),
            source = "Twelve Data"
        )
    }

    fun technical(apiKey: String, instrument: Instrument): TechnicalSignal {
        val json = getJson(
            "/time_series",
            mapOf("symbol" to instrument.symbol, "interval" to "1day", "outputsize" to "60", "order" to "desc"),
            apiKey
        )
        val values = json.optJSONArray("values") ?: throw IllegalStateException("Brak historii dla ${instrument.symbol}")
        val closes = mutableListOf<Double>()
        val volumes = mutableListOf<Long>()
        for (i in 0 until values.length()) {
            val candle = values.optJSONObject(i) ?: continue
            val close = candle.number("close") ?: continue
            closes.add(close)
            volumes.add(candle.longNumber("volume"))
        }
        if (closes.size < 50) throw IllegalStateException("Dostawca zwrócił mniej niż 50 świec — nie można rzetelnie obliczyć SMA50 dla ${instrument.symbol}.")
        val sma20 = closes.take(20).average()
        val sma50 = closes.take(50).average()
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in 0 until minOf(14, closes.size - 1)) {
            val delta = closes[i] - closes[i + 1]
            if (delta >= 0) gains.add(delta) else losses.add(-delta)
        }
        val avgGain = gains.sum() / 14.0
        val avgLoss = losses.sum() / 14.0
        val rsi = when {
            avgLoss == 0.0 && avgGain == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        }
        val change5d = if (closes.size > 5 && closes[5] != 0.0) (closes[0] / closes[5] - 1.0) * 100.0 else 0.0
        val avgVol = volumes.drop(1).take(20).filter { it > 0 }.average().takeIf { it.isFinite() } ?: 0.0
        val currentVol = volumes.firstOrNull() ?: 0L
        val price = closes.first()
        val label: String
        val explanation: String
        when {
            rsi >= 70.0 -> {
                label = "Rozgrzany – ryzyko cofnięcia"
                explanation = "RSI ${rsi.fmt(1)}. Rynek jest wykupiony; nie traktuj momentum jako automatycznego sygnału kupna."
            }
            price > sma20 && sma20 > sma50 && rsi in 50.0..69.9 -> {
                label = "Trend wzrostowy"
                explanation = "Cena > SMA20 > SMA50, RSI ${rsi.fmt(1)}. Trend jest dodatni, ale potrzebne jest zarządzanie ryzykiem."
            }
            price > sma50 && rsi in 40.0..55.0 -> {
                label = "Możliwy pullback w trendzie"
                explanation = "Cena pozostaje nad SMA50, RSI ${rsi.fmt(1)}. Obserwuj potwierdzenie odbicia; sam układ nie wystarcza do wejścia."
            }
            price < sma50 && rsi < 45.0 -> {
                label = "Trend słaby"
                explanation = "Cena poniżej SMA50, RSI ${rsi.fmt(1)}. Ryzyko kontynuacji spadku pozostaje podwyższone."
            }
            else -> {
                label = "Neutralnie / bez przewagi"
                explanation = "Brak zgodnego układu średnich i RSI. Lepiej czekać na potwierdzenie."
            }
        }
        return TechnicalSignal(
            instrument.symbol, instrument.name, instrument.market, price, sma20, sma50, rsi,
            change5d, avgVol, currentVol, label, explanation, System.currentTimeMillis()
        )
    }

    private fun JSONObject.number(key: String): Double? {
        val raw = optString(key, "").trim()
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun JSONObject.longNumber(key: String): Long {
        val raw = optString(key, "").trim()
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
    }
}

object FinnhubClient {
    private const val BASE = "https://finnhub.io/api/v1"

    fun quote(token: String, instrument: Instrument): Quote {
        val url = URL("$BASE/quote?symbol=${URLEncoder.encode(instrument.symbol, "UTF-8")}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Finnhub-Token", token)
            setRequestProperty("User-Agent", "MarketRadar-Android/1.0 personal-use")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = when (code) {
                    401 -> "Nieprawidłowy token Finnhub."
                    403 -> "Finnhub nie udostępnia tego instrumentu na Twoim planie."
                    429 -> "Limit zapytań Finnhub. Spróbuj później."
                    else -> "Finnhub HTTP $code."
                }
                throw IllegalStateException(message)
            }
            val json = JSONObject(body)
            val price = json.number("c") ?: throw IllegalStateException("Brak kursu Finnhub dla ${instrument.symbol}.")
            if (price <= 0.0) throw IllegalStateException("Finnhub nie zwrócił aktualnego kursu dla ${instrument.symbol}.")
            val epoch = json.optLong("t")
            return Quote(
                symbol = instrument.symbol,
                name = instrument.name,
                market = instrument.market,
                currency = "USD",
                price = price,
                change = json.number("d") ?: 0.0,
                percentChange = json.number("dp") ?: 0.0,
                previousClose = json.number("pc") ?: 0.0,
                dayHigh = json.number("h") ?: 0.0,
                dayLow = json.number("l") ?: 0.0,
                volume = 0L,
                averageVolume = 0L,
                timestamp = if (epoch > 0) Instant.ofEpochSecond(epoch).toString() else "czas dostawcy niepodany",
                fetchedAt = System.currentTimeMillis(),
                source = "Finnhub"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun getArray(path: String, params: Map<String, String>, token: String): JSONArray {
        val query = (params + ("token" to token)).entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val connection = (URL("$BASE$path?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MarketRadar-Android/1.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = if (code == 401) "Sprawdź token Finnhub." else if (code == 429) "Limit Finnhub – spróbuj później." else "HTTP $code"
                throw IllegalStateException("Finnhub: $hint")
            }
            return JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    fun news(token: String, instruments: List<Instrument>): List<MarketNews> {
        val items = LinkedHashMap<String, MarketNews>()
        val general = getArray("/news", mapOf("category" to "general"), token)
        addNews(items, general)
        val from = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString()
        val to = LocalDate.now(ZoneOffset.UTC).toString()
        instruments.filter { it.market == "USA" && it.group !in listOf("ETF / Nasdaq 100", "ETF / S&P 500", "ETF / półprzewodniki", "ETF / rynki wschodzące") }
            .take(6).forEach { instrument ->
                try {
                    val arr = getArray("/company-news", mapOf("symbol" to instrument.symbol, "from" to from, "to" to to), token)
                    addNews(items, arr)
                } catch (_: Exception) {
                    // A single symbol's news entitlement must not block the rest of the feed.
                }
            }
        return items.values.sortedByDescending { it.publishedAt }.take(80)
    }

    private fun addNews(map: MutableMap<String, MarketNews>, arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val headline = o.optString("headline").trim()
            val url = o.optString("url").trim()
            if (headline.isBlank() || !url.startsWith("https://")) continue
            val id = o.optLong("id").toString().takeIf { it != "0" } ?: url
            map[id] = MarketNews(
                id,
                headline,
                o.optString("summary"),
                o.optString("source", "Finnhub"),
                url,
                o.optString("related"),
                o.optLong("datetime")
            )
        }
    }
}

private fun JSONObject.number(key: String): Double? {
    val raw = optString(key, "").trim()
    return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun JSONObject.longNumber(key: String): Long {
    val raw = optString(key, "").trim()
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
}

fun Double.fmt(digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", this)
