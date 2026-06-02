# RPlayer Gateway Viewer

Minimalist Android viewer for fixed RPlayer IPFS album variants.

Current variants:

- `unexpectedTracks`: `https://ipfs.io/ipfs/bafybeiewkxwysf4jlnhbxs7pd4junvkrrais76qm3qgkpn3en4b2lcqxwm/index.htm`
- `dreamer`: `https://ipfs.io/ipfs/bafybeifdc2rfwjxe7hdntvgkabir7ocbikbdamylhv7ojewtnrm3pdvbri/index.htm`

The viewer does not launch a general-purpose browser. Android WebView loads a local `127.0.0.1` address served by a small proxy. The proxy fetches data from `ipfs.io`, removes the gateway download behavior that prevents rendering, and fills MIME types by file extension.

## Status

- WebView loads only the local proxy.
- The proxy handles `GET` and `HEAD`.
- The proxy passes the `Range` header for audio seeking.
- The proxy allows only paths under the configured variant IPFS album root.
- External addresses outside the local proxy are blocked.
- The Android foreground playback service intentionally remains active while playback is paused for broader device compatibility. Some Android WebView builds may still suspend the audio pipeline after a longer pause.

## Build

The project is prepared for Gradle with the Android plugin, and can also be opened in Android Studio.

```sh
./gradlew :app:assembleUnexpectedTracksDebug
./gradlew :app:assembleDreamerDebug
```

Release builds use the same flavor names:

```sh
./gradlew :app:assembleUnexpectedTracksRelease
./gradlew :app:assembleDreamerRelease
```

The build command may need Android SDK and Java available on the host machine.
