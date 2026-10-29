# Two-device verification guide

No Worker endpoint is configured in this checkout. A debug build without an endpoint displays a setup error and disables room creation/joining. Release APK tasks fail if the URL is missing or still a placeholder. This repository does not deploy a Worker or provision Supabase. Use an already configured free-tier backend only; do not enable billing or paid services.

## Configure and build the APK

1. Obtain the HTTPS URL of an already deployed Worker endpoint, for example `https://your-worker.workers.dev`. Do not put the Supabase service-role key or any database secret in Android configuration. Pass the URL as the public Gradle property `-PchatApiBase=...`; the URL is compiled into the app, but it is not a credential.
2. Build from PowerShell with JDK 17 and Android SDK 36. On the Windows setup that required a short temporary directory:

   ```powershell
   $toolRoot = Join-Path $env:LOCALAPPDATA 'EphemeralChatToolchain'
   $env:JAVA_HOME = Join-Path $toolRoot 'jdk-17.0.20.1+1'
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   $env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
   $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
   $env:TEMP = 'C:\t'; $env:TMP = 'C:\t'
   cd C:\Users\DEV_MANISH\Documents\msg\android
   .\gradlew.bat clean testDebugUnitTest assembleDebug -PchatApiBase=https://your-worker.workers.dev
   ```

3. Enable Developer options and USB debugging on each Android 8+ device, connect both by USB, and authorize the computer. List serial numbers and install the same APK:

   ```powershell
   $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
   & $adb devices -l
   $apk = 'C:\Users\DEV_MANISH\Documents\msg\android\app\build\outputs\apk\debug\app-debug.apk'
   $serialA = 'SERIAL_SHOWN_FOR_DEVICE_A'
   $serialB = 'SERIAL_SHOWN_FOR_DEVICE_B'
   & $adb -s $serialA install -r $apk
   & $adb -s $serialB install -r $apk
   ```

   Replace both serial values with the exact IDs shown by `adb devices -l`. Alternatively, connect only one device at a time and run `& $adb install -r $apk`.

## Exercise the flow on different networks

1. Put device A on Wi-Fi and device B on a separate network, such as cellular data with Wi-Fi disabled. Confirm both can reach the configured Worker endpoint.
2. On A, create a room and share the one-use invitation with B through a private channel. On B, join using that invitation.
3. Compare the complete key fingerprint shown on both screens over a separate trusted channel. Tap **I compared it** on both devices only after the values match.
4. Send text in both directions and confirm delivery status. Send a file below 10 MiB, confirm it arrives intact, then test cancellation and a file over the limit.
5. Request a voice call, accept microphone permission on the other device, test mute and hang up. Repeat with video and camera permission; test camera off and switch camera. Put one device in the background during a call and confirm the call ends and capture stops.
6. Have A send a message and file, then exit A while B is offline. Reopen B's app/connection; verify A's lines and in-progress/completed received file data are purged while B's own lines remain. Then have B exit and confirm the room is closed/cleaned up by the backend.
7. Repeat on a restrictive network if available. When direct ICE fails, the UI should show direct connection failure; messaging, file transfer, and calling must not fall back to a server relay. There is no TURN server configured.

This procedure is a test plan, not evidence that the flow passed. Record Android versions, network types, results, and any ICE failure state during an actual run. If the two networks cannot form a direct ICE path, that is an expected limitation of the no-TURN ₹0 configuration.
