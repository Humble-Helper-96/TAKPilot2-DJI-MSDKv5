# TAKPilot2 - DJI MSDKv5

TAK flight interface for the DJI Matrice 4 series on DJI MSDK v5 (5.18.0). The bench
aircraft is a Matrice 4TD.
Third member of the TAKPilot2 family (Autel, DJI v4, DJI v5). All three
variants look, feel, and function the same. The only differences come
from aircraft capabilities.

Port plan: `../TAKPilot2-DJIv5-PortPlan.md`
SDK surface reference: `../MSDKv5-SDK-Surface.md`
GUI + logic source of truth: the v4 app
(`../../v4/Mobile-SDK-Android-4.18/Sample Code/`) and the Autel app
(thermal/lens features).

## Build

- JDK 17, AGP 8.7.0, Kotlin 2.1.0, Gradle 8.12 (wrapper included)
- minSdk 24, target/compile SDK 35, arm64-v8a only
- applicationId `com.anchortak.takpilot2djiv5` — do not change; the DJI
  key is bound to it.
- DJI key: copy `app/dji-key.properties.template` to
  `app/dji-key.properties` and add the real key. The file is gitignored.
  Without it the app builds but SDK registration fails.

```
./gradlew :app:assembleDebug
```

## Status

Phase 1 (scaffold): builds; placeholder home screen shows SDK
registration and product connection state. Waiting on the DJI console
entry for the new appId to bench-test registration.
