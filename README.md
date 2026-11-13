# Market Radar — prywatny skaner inwestycyjny

Prywatna aplikacja Android do monitorowania watchlisty. Nie składa zleceń i nie łączy się z rachunkami maklerskimi.

## Bezpłatne źródła notowań
1. **Yahoo Finance Chart API** — domyślne źródło bez klucza API. Aplikacja pobiera notowania i dzienne świece dla USA, wybranych rynków europejskich i GPW (mapowanie tickerów: SAP.DE, ASML.AS, KRU.WA itd.). To nieoficjalny endpoint używany przez serwis Yahoo Finance, niepublicznie udokumentowany jako stabilne API. Może zmienić format, ograniczyć zapytania lub przestać odpowiadać. Nie traktuj go jako gwarantowanego feedu giełdowego.
2. **Finnhub Free** — opcjonalne źródło zapasowe dla USA i wiadomości; wymaga darmowego tokenu. Plan bezpłatny ma limity i zakres osobistego użytku. Nie zakładaj, że zapewnia historyczne świece.
3. **Twelve Data** — opcjonalny fallback. Pokazywanie danych w aplikacji wymaga odpowiedniego prawa internal display i uprawnień giełdowych. Darmowy Basic jest non-display, więc nie może być użyty do wyświetlania kursów.

Dane z Yahoo mają status informacyjny i mogą być opóźnione lub nieaktualne. Przy błędzie Yahoo aplikacja spróbuje Finnhub (USA), a następnie Twelve Data (jeśli skonfigurowane). Nie scala ani nie uśrednia kursów z różnych źródeł; każdy odczyt zachowuje nazwę dostawcy i czas źródłowy.

## Funkcje
- Watchlista: USA, Europa i GPW; AI/półprzewodniki, Big Tech, ETF-y, Kruk, Lubawa, PZU i Pekao.
- Skan ręczny bez klucza API; automatyczny skan priorytetowych NVDA, SMCI, AMD i AVGO co 15 minut oraz reszty watchlisty co 90 minut (WorkManager może opóźnić wykonanie).
- Alerty po przekroczeniu progów zmiany procentowej.
- Analiza techniczna z dziennych świec Yahoo: SMA20, SMA50, RSI14, zmiana 5 sesji. Wymaga co najmniej 50 poprawnych świec.
- Lista obserwacyjna 30 dni z warunkiem obserwacji i unieważnienia scenariusza.
- Wiadomości Finnhub co godzinę po dodaniu tokenu.
- Lokalny dziennik portfela long; ręczne wpisanie symbolu, ilości i średniej ceny.

## Konfiguracja
- Skaner kursów i analiza techniczna uruchamiają się bez klucza dzięki Yahoo Finance.
- Opcjonalnie dodaj token Finnhub w Ustawieniach, aby mieć zapasowe notowania USA i wiadomości.
- Twelve Data dodawaj tylko jeśli posiadasz właściwe uprawnienia do wyświetlania danych.
- Klucze API są szyfrowane Android Keystore.
- Włącz powiadomienia Market Radar w ustawieniach Androida. Wyłącz agresywne oszczędzanie baterii dla aplikacji, jeśli chcesz ograniczyć opóźnienia zadań.

## Zasady i ograniczenia
- Brak fikcyjnych kursów: jeśli dostawcy nie zwrócą prawidłowej ceny, aplikacja zachowuje ostatni odczyt i pokazuje błąd/status; nie tworzy wartości zastępczych.
- Alert wykrywa ruch już zaistniały między kolejnymi odczytami, a nie przewiduje go.
- Sygnały techniczne są filtrami obserwacyjnymi, nie rekomendacjami. Nie uwzględniają pełnej wyceny, wyników, spreadów, podatków ani ryzyka portfela.
- Raporty ESPI/EBI i SEC EDGAR są dostępne jako odnośniki, nie są automatycznie synchronizowanym feedem.
- Brak integracji z XTB/Trade Republic, automatycznego FX PLN/USD, shortów/CFD i składania zleceń.

## Budowanie
GitHub Actions buduje APK z Android API 37 i uruchamia testy jednostkowe.

Identyfikator: `pl.marketradar.app`  
Minimalny Android: API 26  
Docelowy Android: API 37

## Podpis
Artefakty CI są podpisane kluczem debugowym. Do aktualizacji bez odinstalowania aplikacji trzeba skonfigurować trwały klucz release signing jako sekrety GitHub Actions. Nie commituj keystore do repozytorium.
