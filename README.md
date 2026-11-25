# PoGo-Plus-Gold

## BLE diagnostic
Android 12+; enable Bluetooth, grant Nearby Devices permissions and run the test. Uses a custom UUID, not the game protocol. Advertising alone is not proof of local connectivity; timeout is inconclusive. No game support or device keys are included.
Build: JDK 17, Gradle 8.9, Android SDK 35, `gradle :app:assembleDebug`. GitHub Actions builds a diagnostic APK.
