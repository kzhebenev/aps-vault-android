# APS Vault for Android

Android client for a self-hosted [APS Vault](https://github.com/kzhebenev/aps-vault), built on
`docs/CLIENT-CONTRACT.md` of the server (tested against 0.14). Android 11 and later.

- Sign-in as the administrator (master password, TOTP when 2FA is on, or the PIN of a hardware
  token / KMS cell when the server has one) or as a named user (e-mail, password, TOTP).
- Roles per folder: create and edit secrets only where the session may write, with a password
  generator; the signed-in person and their roles are shown in Settings.
- Folders: tabs for all secrets, favourites and each folder with counts; the "all" view is grouped
  by folder; the owner creates folders. Search covers every folder.
- Secret card: login, password (hidden until shown), the one-time code with a countdown ring, site,
  notes and tags; expiry, machine-only, approval and rotation badges; version, dates, read count,
  rotation status. Version history with older values, one-time share links (manager), a fresh
  server-generated value and deletion (writer).
- Edit form: folder, name, login, password with a generator, site, tags, notes, TOTP key, expiry
  date, machine-only and approval switches.
- Copy to the clipboard, cleared after 30 s; marked sensitive so Android 13+ hides the preview.
- Two-person rule: secrets with `require_approval` show a request form, then poll the approval
  and read the value with it. Without a notifier the approval link is shown and can be shared.
- Machine-only secrets show login, notes and TOTP, never the value.
- Biometric unlock (optional): the session id is encrypted with an RSA key in the Android
  Keystore whose private half needs a strong biometric for every use and is invalidated when a
  new fingerprint is enrolled. Off: the session lives in memory only.
- Auto-lock after a chosen time in background (1, 5, 15, 60 min).
- Autofill service: in other apps and browsers a login form offers matching secrets (by web
  domain, or for apps by the site name in the package name) and a search over the vault. The
  value of a secret is read from the server only when it is picked.
- No screenshots in release builds (`FLAG_SECURE`), no backup of app data.

The session travels as `Authorization: Bearer`; the app keeps no cookies.

- Updates: on start the app asks the GitHub releases API for the latest release; when it is newer,
  a banner offers it. The APK is installed only if it is this package signed by the same key.

## Build

JDK 17 and the Android SDK (platform 35):

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # needs a signing config
```

`formtest/` is a manual test bench for the autofill service (a native login form, and a
WebView form when started with a URL); it is not shipped.

## Releases

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then tag `v<versionName>`:
the `release` workflow builds the signed APK (key from the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`
secrets) and publishes it as a release, which installed apps pick up.

## Known limits

- Sign-in with a security key or passkey (server 0.13 WebAuthn) is not supported yet: the
  server accepts assertions only from its web origin and serves no `assetlinks.json`.
- Plain `http://` is accepted only for `localhost`, `127.0.0.1` and the emulator host `10.0.2.2`.
- Chrome uses third-party autofill services from version 131 with "Autofill using another
  service" enabled in its settings.

## License

MIT.
