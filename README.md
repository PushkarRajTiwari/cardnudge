# CardNudge

Tells you which credit card to use at the store you're in, without you saving any places. It's built once with **Kotlin Multiplatform + Compose Multiplatform** and runs on Android and iPhone.

- **Cards:** add your cards and their reward rates, and swipe through them like a wallet. **Scan card** uses the phone's document scanner (Google's on Android, Apple's on iPhone) to crop and straighten a photo of your real card, so it shows exactly as it looks. The scan stays on the phone. Cards you don't scan get a drawn face in their colours.
- **Nearby:** CardNudge looks up the stores around you on OpenStreetMap and shows the best card for each. Stay at one for about a minute and you get a notification naming the best card and the next best.
- **Best picks:** your top card for every category.

Your cards stay on the phone: no account and no bank linking. The only network call is the store lookup, which sends your approximate location to the public Overpass API (OpenStreetMap). Store data © OpenStreetMap contributors, under the ODbL.

## How the code is shared

| Where | What | Shared? |
|---|---|---|
| `composeApp/src/commonMain` | Models, recommender, storage, OpenStreetMap lookup, nearby logic, every screen (Compose) | ✅ Android + iOS |
| `composeApp/src/commonTest` | Unit tests for the rules and storage | ✅ run on both |
| `composeApp/src/androidMain` | Geofences (Play Services), background refresh (WorkManager), HTTP, notifications, permissions | Android only |
| `composeApp/src/iosMain` | Region monitoring and significant-change updates (CoreLocation), HTTP, notifications | iOS only |
| `iosApp/` | ~50 lines of Swift: app entry point hosting the shared UI | iOS only |

The OS-specific code is small and sits behind one interface, `platform/Platform.kt`.

## Memory and efficiency
- There is one `Repository` per process, exposing `StateFlow`s. It never holds an Activity or a view controller. Android singletons keep only the application context.
- Screens collect state with `collectAsStateWithLifecycle`, so collection stops when the app is in the background.
- Coroutines run in composition scopes, so a lookup is cancelled if the screen goes away.
- Store lookups are reused until you move about 800 m or a week passes, and at most 400 stores are saved.
- Lists are `LazyColumn` with stable keys. Gradients, shapes and the backdrop are built once, not on every frame.
- Best-card picks are computed in one pass, and only when cards change.

## Build and install
**Android:** download `CardNudge-android-apk` from the latest **Build** run under GitHub Actions, then open it on your phone. You can also run `./gradlew :composeApp:assembleDebug`.

**iPhone:** on a Mac with Xcode, run `brew install xcodegen && cd iosApp && xcodegen generate`. Open `CardNudge.xcodeproj`, choose your team under Signing, and run it on your phone.

## How store alerts work
1. CardNudge gets your location and fetches named shops, restaurants, gas stations, pharmacies and so on within about 1.2 km.
2. It watches the closest ones (99 on Android, 19 on iPhone) plus one larger circle around you.
3. Leaving that circle triggers a new lookup in the background, so the watched stores follow you around.
4. Android alerts after you stay at a store for a minute, so driving past doesn't count. iOS can't wait in the background, so it alerts on entry.
5. Alerts are limited to one every 5 minutes, and once every 3 hours per store.

## Platform limits
- Android allows up to 100 geofences per app. On Xiaomi, Samsung, Oppo and similar phones, also allow autostart and set battery to "No restrictions".
- iOS monitors at most 20 regions per app. Location must be set to **Always**.
