# Termux Fingerprint

A minimal companion app that restores working fingerprint authentication in [Termux](https://github.com/termux/termux-app) on **Android 14**, where `termux-fingerprint` from `Termux:API` silently fails because the biometric subsystem rejects the prompt as a non-foreground request.

Primarily intended to unlock SSH keys managed by [tergent](https://github.com/aeolwyr/tergent) (a PKCS#11 provider backed by the Android hardware keystore), but can be used standalone as a generic "gate this shell command behind a fingerprint" helper.

Related issues:

- https://github.com/termux/termux-api/issues/661
- https://github.com/aeolwyr/tergent/issues/20

<img src='.github/preview.gif' width="300"/>

## Background

On Android 14, `SystemUI`'s `AuthController.isOwnerInForeground()` validates that the package requesting a `BiometricPrompt` is the one currently at the top of the task stack. `Termux:API` (`com.termux.api`) is a separate package from the terminal (`com.termux`) and has no user-facing Activity, so when `termux-fingerprint` asks the system to show the prompt, Android evicts it immediately:

```
AuthController: Evicting client due to: com.termux
BiometricService/AuthSession: Dismissed! ... Reason: 3, Error: 10
termux-api: Fingerprint operation canceled by user
```

The user sees the prompt flash on-screen and the CLI returns `AUTH_RESULT_FAILURE`. This was implicitly fixed in Android 15 by relaxing the foreground check, but devices stuck on Android 14 have no workaround inside `Termux:API` itself.

This app provides a tiny dedicated Activity (package `pw.hexed.fingerprint`) that satisfies the foreground check, shows the prompt, and reports the result back to the calling shell over a loopback TCP socket.

## Requirements

- **Android 12 or newer** (`minSdk 31`; app targets `targetSdk 35`).
- Device with a fingerprint sensor enrolled at the **Class 3 / strong** level (`BIOMETRIC_STRONG`). Face unlock that does not meet this class will not satisfy the prompt.
- Termux with the following packages installed:
  - `jq` — for parsing the JSON result.
  - `netcat-openbsd` (or `nmap` for its `ncat`) — for the loopback listener.
  - `tergent` — only if you intend to use it as an SSH PKCS#11 provider. Not required for generic use.
  - `coreutils` — provides `timeout(1)` and `mktemp(1)` (installed by default in Termux).

## Installation

### Install APK

Get it from one of:

- **GitHub Releases** — [latest APK](https://github.com/flameshikari/termux-fingerprint/releases/latest)
- **Google Play** — [`pw.hexed.fingerprint`](https://play.google.com/store/apps/details?id=pw.hexed.fingerprint)

Sideloaded APKs and the Play Store build share the same signing key; you can update between them only if you started from the same source.

### Install Dependencies

```sh
pkg install jq tergent netcat-openbsd
```

If you prefer `ncat` from nmap instead of `netcat-openbsd`:

```sh
pkg install jq tergent nmap
```

The shell script uses plain `nc`, which both packages provide.

### Generate Keys

Skip this step if you already have keys in `termux-keystore`.

```sh
# EC P-256, requires fingerprint within 10 seconds of key use
termux-keystore generate my-ssh-key -a EC -s 256 -u 10

# or RSA 2048
termux-keystore generate my-ssh-key -a RSA -s 2048 -u 10
```

The `-u <seconds>` flag binds the key to biometric authentication performed within that window. Use `termux-keystore --help` for the full reference.

List keys:

```sh
termux-keystore list
```

Export the public key so you can append it to `~/.ssh/authorized_keys` on remote hosts:

```sh
ssh-keygen -D $PREFIX/lib/libtergent.so
```

### Install Script

```sh
TARGET=~/.ssh/bin/fingerprint
mkdir -p ~/.ssh/bin
curl -sL https://github.com/flameshikari/termux-fingerprint/raw/refs/heads/master/termux/.ssh/bin/fingerprint > "$TARGET"
chmod +x "$TARGET"
```

The script is self-contained: it launches the APK with a randomly chosen loopback port, listens for the JSON result on that port, prints it, and exits `0` on success or `1` on any failure or timeout.

### Configure SSH

Edit `~/.ssh/config`. Minimum working example:

```ssh-config
Host *
    PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
    Match exec "~/.ssh/bin/fingerprint"
```

For more selective setups see [Usage Examples](#usage-examples).

### Connect

```sh
ssh user@host
```

The fingerprint prompt should appear over Termux. Authenticate with a finger; SSH continues with key-based auth once the script exits `0`.

## Usage Examples

### Per-host Gating

Only require a fingerprint for specific hosts:

```ssh-config
Match host production.example.com exec "~/.ssh/bin/fingerprint"
    PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so

Host *.internal.lan
    # no fingerprint, plain password / other key auth
```

### Multiple Keys

`tergent` exposes every key in the keystore. If you want a specific alias per host, keep them all listed and let SSH pick by `IdentityFile` fingerprint matching, or use `IdentitiesOnly yes` combined with explicit key material on disk for non-keystore hosts:

```ssh-config
Host bastion
    HostName bastion.example.com
    User ops
    PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
    Match exec "~/.ssh/bin/fingerprint"

Host legacy-server
    HostName 10.0.0.5
    IdentitiesOnly yes
    IdentityFile ~/.ssh/legacy_ed25519
    # no fingerprint required
```

### Standalone Usage

The script is a generic boolean gate. Any command that should only run after a fingerprint check:

```sh
~/.ssh/bin/fingerprint && rm -rf ~/something/sensitive
```

```sh
if ~/.ssh/bin/fingerprint; then
    gpg --decrypt secrets.gpg
else
    echo "authentication failed" >&2
fi
```

### JSON Output

On success the script prints a pretty-printed JSON object and exits `0`:

```json
{
  "errors": [],
  "failed_attempts": 0,
  "auth_result": "AUTH_RESULT_SUCCESS"
}
```

On a canceled prompt (user hit back or the blank negative button) the script exits `1` with:

```json
{
  "errors": [
    "ERROR_USER_CANCELED"
  ],
  "failed_attempts": 0,
  "auth_result": "AUTH_RESULT_FAILURE"
}
```

On too many failed attempts:

```json
{
  "errors": [
    "ERROR_LOCKOUT",
    "ERROR_TOO_MANY_FAILED_ATTEMPTS"
  ],
  "failed_attempts": 5,
  "auth_result": "AUTH_RESULT_FAILURE"
}
```

On a crashed or killed Activity the script exits `1` with no output (the `nc` listener hits its 35-second timeout and no JSON arrives).

### JSON Schema

| Field             | Type     | Values                                                        |
| ----------------- | -------- | ------------------------------------------------------------- |
| `auth_result`     | string   | `AUTH_RESULT_SUCCESS`, `AUTH_RESULT_FAILURE`, `AUTH_RESULT_UNKNOWN` |
| `failed_attempts` | integer  | Number of non-matching finger presses before giving up.       |
| `errors`          | string[] | Sorted list of error codes (see below). Empty on success.     |

### Error Codes

| Code                            | Meaning                                                              |
| ------------------------------- | -------------------------------------------------------------------- |
| `ERROR_NO_HARDWARE`             | Device has no biometric hardware the system can use for strong auth. |
| `ERROR_NO_ENROLLED_FINGERPRINTS`| No fingerprint is enrolled at the required class.                    |
| `ERROR_TIMEOUT`                 | 30-second in-app deadline hit before the user acted.                 |
| `ERROR_LOCKOUT`                 | Sensor is temporarily locked (system-enforced).                      |
| `ERROR_TOO_MANY_FAILED_ATTEMPTS`| Set after 5 non-matching presses in the same session.                |
| `ERROR_CANCEL`                  | Negative-button tap (the blank "cancel" area of the prompt).         |
| `ERROR_USER_CANCELED`           | User dismissed the prompt (swipe, back, power, etc.).                |
| `ERROR_CANCELED`                | System-initiated cancellation (another auth preempted this one).     |
| `ERROR_UNKNOWN_<N>`             | Unmapped `BiometricManager.canAuthenticate()` status code.           |

## How It Works

1. Your SSH config `Match exec` fires the helper script before the connection is established.
2. The script aborts early if:
   - it is being run inside an existing SSH session (`$SSH_CLIENT` / `$SSH_TTY` set) — prevents accidentally prompting the local device for a remote shell;
   - `termux-keystore list` returns an empty array — there are no keys to unlock, so the whole dance is pointless.
3. It picks a random loopback port in the `30000-59999` range and launches a one-shot `nc -dl 127.0.0.1 <port>` listener in the background, wrapped in `timeout 35` as a hard deadline.
4. It calls `am start --activity-no-animation -n pw.hexed.fingerprint/.MainActivity --ei port <port>`, pulling the app into the foreground task so the Android 14 biometric foreground check passes.
5. The Activity uses a fully transparent theme and no content of its own, so visually only the system `BiometricPrompt` is rendered on top of the dimmed Termux window. It waits for `onWindowFocusChanged(true)` before calling `authenticate()` — this is what makes the prompt stick instead of being evicted.
6. When the prompt resolves (success, cancel, timeout, lockout), the app builds a JSON payload and opens a client socket to `127.0.0.1:<port>`, retrying up to 3 seconds to cover the small launch-time race. Then it `finishAffinity()`s with animations disabled on both ends.
7. `nc` receives the JSON, writes it to a temp file, and exits. The script reads the file, echoes the JSON (so SSH's stderr surfaces it to you), and returns `0` if `auth_result == AUTH_RESULT_SUCCESS`, otherwise `1`.
8. If the script returns `0`, SSH proceeds with PKCS#11 key-based auth through `tergent`. The key is unlocked by the fingerprint just performed (thanks to the `-u <seconds>` window from step 3 of installation). On `1`, SSH falls back to whatever auth methods the host accepts next, typically password.

### Launcher Mode vs Shell Mode

`MainActivity` branches on whether the `port` int extra is present in the launching intent:

| | Shell mode (`am start --ei port <N>`) | Launcher mode (tap from home screen) |
| --- | --- | --- |
| Theme | `Transparent` (windowIsTranslucent, no dim) | `Black` (fullscreen opaque, black background, immersive system bars) |
| Visual | System `BiometricPrompt` rendered on top of the dimmed Termux window — no visible app switch | Full-screen black, then `BiometricPrompt` centered on it |
| Result handling | JSON posted to `127.0.0.1:<port>` for the shell script to read | Activity closes silently after the prompt resolves |
| Missing hardware / no enrolled prints | Silent exit via JSON `ERROR_NO_HARDWARE` / `ERROR_NO_ENROLLED_FINGERPRINTS` | Toast (`"No fingerprint scanner found"` / `"No fingerprints enrolled"`) before closing |

The launcher-mode fallback exists purely so Google Play reviewers (and curious users) see a real app UI when they tap the icon — a transparent-only build was rejected under the "Broken Functionality" policy because the brief black-to-prompt transition read as "app doesn't open" on devices without enrolled biometrics. Selection happens in `MainActivity.onCreate()`:

```java
int port = getIntent().getIntExtra("port", -1);
boolean launcherMode = port <= 0;
if (launcherMode) {
    setTheme(R.style.Black);  // must be called before super.onCreate()
}
super.onCreate(savedInstanceState);
```

All biometric logic, timeout handling, and the `onWindowFocusChanged` deferred `authenticate()` call are identical between the two modes — only the theme and visible chrome differ.

## Permissions

The APK requests two permissions:

- **`USE_BIOMETRIC`** — required to show the fingerprint prompt.
- **`INTERNET`** — required **only** to open a `Socket` to `127.0.0.1`. Android's permission system has no narrower "loopback-only" variant. No outbound traffic ever leaves the device; see [`privacy-policy.md`](privacy-policy.md).

## Building From Source

The repo is a standard Gradle Android project.

```sh
git clone https://github.com/flameshikari/termux-fingerprint
cd termux-fingerprint
```

Key versions (see [`app/build.gradle`](app/build.gradle)):

- `compileSdk`: 35
- `minSdk`: 31
- `targetSdk`: 35
- `androidx.biometric`: 1.1.0 (pulls in `androidx.fragment` transitively — the only other dep)

### Local Build

Prerequisites: JDK 20+, Android SDK with build-tools for API 35.

```sh
./gradlew assembleDebug     # unsigned debug APK at app/build/outputs/apk/debug/
./gradlew assembleRelease   # requires a signing config
```

### Docker Build

No local JDK / Android SDK required, only Docker.

```sh
docker run --rm -it \
    -v "$PWD":/home/mobiledevops/app \
    -w /home/mobiledevops/app \
    mobiledevops/android-sdk-image \
    ./gradlew assembleDebug
```

The resulting APK ends up at `app/build/outputs/apk/debug/app-debug.apk` on the host. Replace `assembleDebug` with `assembleRelease` if you have a signing config wired in.

### CI Build

CI builds are split into two `workflow_dispatch`-only pipelines:

- [`.github/workflows/release.yml`](.github/workflows/release.yml) — signed APK + AAB, GitHub Release, Play Store upload.
- [`.github/workflows/debug.yml`](.github/workflows/debug.yml) — unsigned debug APK for testing; bumps `versionCode` to the current Unix timestamp so consecutive debug installs always replace the previous one.

## Troubleshooting

### Prompt Flashes and Disappears

This is the original Android 14 eviction. Likely causes:

- App wasn't actually launched in the foreground — make sure your SSH config calls the script via `Match exec` and not something that runs it inside the already-running shell without pulling the Activity up.
- Another app is in an overlay / picture-in-picture state that outranks ours.

### Empty Keystore

`termux-keystore list` returns `[]` → script exits silently. You have no keys. Run `termux-keystore generate ...` (see [Generate Keys](#generate-keys)).

### Command Not Found in SSH

`termux-fingerprint: command not found` inside an SSH session is intentional. The script's first line refuses to run if `$SSH_CLIENT` or `$SSH_TTY` is set, so you can't accidentally chain fingerprint prompts from a remote shell into your phone. Running it locally in Termux works fine.

### Empty Errors on Failure

`AUTH_RESULT_FAILURE` with empty `errors` means the Activity was killed before it could categorise the error (e.g., you hit home/recents during the prompt, or the system process restarted). Retry.

### Animation Still Visible

Some OEM skins (MIUI, OneUI, HyperOS, etc.) override window transition animations at the system level and ignore `FLAG_ACTIVITY_NO_ANIMATION`. Not fixable from inside the app — the disabling is already done via theme + `overridePendingTransition(0, 0)` + `am --activity-no-animation`.

### Port Already in Use

`nc: bind: Address already in use` is extremely unlikely (port is randomized over a 30000-wide range), but if it happens, just re-run. The script picks a fresh port each invocation.

## Privacy

The app is offline-only. See [`privacy-policy.md`](privacy-policy.md) for the full statement.

## License

[MIT](LICENSE). See file for full text.
