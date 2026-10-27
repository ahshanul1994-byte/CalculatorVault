# Calculator Vault — Android Studio starter project

Features in this starter:
- Dark calculator-style UI
- First-run 6-digit PIN setup; PIN + `=` opens the vault
- Offline local storage
- AES/GCM/NoPadding encryption for imported files
- Android system document picker for image/video selection
- `FLAG_SECURE` to block ordinary screenshots and recent-app previews
- Fingerprint/biometrics are not used

## Open and build
1. Install Android Studio (current stable).
2. Open this folder as an existing project.
3. Allow Gradle sync to download dependencies.
4. Connect an Android device or start an emulator.
5. Press Run, or use Build > Build APK(s).

## Important limitations — starter, not production-ready
- This starter encrypts imported files, but does not yet remove the original file from the user's gallery. This is intentional to avoid accidental data loss.
- Full photo/video in-app preview and restore/export are not implemented yet.
- PIN verification currently uses salted SHA-256, which is not ideal against offline brute-force for a 6-digit PIN. Before release, replace it with a memory-hard KDF (Argon2id where available, or carefully configured PBKDF2) plus rate limiting.
- The AES key is currently stored in app preferences as Base64 for demo simplicity. This is NOT a production-safe key-management design. Before release, generate/store the key with Android Keystore and design a secure PIN-unlock/key-wrapping flow.
- The app currently reads whole files into memory; large videos can cause memory pressure. Production implementation should stream encryption/decryption.
- The app has not been compiled or tested on a device in this environment. Review, complete, and test before storing sensitive personal files.
- Android app uninstall/data clear removes app-private vault data. There is no cloud backup by design.

## Package
`com.example.calculatorvault`
## Build APK on GitHub (GitHub Actions)

1. Create a new GitHub repository.
2. Upload the contents of this project to the repository root (the `.github` folder must be included).
3. Open the repository's **Actions** tab.
4. Select **Build Calculator Vault APK**.
5. Click **Run workflow** (or push a commit to `main`/`master`).
6. When the workflow completes, open that run and download the artifact named **CalculatorVault-debug-apk**.
7. Extract the artifact ZIP; it contains `app-debug.apk`.

The workflow creates a debug APK for testing, not a signed Play Store release. This starter project is not production-ready for sensitive files; review the security limitations above before real use.
