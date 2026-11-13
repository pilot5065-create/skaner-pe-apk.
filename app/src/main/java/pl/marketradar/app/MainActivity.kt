package pl.marketradar.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF08111F)
private val Panel = Color(0xFF142137)
private val Panel2 = Color(0xFF1B2B43)
private val Muted = Color(0xFF9DADC2)
private val Green = Color(0xFF41D6A2)
private val Red = Color(0xFFFF7272)
private val Amber = Color(0xFFFFC766)
private val Blue = Color(0xFF75B7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarketNotifications.createChannel(this)
        MarketScheduler.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent { MarketRadarApp(this) }
    }
}

@Composable
fun MarketRadarApp(context: Context) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Radar") }
    var marketFilter by remember { mutableStateOf("WSZYSTKIE") }
    var quotes by remember { mutableStateOf(MarketStore.allQuotes(context)) }
    var technical by remember { mutableStateOf(MarketStore.allTechnical(context)) }
    var news by remember { mutableStateOf(MarketStore.news(context)) }
    var alerts by remember { mutableStateOf(MarketStore.alerts(context)) }
    var holdings by remember { mutableStateOf(MarketStore.loadHoldings(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Dane lokalne. Połącz dostawcę, aby pobrać notowania.") }
    var progress by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var twelveConfigured by remember { mutableStateOf(SecretVault.has(context, "twelve_data_key")) }
    var finnhubConfigured by remember { mutableStateOf(SecretVault.has(context, "finnhub_key")) }
    var addSymbolDialog by remember { mutableStateOf(false) }

    val instruments = DefaultWatchlist.load(context)
    val apiKey = SecretVault.read(context, "twelve_data_key")
    val finnhubKey = SecretVault.read(context, "finnhub_key")

    fun reloadLocal() {
        quotes = MarketStore.allQuotes(context)
        technical = MarketStore.allTechnical(context)
        alerts = MarketStore.alerts(context)
        news = MarketStore.news(context)
        holdings = MarketStore.loadHoldings(context)
    }

    suspend fun updateQuotes() {
        if (apiKey.isBlank() && finnhubKey.isBlank()) {
            status = "Dodaj token Finnhub dla USA albo licencjonowany klucz Twelve Data dla Europy/GPW."
            showSettings = true
            return
        }
        busy = true
        status = "Pobieranie kursów z właściwych źródeł…"
        progress = "Przygotowanie skanera"
        try {
            val done = MarketRefresher.refreshQuotes(context, apiKey, finnhubKey, instruments) { current, total, symbol ->
                withContext(Dispatchers.Main) {
                    progress = "${current} / ${total} • ${symbol}"
                    status = "USA: Finnhub • Europa/GPW: Twelve Data z licencją internal display"
                }
            }
            reloadLocal()
            val count = done.size
            status = "Zaktualizowano ${count} z ${instruments.size} instrumentów. Brak danych może oznaczać brak dostępu lub symbolu u dostawcy."
            progress = "Ostatni skan: ${clock(System.currentTimeMillis())}"
        } catch (e: Exception) {
            status = e.message ?: "Nie udało się pobrać notowań."
        } finally {
            busy = false
        }
    }

    suspend fun updateTechnical() {
        if (apiKey.isBlank()) {
            status = "Dodaj klucz Twelve Data w Ustawieniach."
            showSettings = true
            return
        }
        busy = true
        status = "Liczenie analizy technicznej z dziennych świec…"
        progress = "Pobieranie historii"
        try {
            val done = MarketRefresher.refreshTechnical(context, apiKey, instruments) { current, total, symbol ->
                withContext(Dispatchers.Main) {
                    progress = "$current / $total • $symbol"
                }
            }
            technical = MarketStore.allTechnical(context)
            status = "Obliczono ${done.size} układów technicznych. Dane dzienne; nie jest to sygnał kupna/sprzedaży."
        } catch (e: Exception) {
            status = e.message ?: "Błąd analizy technicznej."
        } finally {
            busy = false
        }
    }

    suspend fun updateNews() {
        if (finnhubKey.isBlank()) {
            status = "Dodaj token Finnhub w Ustawieniach, aby uruchomić wiadomości."
            showSettings = true
            return
        }
        busy = true
        status = "Pobieranie wiadomości rynkowych i spółkowych…"
        try {
            val result = withContext(Dispatchers.IO) { FinnhubClient.news(finnhubKey, instruments) }
            MarketStore.saveNews(context, result)
            news = result
            status = "Pobrano ${result.size} wiadomości. Źródło i czas publikacji są widoczne przy każdym wpisie."
        } catch (e: Exception) {
            status = e.message ?: "Nie udało się pobrać wiadomości."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(twelveConfigured, finnhubConfigured) {
        if ((twelveConfigured || finnhubConfigured) && quotes.isEmpty() && !busy) updateQuotes()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green, secondary = Blue, background = Navy, surface = Panel,
            onBackground = Color.White, onSurface = Color.White,
            surfaceVariant = Panel2, onSurfaceVariant = Muted
        )
    ) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    listOf("Radar", "Sygnały", "News", "Portfel", "Ustawienia").forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(when (item) { "Radar" -> "◉"; "Sygnały" -> "⌁"; "News" -> "▤"; "Portfel" -> "▥"; else -> "⚙" }, fontSize = 18.sp) },
                            label = { Text(item, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Green, selectedTextColor = Green,
                                indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TopHeader(
                    busy = busy,
                    onRefresh = { scope.launch { updateQuotes() } },
                    onSettings = { tab = "Ustawienia" }
                )
                when (tab) {
                    "Radar" -> RadarScreen(
                        quotes = quotes, alerts = alerts, status = status, progress = progress,
                        filter = marketFilter, onFilter = { marketFilter = it },
                        onRefresh = { scope.launch { updateQuotes() } },
                        onAdd = { addSymbolDialog = true },
                        busy = busy
                    )
                    "Sygnały" -> TechnicalScreen(
                        values = technical, busy = busy, status = status,
                        onRefresh = { scope.launch { updateTechnical() } }
                    )
                    "News" -> NewsScreen(
                        items = news, busy = busy, status = status,
                        onRefresh = { scope.launch { updateNews() } },
                        onOpen = { openUrl(context, it) }
                    )
                    "Portfel" -> PortfolioScreen(
                        holdings = holdings, quotes = quotes,
                        onAdd = { holding ->
                            holdings = (holdings.filterNot { it.symbol == holding.symbol } + holding)
                            MarketStore.saveHoldings(context, holdings)
                        },
                        onRemove = { symbol ->
                            holdings = holdings.filterNot { it.symbol == symbol }
                            MarketStore.saveHoldings(context, holdings)
                        }
                    )
                    else -> SettingsScreen(
                        context = context,
                        twelveConfigured = twelveConfigured,
                        finnhubConfigured = finnhubConfigured,
                        busy = busy,
                        status = status,
                        onSave = { twelve, finnhub, threshold ->
                            if (twelve.isNotBlank()) SecretVault.save(context, "twelve_data_key", twelve)
                            if (finnhub.isNotBlank()) SecretVault.save(context, "finnhub_key", finnhub)
                            MarketStore.setThreshold(context, threshold)
                            twelveConfigured = SecretVault.has(context, "twelve_data_key")
                            finnhubConfigured = SecretVault.has(context, "finnhub_key")
                            MarketScheduler.schedule(context)
                            status = "Ustawienia zapisane. Klucze są szyfrowane w Android Keystore."
                        },
                        onRefresh = { scope.launch { updateQuotes() } },
                        onOpen = { openUrl(context, it) }
                    )
                }
            }
        }
        if (addSymbolDialog) {
            AddSymbolDialog(
                onDismiss = { addSymbolDialog = false },
                onAdd = { symbol, market ->
                    val added = DefaultWatchlist.add(context, symbol, market)
                    addSymbolDialog = false
                    status = if (added) "Dodano $symbol do watchlisty. Odśwież notowania." else "Symbol jest nieprawidłowy lub już istnieje."
                }
            )
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Konfiguracja dostawców") },
                text = { Text("Dodaj token Finnhub do notowań USA i wiadomości. Twelve Data jest opcjonalne dla Europy/GPW i historii technicznej — wymagany plan z prawem internal display. Klucze wklej tylko w Ustawieniach aplikacji.") },
                confirmButton = { TextButton(onClick = { showSettings = false; tab = "Ustawienia" }) { Text("Otwórz ustawienia") } }
            )
        }
    }
}

@Composable
private fun TopHeader(busy: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("MARKET RADAR", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text("Terminal inwestycyjny", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onSettings) { Text("USTAW", color = Muted) }
        Button(onClick = onRefresh, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy)) {
            Text(if (busy) "…" else "Skanuj")
        }
    }
}

@Composable
private fun RadarScreen(
    quotes: List<Quote>, alerts: List<RadarAlert>, status: String, progress: String,
    filter: String, onFilter: (String) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit, busy: Boolean
) {
    val shown = quotes.filter { filter == "WSZYSTKIE" || it.market == filter }
    val movers = shown.sortedByDescending { kotlin.math.abs(it.percentChange) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        contentPadding = PaddingValues(bottom = 22.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatTile("INSTRUMENTY", quotes.size.toString(), Blue, Modifier.weight(1f))
                StatTile("RUCH ≥ 3%", shown.count { kotlin.math.abs(it.percentChange) >= 3.0 }.toString(), Amber, Modifier.weight(1f))
                StatTile("ALERTY", alerts.size.toString(), Green, Modifier.weight(1f))
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Text("STATUS SKANERA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(status, color = Color.White, fontSize = 12.sp)
                    if (progress.isNotBlank()) Text(progress, color = Green, fontSize = 11.sp)
                    Text("USA: Finnhub (zakres i opóźnienie zależą od tokenu oraz uprawnień). Europa/GPW: Twelve Data wyłącznie z prawem internal display.", color = Muted, fontSize = 10.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("WSZYSTKIE", "USA", "EUROPA", "GPW").forEach { market ->
                    FilterChip(
                        selected = filter == market,
                        onClick = { onFilter(market) },
                        label = { Text(market, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Navy)
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WATCHLISTA", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("+ Dodaj symbol") }
            }
        }
        if (quotes.isEmpty()) {
            item { EmptyCard("Brak notowań", "Dodaj token Finnhub dla rynku USA. Europa i GPW wymagają Twelve Data z prawem internal display. Nie pokazuję fikcyjnych ani przykładowych cen.") }
        } else {
            items(movers, key = { it.symbol }) { quote ->
                QuoteCard(quote)
            }
        }
        item { Text("OSTATNIE ALERTY", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (alerts.isEmpty()) item { Text("Alert pojawi się po przekroczeniu progu względem poprzedniego odczytu. Pierwszy odczyt ustala punkt odniesienia.", color = Muted, fontSize = 11.sp) }
        items(alerts.take(8), key = { "${it.symbol}-${it.createdAt}" }) { AlertCard(it) }
        item { Text("Alerty są regułami liczbowymi, nie rekomendacją transakcyjną. Cena i zmiana pochodzą z czasu wskazanego przez dostawcę.", color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun StatTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuoteCard(q: Quote) {
    val tint = if (q.percentChange >= 0) Green else Red
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(q.symbol, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${q.name} • ${q.market}", color = Muted, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${q.price.priceFmt()} ${q.currency}", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${if (q.percentChange >= 0) "+" else ""}${q.percentChange.fmt(2)}%", color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SmallMetric("Zmiana", "${if (q.change >= 0) "+" else ""}${q.change.fmt(2)}")
                SmallMetric("High", q.dayHigh.priceFmt())
                SmallMetric("Low", q.dayLow.priceFmt())
                SmallMetric("Wolumen", if (q.volume > 0) q.volume.compact() else "—")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${q.source} • ${q.timestamp}", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("Pobrano ${clock(q.fetchedAt)}", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AlertCard(a: RadarAlert) {
    Surface(color = Panel2, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.level, color = if (a.level == "PILNY") Red else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(clock(a.createdAt), color = Muted, fontSize = 9.sp)
            }
            Text(a.headline, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(a.detail, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TechnicalScreen(values: List<TechnicalSignal>, busy: Boolean, status: String, onRefresh: () -> Unit) {
    val sorted = values.sortedByDescending { signalRank(it.label) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 22.dp)
    ) {
        item {
            Text("RADAR UKŁADÓW TECHNICZNYCH", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("SMA20 / SMA50 • RSI14 • zmiana 5 sesji", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRefresh, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy)) {
                Text(if (busy) "Analizuję…" else "Przelicz z dziennych świec")
            }
            Text(status, color = Muted, fontSize = 10.sp)
        }
        if (values.isEmpty()) {
            item { EmptyCard("Brak analizy", "Pierwsze przeliczenie pobiera 60 dziennych świec dla każdego instrumentu. Wykonuj je najwyżej raz dziennie, aby oszczędzać limity API.") }
        }
        items(sorted, key = { it.symbol }) { signal ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${signal.symbol} • ${signal.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(signal.market, color = Muted, fontSize = 10.sp)
                        }
                        Text(signal.label, color = signalColor(signal.label), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SmallMetric("RSI 14", signal.rsi14.fmt(1))
                        SmallMetric("SMA 20", signal.sma20.priceFmt())
                        SmallMetric("SMA 50", signal.sma50.priceFmt())
                        SmallMetric("5 sesji", "${if (signal.change5d >= 0) "+" else ""}${signal.change5d.fmt(1)}%")
                    }
                    Text(signal.explanation, color = Muted, fontSize = 11.sp)
                    Text("Historia dzienna z Twelve Data • ${clock(signal.calculatedAt)}", color = Muted, fontSize = 9.sp)
                }
            }
        }
        item { Text("Reguły są mechaniczne i opisowe. Nie uwzględniają spreadu, płynności, wyników, wyceny ani Twojej ceny wejścia. Nie są automatycznym sygnałem kupna/sprzedaży.", color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun NewsScreen(items: List<MarketNews>, busy: Boolean, status: String, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 22.dp)
    ) {
        item {
            Text("WIADOMOŚCI I KATALIZATORY", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Finnhub • news ogólnorynkowy i wybrane spółki USA", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRefresh, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy)) {
                Text(if (busy) "Pobieram…" else "Odśwież wiadomości")
            }
            Text(status, color = Muted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpen("https://biznes.pap.pl/") }) { Text("PAP • ESPI / EBI") }
                OutlinedButton(onClick = { onOpen("https://www.sec.gov/edgar/search/") }) { Text("SEC EDGAR") }
            }
            Text("Raporty ESPI/EBI i dokumenty SEC traktuj jako źródło pierwotne. Komentarze prasowe to źródła wtórne.", color = Muted, fontSize = 10.sp)
        }
        if (items.isEmpty()) {
            item { EmptyCard("Brak wiadomości", "Dodaj token Finnhub w Ustawieniach. Raporty polskich spółek możesz też otworzyć przez PAP Biznes • ESPI / EBI.") }
        }
        items(items, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(item.url) },
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.source, color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (item.publishedAt > 0) clock(item.publishedAt * 1000) else "Czas niepodany", color = Muted, fontSize = 9.sp)
                    }
                    Text(item.headline, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (item.related.isNotBlank()) Text("Powiązane: ${item.related}", color = Blue, fontSize = 10.sp)
                    if (item.summary.isNotBlank()) Text(item.summary.take(320), color = Muted, fontSize = 11.sp)
                    Text("Otwórz oryginalny artykuł ↗", color = Blue, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun PortfolioScreen(
    holdings: List<Holding>, quotes: List<Quote>,
    onAdd: (Holding) -> Unit, onRemove: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val quoteMap = quotes.associateBy { it.symbol }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 22.dp)
    ) {
        item {
            Text("PORTFEL I RYZYKO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Wpisz pozycje z XTB / Trade Republic lub innych rachunków. Dane są zapisywane tylko lokalnie na telefonie.", color = Muted, fontSize = 11.sp)
            Button(onClick = { showDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy)) { Text("+ Dodaj pozycję") }
        }
        if (holdings.isEmpty()) item { EmptyCard("Nie dodano pozycji", "Nie wpisuję za Ciebie historycznych udziałów, bo mogły się zmienić. Dodaj aktualne ilości i średnią cenę zakupu.") }
        items(holdings, key = { it.symbol }) { holding ->
            val q = quoteMap[holding.symbol]
            val pl = q?.let { (it.price - holding.averagePrice) * holding.quantity }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(holding.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${holding.quantity.fmt(4)} szt. • średnia ${holding.averagePrice.priceFmt()} ${holding.currency}", color = Muted, fontSize = 10.sp)
                        }
                        TextButton(onClick = { onRemove(holding.symbol) }) { Text("Usuń", color = Red) }
                    }
                    if (q != null && pl != null) {
                        Text("Wartość rynkowa: ${(q.price * holding.quantity).priceFmt()} ${q.currency}", color = Color.White, fontSize = 12.sp)
                        Text("Niezrealizowany wynik: ${if (pl >= 0) "+" else ""}${pl.priceFmt()} ${q.currency}", color = if (pl >= 0) Green else Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    } else Text("Brak aktualnego kursu w watchliście. Dodaj symbol i odśwież skan.", color = Amber, fontSize = 10.sp)
                }
            }
        }
        item { Text("Wynik nie obejmuje prowizji, podatków, przewalutowań ani pozycji CFD/short. Krótkie pozycje wymagają osobnego modelu i nie są tu liczone jako long.", color = Muted, fontSize = 10.sp) }
    }
    if (showDialog) {
        AddHoldingDialog(onDismiss = { showDialog = false }, onAdd = { h -> onAdd(h); showDialog = false })
    }
}

@Composable
private fun AddHoldingDialog(onDismiss: () -> Unit, onAdd: (Holding) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj pozycję") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(symbol, { symbol = it.uppercase(Locale.US) }, label = { Text("Ticker, np. NVDA") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Ilość akcji") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(cost, { cost = it }, label = { Text("Średnia cena zakupu") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("USD", "PLN", "EUR").forEach { c ->
                        FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val qty = quantity.replace(",", ".").toDoubleOrNull()
                val px = cost.replace(",", ".").toDoubleOrNull()
                if (symbol.isNotBlank() && qty != null && qty > 0 && px != null && px > 0) {
                    onAdd(Holding(symbol.trim(), qty, px, currency))
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@Composable
private fun SettingsScreen(
    context: Context, twelveConfigured: Boolean, finnhubConfigured: Boolean,
    busy: Boolean, status: String,
    onSave: (String, String, Double) -> Unit, onRefresh: () -> Unit, onOpen: (String) -> Unit
) {
    var twelve by remember { mutableStateOf("") }
    var finn by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf(MarketStore.threshold(context).toString()) }
    var reveal by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 26.dp)
    ) {
        item {
            Text("USTAWIENIA I DOSTAWCY", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Klucze API są szyfrowane kluczem Android Keystore i nie są wysyłane do mojego repozytorium.", color = Muted, fontSize = 11.sp)
        }
        item {
            ProviderCard(
                name = "Twelve Data — Europa/GPW i historia",
                status = if (twelveConfigured) "Klucz zapisany na urządzeniu" else "Opcjonalny — plan z internal display",
                note = "Do wyświetlania kursów w aplikacji potrzebny jest plan z prawem internal display. Basic Free jest non-display. Grow: globalne akcje/ETF EOD; live Europa wymaga wyższego planu i/lub danych giełdowych.",
                linkLabel = "Załóż konto / pobierz klucz",
                onOpen = { onOpen("https://twelvedata.com/pricing") }
            )
            OutlinedTextField(
                value = twelve, onValueChange = { twelve = it },
                label = { Text(if (twelveConfigured) "Wklej nowy klucz, aby zastąpić zapisany" else "Klucz Twelve Data") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation()
            )
        }
        item {
            ProviderCard(
                name = "Finnhub — USA realtime i wiadomości",
                status = if (finnhubConfigured) "Token zapisany na urządzeniu" else "Główne źródło dla USA — token wymagany",
                note = "Notowania USA i newsy ogólne/spółkowe. Używaj wyłącznie w granicach planu osobistego Finnhub; nie udostępniaj aplikacji ani danych innym osobom.",
                linkLabel = "Dokumentacja / rejestracja",
                onOpen = { onOpen("https://finnhub.io/") }
            )
            OutlinedTextField(
                value = finn, onValueChange = { finn = it },
                label = { Text(if (finnhubConfigured) "Wklej nowy token, aby zastąpić zapisany" else "Token Finnhub") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation()
            )
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Ukryj klucze" else "Pokaż wpisane klucze") }
        }
        item {
            Text("ALERTY CENOWE", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                label = { Text("Próg silnego ruchu (%)") },
                supportingText = { Text("Domyślnie 3%. Drugi próg pilny: 5%.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
            Text("Powiadomienie pojawia się po przekroczeniu progu pomiędzy dwoma kolejnymi odczytami, nie przy pierwszym uruchomieniu.", color = Muted, fontSize = 10.sp)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("HARMONOGRAM", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Priorytet: NVDA, SMCI, AMD i AVGO co 15 minut. Pozostałe 18 instrumentów co 90 minut. Android może opóźniać zadania w tle przez oszczędzanie baterii.", color = Color.White, fontSize = 11.sp)
                    Text("Szacunkowo około 672 zapytań cenowych/dzień przy pełnym harmonogramie. Analiza techniczna pobiera historię oddzielnie i zapisuje cache raz dziennie; ręczne skany zużywają dodatkowe kredyty.", color = Muted, fontSize = 10.sp)
                }
            }
        }
        item {
            Button(
                onClick = {
                    val thresholdValue = threshold.replace(",", ".").toDoubleOrNull()?.coerceIn(1.0, 10.0) ?: 3.0
                    onSave(twelve, finn, thresholdValue)
                    twelve = ""; finn = ""
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy)
            ) { Text("Zapisz konfigurację") }
            OutlinedButton(onClick = onRefresh, enabled = !busy && (twelveConfigured || finnhubConfigured), modifier = Modifier.fillMaxWidth()) { Text("Uruchom skan teraz") }
            Text(status, color = Muted, fontSize = 10.sp)
        }
        item {
            Text("ŹRÓDŁA PIERWOTNE I ZASADY", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("USA: Finnhub (notowania i newsy), dokumenty SEC EDGAR jako źródła pierwotne. Europa/GPW: Twelve Data tylko z licencją internal display; EOD lub live zależnie od planu i dodatków giełdowych. Komunikaty polskich emitentów: ESPI/EBI przez PAP Biznes / oficjalne raporty emitentów.", color = Muted, fontSize = 11.sp)
            Text("Aplikacja nie składa zleceń, nie łączy się z rachunkami maklerskimi i nie zna Twoich rzeczywistych pozycji, dopóki sam ich nie wpiszesz.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ProviderCard(name: String, status: String, note: String, linkLabel: String, onOpen: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(note, color = Muted, fontSize = 10.sp)
            TextButton(onClick = onOpen) { Text(linkLabel) }
        }
    }
}

@Composable
private fun AddSymbolDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("USA") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj instrument") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(symbol, { symbol = it.uppercase(Locale.US) }, label = { Text("Ticker u dostawcy (np. NVDA, SAP:XETR, KRU:WSE)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("USA", "EUROPA", "GPW").forEach { m ->
                        FilterChip(selected = market == m, onClick = { market = m }, label = { Text(m) })
                    }
                }
                Text("Przykład formatu: NVDA lub SAP:XETR albo KRU:WSE. Dostępność symbolu zależy od katalogu dostawcy.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (symbol.isNotBlank()) onAdd(symbol, market) }) { Text("Dodaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(body, color = Muted, fontSize = 11.sp)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}

private fun signalRank(label: String): Int = when {
    label == "Trend wzrostowy" -> 4
    label == "Możliwy pullback w trendzie" -> 3
    label.startsWith("Neutralnie") -> 2
    label.startsWith("Rozgrzany") -> 1
    else -> 0
}

private fun signalColor(label: String): Color = when {
    label == "Trend wzrostowy" -> Green
    label == "Możliwy pullback w trendzie" -> Blue
    label.startsWith("Rozgrzany") -> Amber
    label.startsWith("Trend słaby") -> Red
    else -> Muted
}

private fun Double.priceFmt(): String = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 3
}.format(this)

private fun Long.compact(): String = when {
    this >= 1_000_000 -> "${(this / 1_000_000.0).fmt(1)}M"
    this >= 1_000 -> "${(this / 1_000.0).fmt(1)}k"
    else -> toString()
}

private fun clock(epochMillis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale("pl", "PL")).format(Date(epochMillis))
