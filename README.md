# RPlayer Gateway Viewer

Minimalist Android viewer prototype for one fixed RPlayer IPFS address:

`https://ipfs.io/ipfs/bafybeiewkxwysf4jlnhbxs7pd4junvkrrais76qm3qgkpn3en4b2lcqxwm/index.htm`

The viewer does not launch a general-purpose browser. Android WebView loads a local `127.0.0.1` address served by a small proxy. The proxy fetches data from `ipfs.io`, removes the gateway download behavior that prevents rendering, and fills MIME types by file extension.

## Prototype Status

- WebView loads only the local proxy.
- The proxy handles `GET` and `HEAD`.
- The proxy passes the `Range` header for audio seeking.
- The proxy allows only paths under `/ipfs/`.
- External addresses outside the local proxy are blocked in the prototype.
- The Android foreground playback service intentionally remains active while playback is paused for broader device compatibility. Some Android WebView builds may still suspend the audio pipeline after a longer pause.

## Build

The project is prepared for Gradle with the Android plugin, and can also be opened in Android Studio.

```sh
gradle :app:assembleDebug
```

The build command may need Android SDK and Gradle available on the host machine.
