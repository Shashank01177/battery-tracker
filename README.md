# 🔋 Battery Tracker — Android App

A native Android app (Kotlin) that automatically monitors and records battery charging, discharging, and active screen-on usage sessions in the background.

---

## ✨ Features

- **⚡ Charging Sessions:** Tracks plug-in to unplug time, start % to end %, and duration.
- **🔋 Kept at 100% Tracking:** Records how many minutes the device was kept plugged into the charger after reaching 100% full charge.
- **📉 Discharging Sessions:** Monitors overall battery drain between charges.
- **📱 Active Screen-On Logs:** Tracks each individual screen-on period during discharge (e.g., *11:10 AM – 11:20 AM: 80% → 78% (10 mins)*) so you can see your active phone usage vs idle drain.
- **📤 One-Click Export:** Exports complete battery history into a clean `.csv` file via the Android share sheet (WhatsApp, Drive, Gmail, etc.).
- **🔄 Auto-Start on Reboot:** Automatically resumes tracking on device reboot via `BootReceiver`.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin
- **Architecture:** MVVM + Clean Repository Pattern
- **Local Database:** Room Database (SQLite)
- **Background Tracking:** Foreground Service (`BatteryMonitorService`) with `ACTION_BATTERY_CHANGED` and `ACTION_SCREEN_ON`/`OFF` receivers
- **UI:** Material Design 3, RecyclerView with ViewBinding, LiveData
- **Cloud CI/CD:** GitHub Actions workflow (`.github/workflows/build-apk.yml`) for automated cloud APK builds

---

## 📱 Installation on Android

### Cloud Build (GitHub Actions)
1. Go to the **Actions** tab of this repository.
2. Select the latest **Build Android APK** workflow run.
3. Under **Artifacts**, download `BatteryTracker-debug-apk`.
4. Open the downloaded file on your phone and tap **Install**.

### ⚠️ Important Settings for Background Tracking (iQOO / Vivo / Xiaomi / Samsung):
- **Enable Autostart:** Go to *Settings → Apps → Battery Tracker → Autostart → ON*.
- **Battery Optimization:** Go to *Settings → Battery → Background power consumption management → Battery Tracker → Unrestricted / Don't restrict*.
