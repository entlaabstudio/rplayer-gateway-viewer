# RPlayer Gateway Viewer

Minimalist Android viewer for fixed RPlayer IPFS music album variants.

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

```sh
./gradlew :app:assembleUnexpectedTracksDebug
./gradlew :app:assembleDreamerDebug
./gradlew :app:assembleKrehkyMechanismusDebug
```

Release builds use the same flavor names:

```sh
./gradlew :app:assembleUnexpectedTracksRelease
./gradlew :app:assembleDreamerRelease
./gradlew :app:assembleKrehkyMechanismusRelease
```

The build command may need Android SDK and Java available on the host machine.

## Release Verification

The `0.1.5-build-37` release APK files are mirrored on IPFS and have public VirusTotal reports:

| APK | IPFS mirror | VirusTotal |
| --- | --- | --- |
| [`unexpectedTracks-0.1.5-build-37-release-signed.apk`](https://github.com/entlaabstudio/rplayer-gateway-viewer/releases/download/v0.1.5/unexpectedTracks-0.1.5-build-37-release-signed.apk) | https://bafybeiaplogko4xh5ejx437rxxtyquykjomvjary2qkcji3qb7cgabnlwi.ipfs.dweb.link?filename=unexpectedTracks-0.1.5-build-37-release-signed.apk | https://www.virustotal.com/gui/file/cfd66dee7e0b0a80e50380fbc1363dae9525cfe88a564c50ceea3ee22224a792/details |
| [`dreamer-0.1.5-build-37-release-signed.apk`](https://github.com/entlaabstudio/rplayer-gateway-viewer/releases/download/v0.1.5/dreamer-0.1.5-build-37-release-signed.apk) | https://bafybeigjnvd2jduytqgaizux57jhbkprjl4pqxfv2b67hn2pvbai23qxye.ipfs.dweb.link?filename=dreamer-0.1.5-build-37-release-signed.apk | https://www.virustotal.com/gui/file/b8c1b3a20d06014a76947c90e1527a226a275c936b4ac4737124eb3d3df24ad9/details |
| [`krehkyMechanismus-0.1.5-build-37-release-signed.apk`](https://github.com/entlaabstudio/rplayer-gateway-viewer/releases/download/v0.1.5/krehkyMechanismus-0.1.5-build-37-release-signed.apk) | https://bafybeidgkzctvp7dzza5i5ngd6yzabejr5pwatoqgiy2fi2rldqmopecg4.ipfs.dweb.link?filename=krehkyMechanismus-0.1.5-build-37-release-signed.apk | https://www.virustotal.com/gui/file/f0b177a40b6183927116527015fe57ab9aacfd44cb46d7933361384b711370a7/details |

## Signing

Release APK files can be signed after a release build with one password prompt:

```sh
scripts/sign-release-apks.sh 0.1.5-build-37
```

The script uses `APKSIGNER`, `KEYSTORE`, and `KEY_ALIAS` environment variables when set. Otherwise it uses the local maintainer defaults.
