package pl.marketradar.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object MarketScheduler {
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Four highest-volatility AI/semiconductor names receive a more frequent scan.
        val fast = PeriodicWorkRequestBuilder<MarketFastScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(
            "market_radar_priority_15m", ExistingPeriodicWorkPolicy.KEEP, fast
        )

        // The remainder of the watchlist is scanned less often to respect the free API quota.
        val broad = PeriodicWorkRequestBuilder<MarketScanWorker>(90, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(
            "market_radar_broad_90m", ExistingPeriodicWorkPolicy.KEEP, broad
        )

        // News feed refresh runs hourly; headlines remain source-linked in the app.
        val news = PeriodicWorkRequestBuilder<MarketNewsWorker>(60, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(
            "market_radar_news_60m", ExistingPeriodicWorkPolicy.KEEP, news
        )
    }
}

object MarketNotifications {
    private const val CHANNEL_ID = "market_radar_alerts"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alerty rynkowe", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Powiadomienia o silnych ruchach cen monitorowanych instrumentów"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun post(context: Context, title: String, message: String, id: Int) {
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

object MarketRefresher {
    suspend fun refreshQuotes(
        context: Context,
        twelveDataKey: String,
        finnhubKey: String,
        instruments: List<Instrument>,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<Quote> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Quote>()
        instruments.forEachIndexed { index, instrument ->
            try {
                val old = MarketStore.quote(context, instrument.symbol)
                val quote = try {
                    // Yahoo Finance is the default no-key fallback for the whole watchlist.
                    YahooFinanceClient.quote(instrument)
                } catch (yahooError: Exception) {
                    when {
                        instrument.market == "USA" && finnhubKey.isNotBlank() ->
                            FinnhubClient.quote(finnhubKey, instrument)
                        twelveDataKey.isNotBlank() ->
                            TwelveDataClient.quote(twelveDataKey, instrument)
                        else -> throw IllegalStateException("Yahoo Finance: ${yahooError.message ?: "brak danych"}. Brak dodatkowego źródła dla tego instrumentu; dodaj token Finnhub (USA) lub licencjonowany Twelve Data (Europa/GPW).")
                    }
                }
                MarketStore.saveQuote(context, quote)
                results.add(quote)
                evaluateAlerts(context, old, quote)
                onProgress(index + 1, instruments.size, instrument.symbol)
            } catch (e: Exception) {
                onProgress(index + 1, instruments.size, "${instrument.symbol}: ${e.message ?: "błąd danych"}")
            }
        }
        results
    }

    suspend fun refreshTechnical(
        context: Context,
        apiKey: String,
        instruments: List<Instrument>,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<TechnicalSignal> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().toString()
        val results = mutableListOf<TechnicalSignal>()
        instruments.forEachIndexed { index, instrument ->
            if (MarketStore.technicalDate(context, instrument.symbol) == today) {
                MarketStore.allTechnical(context).firstOrNull { it.symbol == instrument.symbol }?.let { results.add(it) }
                onProgress(index + 1, instruments.size, "${instrument.symbol}: cache dzienny")
                return@forEachIndexed
            }
            try {
                val signal = try {
                    YahooFinanceClient.technical(instrument)
                } catch (yahooError: Exception) {
                    if (apiKey.isNotBlank()) TwelveDataClient.technical(apiKey, instrument)
                    else throw IllegalStateException("Yahoo Finance: ${yahooError.message ?: "brak historii"}. Nie ma klucza alternatywnego dostawcy.")
                }
                MarketStore.saveTechnical(context, signal)
                MarketStore.setTechnicalDate(context, instrument.symbol, today)
                results.add(signal)
                onProgress(index + 1, instruments.size, instrument.symbol)
            } catch (e: Exception) {
                onProgress(index + 1, instruments.size, "${instrument.symbol}: ${e.message ?: "brak historii"}")
            }
            if (index < instruments.lastIndex) delay(7_700L)
        }
        results
    }

    private fun evaluateAlerts(context: Context, old: Quote?, current: Quote) {
        if (old == null) return
        val threshold = MarketStore.threshold(context)
        val levels = listOf(threshold, 5.0).distinct().sorted()
        levels.forEach { level ->
            if (!MarketRules.crossesAlert(old.percentChange, current.percentChange, level)) return@forEach
            val direction = if (current.percentChange >= 0) "wzrost" else "spadek"
            val severity = if (level >= 5.0) "PILNY" else "SILNY RUCH"
            val key = "${LocalDate.now()}|${level}|$direction"
            if (MarketStore.lastAlertKey(context, current.symbol) == key) return@forEach
            val detail = "${current.symbol}: ${if (current.percentChange >= 0) "+" else ""}${current.percentChange.fmt(2)}% od poprzedniego zamknięcia. Kurs ${current.price.fmt(2)} ${current.currency}. Źródło: ${current.source}, czas źródłowy: ${current.timestamp}."
            MarketStore.addAlert(context, RadarAlert(current.symbol, "$severity: ${current.name}", detail, severity, System.currentTimeMillis()))
            MarketStore.setLastAlertKey(context, current.symbol, key)
            MarketNotifications.post(
                context, "$severity • ${current.symbol}",
                "${current.name}: ${if (current.percentChange >= 0) "+" else ""}${current.percentChange.fmt(2)}% (próg ${level.fmt(1)}%).",
                current.symbol.hashCode() + level.toInt()
            )
        }
    }
}

private val PRIORITY_SYMBOLS = setOf("NVDA", "SMCI", "AMD", "AVGO")

class MarketFastScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val twelveKey = SecretVault.read(applicationContext, "twelve_data_key")
        val finnhubKey = SecretVault.read(applicationContext, "finnhub_key")
        // Yahoo Finance requires no API key; always run the free fallback scanner.
        return try {
            val priority = DefaultWatchlist.load(applicationContext).filter { it.symbol in PRIORITY_SYMBOLS }
            MarketRefresher.refreshQuotes(applicationContext, twelveKey, finnhubKey, priority)
            applicationContext.getSharedPreferences("market_radar", Context.MODE_PRIVATE)
                .edit().putLong("last_priority_scan", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class MarketScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val twelveKey = SecretVault.read(applicationContext, "twelve_data_key")
        val finnhubKey = SecretVault.read(applicationContext, "finnhub_key")
        if (twelveKey.isBlank() && finnhubKey.isBlank()) return Result.success()
        return try {
            val rest = DefaultWatchlist.load(applicationContext).filterNot { it.symbol in PRIORITY_SYMBOLS }
            MarketRefresher.refreshQuotes(applicationContext, twelveKey, finnhubKey, rest)
            applicationContext.getSharedPreferences("market_radar", Context.MODE_PRIVATE)
                .edit().putLong("last_background_scan", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}


class MarketNewsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val token = SecretVault.read(applicationContext, "finnhub_key")
        if (token.isBlank()) return Result.success()
        return try {
            val news = withContext(Dispatchers.IO) {
                FinnhubClient.news(token, DefaultWatchlist.load(applicationContext))
            }
            MarketStore.saveNews(applicationContext, news)
            applicationContext.getSharedPreferences("market_radar", Context.MODE_PRIVATE)
                .edit().putLong("last_news_scan", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
