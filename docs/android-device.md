# Run the app on your Android phone

The mobile channel is one Expo/React Native codebase with web, Android and
iOS targets. The web target serves at `/app`; this is the ANDROID one.

## Build the APK (done once per code change)

```bash
cd apps/mobile
export ANDROID_HOME=$HOME/Library/Android/sdk
npx expo prebuild --platform android --no-install
cd android && ./gradlew assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

## Install and connect (USB — keeps every localhost config truthful)

1. On the phone: Settings → About → tap "Build number" 7× → enable
   **Developer options** → turn on **USB debugging**; connect by cable and
   accept the trust prompt.
2. From the Mac:

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB install apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk
# tunnel the phone's localhost to the Mac — tokens keep their localhost
# issuer, so NOTHING in the stack needs reconfiguring:
$ADB reverse tcp:8080 tcp:8080   # gateway / APIs
$ADB reverse tcp:8085 tcp:8085   # Keycloak
```

3. Open **MyGenAlpha** on the phone → **Sign in / register** → the system
   browser opens Keycloak → sign in (e.g. `emil@acme.example` / `emil` for
   the B2B member story, `kai@bss.local` / `kai` for B2C) → the
   `genalphabss://` deep link returns you to the app, signed in.

Note: `adb reverse` lasts while the cable (or `adb tcpip` session) is up —
re-run the two reverse commands after replugging. For a cable-free LAN build,
set `EXPO_PUBLIC_API_BASE=http://<mac-ip>:8080` at build time — but then
Keycloak must be reissued on that hostname too; the USB route avoids all of it.

## What's native here, honestly

- OIDC sign-in runs through **expo-auth-session** and the system browser with
  PKCE — the "phase two" the auth module always promised. The access token is
  held in memory for the session.
- Everything else — recomposing Home, SIM PUK/PIN, top-ups, plan change,
  Emil's B2B member mode — is the same code the web target runs.
- Not yet: push notifications (inbox polls), biometrics, release signing.
