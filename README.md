# WiFi AutoSwitch

A small personal Android app that moves your phone between your two home Wi-Fi networks (Upstairs / Downstairs) when the one you're on gets weak and the other is clearly stronger. No router changes, no passwords typed into the app, no cloud, no ads, no analytics.

## 1. Build in Android Studio

1. Unzip the project and open the `WiFiAutoSwitch` folder in Android Studio (File → Open).
2. Let Gradle sync. It downloads Gradle 8.11.1, AGP 8.8.1, Kotlin 2.0.21, Compose, DataStore and the Shizuku API 13.1.5.
3. If Studio offers to upgrade the Android Gradle Plugin, it's safe to decline. If you accept, use the AGP Upgrade Assistant rather than editing versions by hand (AGP 9 changes how the Kotlin plugin is applied).
4. Build → Build App Bundle(s) / APK(s) → Build APK(s). The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`.

Run the algorithm's unit tests with `./gradlew testDebugUnitTest` (or right-click `SwitchingEngineTest` → Run).

## 2. Install on the Pixel 9 Pro XL

Easiest: enable Developer options (Settings → About phone → tap Build number 7 times), turn on USB debugging, plug in the phone and press Run ▶ in Android Studio.

Or copy `app-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager when asked).

## 3. First-time setup

1. Open the app. Tap **Choose** next to Upstairs Wi-Fi and pick your C-Data network, then do the same for Downstairs with the Dialog S50 network. If a name isn't listed, type it exactly (case and spaces matter).
2. Turn on **Auto Switch**. Allow **Precise** location and notifications when asked. Android treats Wi-Fi names and scan results as location data; the app never asks for a GPS position.
3. Keep the system **Location** toggle on (Android hides scan results when it's off).
4. For fully automatic switching, set up Shizuku (below). Without it the app still works in **assisted mode**: you get a notification, tap it, pick the network.
5. Optional: Advanced settings → **Allow all the time** so monitoring restarts by itself after a reboot. Without it, you get a one-tap "Resume" notification after restarting.

### Shizuku (for automatic switching, no root)

1. Install Shizuku (https://shizuku.rikka.app/download/).
2. Connect to Wi-Fi, enable Developer options → **Wireless debugging**, open Shizuku and follow its "Start via Wireless debugging" pairing steps.
3. Back in WiFi AutoSwitch, tap **Allow** in the Setup card and grant access in Shizuku's dialog.
4. After every phone restart Shizuku normally needs starting again (open Shizuku → Start). The app shows "Shizuku is not running" and falls back to assisted mode until then.

## 4. How it works

**Detection** is event-driven: a `NetworkCallback` (with location info) reports which network you're on and whether it has working internet; signal broadcasts and a cheap cached read give the RSSI; a `ScanResultsCallback` reuses every scan the system already does.

**Decision** (`core/SwitchingEngine.kt`, pure Kotlin, unit-tested):
- Do nothing while the current signal is at or above **-75 dBm**.
- When weak, the other network must be at least **12 dB stronger** and itself no weaker than -82 dBm.
- **Two separate scans** (at least 3 s apart, within 90 s, each no older than 30 s) must agree.
- **120 s cooldown** after a successful switch, so no ping-pong.
- Failed attempts back off **60 s → 120 s → 240 s → 480 s**, and after **5 failures** it pauses until you turn Auto Switch off and on.
- The four main thresholds are adjustable under Advanced settings.

**Switching** (`NetworkConnector.kt`):
- Ordinary apps cannot make Android join a saved network on Android 10+. `enableNetwork()` always fails for modern apps, `WifiNetworkSuggestion` only suggests and needs passwords, and `WifiNetworkSpecifier` creates an app-only link, not the phone's internet Wi-Fi. None of these are used.
- **Automatic mode (Shizuku):** a tiny Shizuku "user service" (`shizuku/PrivilegedWifiService.kt`) runs as the shell user, which holds `NETWORK_SETTINGS`, and asks the Wi-Fi service to join the saved network by its ID, the same call the Settings app makes. The saved profile (password, MAC randomisation, metered, auto-join) is not recreated or changed. It never runs shell commands, never reads passwords, and is started only for the few seconds a switch takes.
- After requesting a switch the app waits up to 25 s for the target network **with validated internet**. Otherwise it counts as a failure; if you're left with no Wi-Fi it rejoins the previous network (Android usually does this itself first).
- **Assisted mode:** a notification opens the Wi-Fi panel. Reported as "Stronger network available", never as a successful switch.

**Battery:**
- Foreground service of type `location` with a silent, minimised notification. No wake locks, no alarms, no busy loops; timers never wake a sleeping CPU.
- Scans are requested only when the signal is weak and fresh data is missing. They're capped at 3 per 2 minutes (Android allows 4), at least 20 s apart, and back off up to 5 minutes when both networks are weak.
- Screen on: cached-signal checks every 60 s (strong), 20 s (near the threshold) or 8 s (weak). These checks read a cached value and never touch the radio.
- Monitoring pauses while Wi-Fi or Location is off.

## 4b. Extras

- **Calls:** with "Don't switch during calls" on (default), switching waits until a phone or VoIP call ends (detected from the audio mode; no extra permission).
- **Shizuku alert:** once Shizuku has worked, a notification appears if it stays unavailable for 10 s. Tap it to open Shizuku and press Start.
- **Quick Settings tiles:** pull down the panel twice → pencil/Edit → drag in **Auto Switch** (on/off, shows the floor) and **Switch Wi-Fi** (switch now).
- **Switch now:** the button on the Status card or the Switch Wi-Fi tile moves you to the other network immediately (needs Shizuku; without it, opens the Wi-Fi panel). Failed manual attempts don't count toward the automatic failure limit.
- **Auto-tune:** each time Android finishes a scan anyway (screen on), the app records one reading: your current signal and whether the other network was clearly stronger. After a few trips between floors, Advanced settings shows a suggested threshold with an **Apply** button. Only counts are stored.

## 5. Known limitations

- **Screen off:** Android stops measuring signal strength while the screen is off, and the app does not wake the phone. So switching usually happens within roughly 10–25 s of turning the screen **on**. If the phone is already awake (e.g. playing music) and the signal was borderline, the app may scan once every couple of minutes. If the connection drops completely, Android's own logic reconnects to whichever saved network is available.
- **Shizuku after reboot:** must usually be restarted manually (Wireless debugging requirement).
- **Same-name networks:** if two saved profiles share an SSID, the first one is used.
- **Hidden framework API:** the Shizuku path calls a hidden interface whose signature has changed between Android versions. The code adapts by parameter type, but Android 17 behaviour must be confirmed on your phone (check Advanced → Show log after a switch).

## 6. Verification status

**Verified in the build sandbox:**
- `SwitchingEngine` and `ThresholdTuner` compiled and all 31 unit tests passed (both directions, both strong, both weak, slight improvement, stale/future scans, destination disappearing, observation window, recovery mid-confirmation, cooldown and return trip, backoff and pause, assisted-mode cooldown).
- All non-UI code (service, monitor, connector, Shizuku code, settings, notifications, boot receiver) type-checked against a real Android 16 (API 36) framework jar and real kotlinx-coroutines, using small stand-ins for AndroidX core/DataStore/Shizuku signatures.
- Confirmed from the Android 16 framework jar: `IWifiManager.connect(WifiConfiguration, int, IActionListener, String, Bundle)` and `getConfiguredNetworks(String, String, boolean)`, matching what the privileged service fills in.
- Confirmed from AOSP source: the shell package holds `NETWORK_SETTINGS` up to Android 16.

**Implemented but not verified:**
- A full Gradle build / APK (no Android SDK or Google Maven access in the sandbox).
- Compose UI (syntax-checked only).
- Anything on a real device or on Android 17, including the Shizuku switch call, scan throttling behaviour, and battery use.

## 7. Manual test checklist (on the phone)

Turn on Advanced → Show log while testing; the counters show checks, scans and switch attempts.

1. Upstairs, screen on, walk downstairs: expect "Switched successfully" (Shizuku) or a notification (assisted) within ~10–30 s.
2. Walk back up: expect the reverse switch (after the 120 s cooldown).
3. Stand where both are strong: no switching at all.
4. Stand where both are weak: no switching; scan requests should slow down over time.
5. Turn the downstairs router off while upstairs is weak: no switch.
6. Stop Shizuku mid-use: status shows assisted mode, no crash.
7. Lock the screen, walk between floors, unlock: switch shortly after unlocking.
8. Toggle Wi-Fi off and on, and airplane mode: status shows "Wi-Fi is off", then resumes.
9. Force-stop and reopen the app: monitoring resumes if Auto Switch was on.
10. Reboot: either it resumes on its own (Allow all the time) or you get a Resume notification.
11. Walk between floors several times in a row: no rapid back-and-forth.
12. Leave it on for a day and check Settings → Battery → app usage, plus the scan counter.

## 8. Project layout

```
app/src/main/java/app/wifiautoswitch/
  MainActivity.kt           single activity
  ui/HomeScreen.kt          the whole UI
  ui/Theme.kt               Material 3, dynamic colour, dark mode
  MonitoringService.kt      foreground service, adaptive timing, scan budget, verification
  WifiMonitor.kt            callbacks, broadcasts, cached scan results
  NetworkConnector.kt       automatic (Shizuku) vs assisted switching
  SettingsRepository.kt     DataStore settings (local only, backups off)
  Permissions.kt, Notifications.kt, BootReceiver.kt, AppState.kt (status + diagnostics)
  core/SwitchingEngine.kt   pure decision logic (unit-tested)
  core/Model.kt
  shizuku/ShizukuConnector.kt, shizuku/PrivilegedWifiService.kt
app/src/main/aidl/.../IPrivilegedWifi.aidl
app/src/test/.../SwitchingEngineTest.kt
```

The Shizuku technique was studied in nd4y/netswitcher, which has no license file; this code is an independent implementation and copies none of it.
