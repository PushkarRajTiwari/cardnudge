# CardNudge

Tells you which credit card to use when you arrive at a place you've saved. It's built once with **Kotlin Multiplatform + Compose Multiplatform** and runs on Android and iPhone.

- **Cards:** add your cards and their reward rate (%) for each kind of spend.
- **Places:** save shops you visit. When you arrive, you get a notification naming the best card and the next best.
- **Best picks:** your top card for every category.

Everything stays on the phone: no account, no bank linking and no network calls.

## How the code is shared

| Where | What | Shared? |
|---|---|---|
| `composeApp/src/commonMain` | Models, recommender, storage, every screen (Compose) | ✅ Android + iOS |
| `composeApp/src/commonTest` | Unit tests for the rules and storage | ✅ run on both |
| `composeApp/src/androidMain` | Geofences (Play Services), notifications, permissions | Android only |
| `composeApp/src/iosMain` | Region monitoring (CoreLocation), notifications | iOS only |
| `iosApp/` | ~50 lines of Swift: app entry point hosting the shared UI | iOS only |

The OS-specific code is small and sits behind one interface, `platform/Platform.kt`.

## Memory and efficiency
- There is one `Repository` per process, exposing `StateFlow`s. It never holds an Activity or a view controller. Android singletons keep only the application context.
- Screens collect state with `collectAsStateWithLifecycle`, so collection stops when the app is in the background.
- Coroutines run in composition scopes, and a pending location request is cancelled when its sheet closes.
- Lists are `LazyColumn` with stable keys. Gradients, shapes and the backdrop are built once, not on every frame.
- Best-card picks are computed in one pass, and only when cards change.
- Geofences are re-registered only when places or permissions change.

## Build and install
**Android:** download `CardNudge-android-apk` from the latest **Build** run under GitHub Actions, then open it on your phone. You can also run `./gradlew :composeApp:assembleDebug`.

**iPhone:** on a Mac with Xcode, run `brew install xcodegen && cd iosApp && xcodegen generate`. Open `CardNudge.xcodeproj`, choose your team under Signing, and run it on your phone.

## Platform limits
- Android allows up to 100 geofences per app. On Xiaomi, Samsung, Oppo and similar phones, also allow autostart and set battery to "No restrictions".
- iOS monitors at most 20 regions per app. Location must be set to **Always**.
