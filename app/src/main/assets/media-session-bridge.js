(function installRPlayerGatewayMediaSessionBridge() {
    'use strict';

    var rootWindow = window;
    var nativeBridge = rootWindow.RPlayerGatewayMediaSessionNative;

    /**
     * Writes a diagnostic message to WebView console and, when available, Android logcat.
     *
     * @param {string} message Diagnostic message.
     */
    function debugLog(message) {
        var fullMessage = '[RPGV media bridge] ' + message;

        if (rootWindow.console && typeof rootWindow.console.log === 'function') {
            rootWindow.console.log(fullMessage);
        }

        if (nativeBridge && typeof nativeBridge.log === 'function') {
            nativeBridge.log(message);
        }
    }

    debugLog('Script started at ' + rootWindow.location.href);

    if (!nativeBridge) {
        rootWindow.setTimeout(installRPlayerGatewayMediaSessionBridge, 500);
        return;
    }

    var state = rootWindow.RPlayerGatewayMediaSessionState || {
        actionHandlers: {},
        installedWindows: [],
        lastMetadataSignature: '',
        lastPlaybackState: ''
    };

    rootWindow.RPlayerGatewayMediaSessionState = state;

    /**
     * Converts an artwork URL from RPlayer metadata to an absolute URL.
     *
     * @param {string} artworkUrl Artwork URL from browser Media Session metadata.
     * @return {string} Absolute artwork URL or the original value when it cannot be resolved.
     */
    function resolveArtworkUrl(artworkUrl) {
        if (!artworkUrl) {
            return '';
        }

        try {
            return new URL(artworkUrl, rootWindow.location.href).href;
        } catch (error) {
            debugLog('Could not resolve artwork URL: ' + error.message);
            return artworkUrl;
        }
    }

    /**
     * Sends metadata from RPlayer's Media Session API to Android.
     *
     * @param {*} metadata MediaMetadata-like object provided by RPlayer.
     */
    function sendMetadataToAndroid(metadata) {
        if (!metadata) {
            return;
        }

        var artwork = Array.isArray(metadata.artwork) && metadata.artwork.length > 0
            ? metadata.artwork[0].src || ''
            : '';
        var title = String(metadata.title || '');
        var artist = String(metadata.artist || '');
        var album = String(metadata.album || '');
        var artworkUrl = resolveArtworkUrl(String(artwork || ''));
        var signature = JSON.stringify([title, artist, album, artworkUrl]);

        if (signature === state.lastMetadataSignature) {
            return;
        }

        state.lastMetadataSignature = signature;
        debugLog('Metadata captured: title="' + title + '", artist="' + artist + '", album="' + album + '", artwork="' + artworkUrl + '"');
        nativeBridge.updateMetadata(title, artist, album, artworkUrl);
    }

    /**
     * Sends playback state to Android's native MediaSession.
     *
     * @param {string} playbackState Browser Media Session playback state.
     */
    function sendPlaybackStateToAndroid(playbackState) {
        var normalizedState = String(playbackState || 'none');

        if (normalizedState === state.lastPlaybackState) {
            return;
        }

        state.lastPlaybackState = normalizedState;
        debugLog('Playback state captured: ' + normalizedState);
        nativeBridge.updatePlaybackState(normalizedState);
    }

    /**
     * Dispatches a native Android media action back to RPlayer's handler.
     *
     * @param {string} action Browser Media Session action name.
     * @param {Object=} details Optional action details.
     */
    function dispatchAction(action, details) {
        var handler = state.actionHandlers[action];

        if (typeof handler !== 'function') {
            debugLog('No RPlayer media action handler registered for: ' + action);
            return;
        }

        debugLog('Dispatching action to RPlayer: ' + action);
        handler(details || {});
    }

    /**
     * Installs a property wrapper for mediaSession.metadata or playbackState.
     *
     * @param {Object} target Media Session object.
     * @param {string} propertyName Property to wrap.
     * @param {*} initialValue Current property value.
     * @param {Function} onChange Callback called when the property changes.
     */
    function installPropertyWrapper(target, propertyName, initialValue, onChange) {
        var currentValue = initialValue;

        try {
            Object.defineProperty(target, propertyName, {
                configurable: true,
                enumerable: true,
                get: function() {
                    return currentValue;
                },
                set: function(value) {
                    currentValue = value;
                    onChange(value);
                }
            });
        } catch (error) {
            debugLog('Could not wrap navigator.mediaSession.' + propertyName + ': ' + error.message);
        }
    }

    /**
     * Installs the bridge into one same-origin window or iframe.
     *
     * @param {Window} targetWindow Window object to patch.
     */
    function installIntoWindow(targetWindow) {
        if (!targetWindow || targetWindow.RPlayerGatewayViewerMediaSessionHook) {
            return;
        }

        var frameUrl = '[unknown]';

        try {
            frameUrl = String(targetWindow.location.href || '[empty]');
        } catch (error) {
            frameUrl = '[inaccessible location]';
        }

        debugLog('Installing into window: ' + frameUrl);

        var mediaSessionExists = !!targetWindow.navigator.mediaSession;
        var mediaSession = targetWindow.navigator.mediaSession || {};
        var originalSetActionHandler = typeof mediaSession.setActionHandler === 'function'
            ? mediaSession.setActionHandler.bind(mediaSession)
            : null;
        var currentMetadata = mediaSession.metadata || null;
        var currentPlaybackState = mediaSession.playbackState || 'none';

        debugLog('navigator.mediaSession present in ' + frameUrl + ': ' + mediaSessionExists);
        debugLog('Initial playbackState in ' + frameUrl + ': ' + currentPlaybackState);

        if (!targetWindow.MediaMetadata) {
            /**
             * Minimal MediaMetadata fallback used when Android WebView does not expose it.
             *
             * @param {Object} metadata Browser Media Session metadata object.
             */
            targetWindow.MediaMetadata = function(metadata) {
                Object.assign(this, metadata || {});
            };
        }

        mediaSession.setActionHandler = function(action, handler) {
            if (typeof handler === 'function') {
                state.actionHandlers[action] = handler;
                debugLog('Action handler captured: ' + action + ' at ' + frameUrl);
                nativeBridge.registerAction(String(action));
            } else {
                delete state.actionHandlers[action];
                debugLog('Action handler removed: ' + action + ' at ' + frameUrl);
            }

            if (originalSetActionHandler) {
                try {
                    originalSetActionHandler(action, handler);
                } catch (error) {
                    debugLog('Native browser media action is not supported: ' + action);
                }
            }
        };

        installPropertyWrapper(mediaSession, 'metadata', currentMetadata, sendMetadataToAndroid);
        installPropertyWrapper(mediaSession, 'playbackState', currentPlaybackState, sendPlaybackStateToAndroid);

        if (!targetWindow.navigator.mediaSession) {
            try {
                Object.defineProperty(targetWindow.navigator, 'mediaSession', {
                    configurable: true,
                    enumerable: true,
                    value: mediaSession
                });
            } catch (error) {
                debugLog('Could not create navigator.mediaSession fallback: ' + error.message);
            }
        }

        if (targetWindow.HTMLMediaElement && targetWindow.HTMLMediaElement.prototype) {
            var mediaPrototype = targetWindow.HTMLMediaElement.prototype;

            if (!mediaPrototype.RPlayerGatewayViewerMediaSessionHook) {
                var originalPlay = mediaPrototype.play;
                var originalPause = mediaPrototype.pause;

                mediaPrototype.play = function() {
                    sendPlaybackStateToAndroid('playing');
                    return originalPlay.apply(this, arguments);
                };

                mediaPrototype.pause = function() {
                    sendPlaybackStateToAndroid('paused');
                    return originalPause.apply(this, arguments);
                };

                mediaPrototype.RPlayerGatewayViewerMediaSessionHook = true;
                debugLog('HTMLMediaElement play/pause hooks installed at ' + frameUrl);
            }
        }

        targetWindow.setInterval(function() {
            if (targetWindow.navigator.mediaSession) {
                var browserPlaybackState = targetWindow.navigator.mediaSession.playbackState;

                sendMetadataToAndroid(targetWindow.navigator.mediaSession.metadata);

                if (browserPlaybackState && browserPlaybackState !== 'none') {
                    sendPlaybackStateToAndroid(browserPlaybackState);
                }
            }
        }, 500);

        targetWindow.RPlayerGatewayViewerMediaSessionHook = true;
        state.installedWindows.push(targetWindow);
        debugLog('Media session bridge installed in window: ' + frameUrl);
    }

    /**
     * Installs the bridge into the top page and every accessible iframe.
     */
    function scanWindows() {
        installIntoWindow(rootWindow);

        for (var index = 0; index < rootWindow.frames.length; index += 1) {
            try {
                installIntoWindow(rootWindow.frames[index]);
            } catch (error) {
                debugLog('Could not access frame for Media Session bridge: ' + error.message);
            }
        }
    }

    rootWindow.RPlayerGatewayMediaSession = {
        dispatchAction: dispatchAction
    };

    scanWindows();
    rootWindow.setInterval(scanWindows, 1000);
}());
