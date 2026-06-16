# RPlayer Gateway Viewer

Minimalist Android viewer for fixed RPlayer IPFS music album variants.

**Note:** The first player load can take longer because the app has to fetch album files from the public IPFS gateway and fill the local cache. Later starts are usually much faster.

Current IPFS album roots:

| Flavor | Artist | Album | Year | Genres | IPFS entry |
| --- | --- | --- | --- | --- | --- |
| `unexpectedTracks` | Technotramp | Unexpected Tracks | 2023 | Techno-Tramping | `https://ipfs.io/ipfs/bafybeiewkxwysf4jlnhbxs7pd4junvkrrais76qm3qgkpn3en4b2lcqxwm/index.htm` |
| `dreamer` | Michal Diviš | Dreamer | 2021 | Metal, Progressive rock, Djent | `https://ipfs.io/ipfs/bafybeiaknjru5kws3exdyleahfleoh3ud3bp5ve7l3e7yeqmzo3sp7rcii/index.htm` |
| `krehkyMechanismus` | První Hoře | Křehký mechanismus pozemského štěstí | 2017 | Progressive rock, Metal, Alternative, Avantgarde | `https://ipfs.io/ipfs/bafybeigmjwx26qgubv6ddciwuwbatpgcdrw6mnhgz4qy5bd55nb3trosfu/index.htm` |

The viewer does not launch a general-purpose browser. Android WebView loads a local `127.0.0.1` address served by a small proxy. The proxy fetches data from `ipfs.io`, removes the gateway download behavior that prevents rendering, and fills MIME types by file extension.

## Status

- WebView loads only the local proxy.
- The proxy handles `GET` and `HEAD`.
- The proxy passes the `Range` header for audio seeking.
- The proxy allows only paths under the configured variant IPFS album root.
- User-selected external `http`, `https`, and `mailto` links open outside WebView.
- External subresources outside the local proxy are blocked.
- The Android foreground playback service intentionally remains active while playback is paused for broader device compatibility. Some Android WebView builds may still suspend the audio pipeline after a longer pause.

## Build

The project is prepared for Gradle with the Android plugin, and can also be opened in Android Studio.

Build all debug APK variants:

```sh
./gradlew assembleDebug
```

Build all release APK variants:

```sh
./gradlew assembleRelease
```

Individual debug builds use the flavor names:

```sh
./gradlew :app:assembleUnexpectedTracksDebug
./gradlew :app:assembleDreamerDebug
./gradlew :app:assembleKrehkyMechanismusDebug
```

Individual release builds use the same flavor names:

```sh
./gradlew :app:assembleUnexpectedTracksRelease
./gradlew :app:assembleDreamerRelease
./gradlew :app:assembleKrehkyMechanismusRelease
```

The build command may need Android SDK and Java available on the host machine.

## Signing

Release APK files can be signed after a release build with one password prompt:

```sh
scripts/sign-release-apks.sh 0.6.3-build-51
```

The script uses `APKSIGNER`, `KEYSTORE`, and `KEY_ALIAS` environment variables when set. Otherwise it uses the local maintainer defaults.
