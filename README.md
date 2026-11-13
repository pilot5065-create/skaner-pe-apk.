# Market Radar — prywatny skaner inwestycyjny

Prywatna aplikacja Android dla jednego użytkownika. Nie składa zleceń i nie łączy się z rachunkami maklerskimi.

## Funkcje
- Watchlista: USA, Europa i GPW; domyślnie spółki AI/półprzewodniki, wybrane Big Tech, ETF-y oraz Kruk, Lubawa, PZU i Pekao.
- Notowania USA przez Finnhub; kursy rynków międzynarodowych przez Twelve Data, jeśli konto ma odpowiednie uprawnienia do wyświetlania danych.
- Alerty po przekroczeniu dziennej zmiany procentowej: domyślnie 3% i 5%.
- Priorytetowy skan NVDA, SMCI, AMD i AVGO co 15 minut; pozostała watchlista co 90 minut.
- Wiadomości Finnhub odświeżane w tle co godzinę.
- Analiza dziennych świec: SMA20, SMA50, RSI14 i zmiana 5 sesji.
- Lista obserwacyjna 30 dni: tylko kandydaci z określonym warunkiem obserwacji i unieważnienia.
- Lokalny dziennik portfela long; ręczne wpisanie symbolu, ilości i średniej ceny.
- Klucze API szyfrowane kluczem Android Keystore.

## Konfiguracja dostawców
1. Finnhub: dodaj własny token w zakładce Ustawienia. Używaj w granicach planu osobistego i warunków licencyjnych dostawcy. Nie udostępniaj aplikacji ani danych innym osobom.
2. Twelve Data: opcjonalnie dodaj klucz planu z prawem internal display i uprawnieniem do wybranej giełdy. Basic Free jest przeznaczony do użycia non-display i nie może służyć do pokazywania kursów w aplikacji.
3. Włącz powiadomienia Market Radar w ustawieniach Androida. System może opóźniać harmonogramy w tle.

W cenniku indywidualnym Twelve Data (sprawdź przed zakupem) Grow obejmuje EOD dla globalnych akcji/ETF-ów i internal display; Pro obejmuje real-time na wybranych rynkach UE. Katalog Twelve Data wskazuje GPW/WARSAW (MIC XWAR) od planu Ultra. Zakres danych zależy też od dodatków giełdowych.

## Zasady
- Brak fikcyjnych notowań: jeśli API nie zwróci ceny, aplikacja pokaże brak danych.
- Każdy kurs zachowuje źródło i znacznik czasu dostawcy oraz lokalny czas pobrania.
- Alert jest wykrywaniem już zaistniałej zmiany między odczytami, a nie przewidywaniem ruchu.
- Sygnały techniczne i lista 30-dniowa są filtrami obserwacyjnymi, nie rekomendacją inwestycyjną. Nie uwzględniają wyceny, wyników, spreadu, podatków ani pełnego ryzyka portfela.
- Raporty polskich emitentów: źródła pierwotne ESPI/EBI; link do PAP Biznes w zakładce News. Wersja aplikacji nie pobiera jeszcze raportów ESPI/EBI automatycznie.
- SEC EDGAR jest udostępniony jako odnośnik do źródeł pierwotnych, nie jako automatycznie synchronizowany feed.

## Budowanie
GitHub Actions buduje APK z Android SDK 37.0 / API 37, uruchamia testy jednostkowe, sprawdza identyfikator pakietu i podpis APK, a następnie publikuje artefakt.

Identyfikator aplikacji: `pl.marketradar.app`  
Minimalny Android: API 26  
Docelowy Android: API 37

## Ograniczenia podpisu
Aktualny artefakt CI jest podpisany kluczem debugowym. Do długoterminowych aktualizacji bez odinstalowania aplikacji należy skonfigurować stały klucz release signing jako sekrety GitHub Actions. Nie przechowuj klucza podpisującego w publicznym repozytorium.
