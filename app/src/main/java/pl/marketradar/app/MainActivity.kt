package pl.marketradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF0B1220)
private val Panel = Color(0xFF151F31)
private val Muted = Color(0xFF9AA9BD)
private val Green = Color(0xFF36D399)
private val Amber = Color(0xFFF6C453)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarketRadarApp() }
    }
}

@Composable
fun MarketRadarApp() {
    var tab by remember { mutableStateOf("Radar") }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Navy, surface = Panel, primary = Green,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Navy, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Radar", "USA", "Europa", "GPW").forEach {
                    NavigationBarItem(selected = tab == it, onClick = { tab = it },
                        icon = {}, label = { Text(it) })
                }
            }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("MARKET RADAR", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Wczesne ostrzeganie rynkowe", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Prywatny terminal inwestycyjny • wersja 0.1.0", color = Muted, fontSize = 12.sp)
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("STATUS SYSTEMU", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Interfejs demonstracyjny", color = Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Notowania i wiadomości na żywo nie są jeszcze podłączone. Nie używaj poniższych elementów jako sygnałów inwestycyjnych.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                item { SectionTitle("OBSZARY MONITORINGU") }
                item { MarketCard("USA", "NASDAQ • NYSE • S&P 500", "Oczekuje na dostawcę danych") }
                item { MarketCard("EUROPA", "DAX • STOXX 600 • CAC 40", "Oczekuje na dostawcę danych") }
                item { MarketCard("POLSKA", "WIG20 • mWIG40 • sWIG80", "KNF / ESPI – integracja do wykonania") }
                item { SectionTitle("MODUŁY ANALITYCZNE") }
                item { MarketCard("Anomalie wolumenu", "Wykrywanie nietypowej aktywności", "Do podłączenia") }
                item { MarketCard("Wiadomości i katalizatory", "Źródło, czas publikacji, wpływ", "Do podłączenia") }
                item { MarketCard("Sygnały transakcyjne", "Wejście • stop • cele • ryzyko", "Wymaga danych i walidacji") }
                item {
                    Text("Zasada bezpieczeństwa", color = Green, fontWeight = FontWeight.Bold)
                    Text("Każdy przyszły alert będzie zawierał źródło, znacznik czasu, opóźnienie danych i poziom pewności. Aplikacja nie gwarantuje przewidywania gwałtownych ruchów.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
@Composable private fun MarketCard(title: String, subtitle: String, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(status, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
