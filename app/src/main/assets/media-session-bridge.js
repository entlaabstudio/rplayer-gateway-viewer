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
        directPlayPending: false,
        directPlayPendingAttemptId: 0,
        lastBrowserPlaybackState: '',
        lastMediaElement: null,
        lastMetadataSignature: '',
        lastPlayAttemptId: 0,
        lastPlaybackState: '',
        lastProgressSignature: '',
        lastSeekerStartSeconds: 0
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
     * Handles browser playbackState changes that can be optimistic in WebView.
     *
     * @param {string} playbackState Browser Media Session playback state.
     */
    function sendBrowserPlaybackStateToAndroid(playbackState) {
        var normalizedState = String(playbackState || 'none');

        if (normalizedState === state.lastBrowserPlaybackState) {
            return;
        }

        state.lastBrowserPlaybackState = normalizedState;

        if (normalizedState === 'playing') {
            debugLog('Browser playbackState says playing; waiting for media element confirmation.');
            return;
        }

        sendPlaybackStateToAndroid(normalizedState);
    }

    /**
     * Checks whether a remembered media element still exposes standard controls.
     *
     * @param {HTMLMediaElement|null} mediaElement Media element to validate.
     * @return {boolean} True when the element can still be controlled.
     */
    function isUsableMediaElement(mediaElement) {
        return !!mediaElement
            && typeof mediaElement.play === 'function'
            && typeof mediaElement.pause === 'function';
    }

    /**
     * Stores the current media element and attaches state listeners once.
     *
     * @param {HTMLMediaElement} mediaElement Media element used by RPlayer.
     */
    function rememberMediaElement(mediaElement) {
        if (!mediaElement) {
            return;
        }

        state.lastMediaElement = mediaElement;

        if (mediaElement.RPlayerGatewayViewerMediaElementHook) {
            return;
        }

        mediaElement.addEventListener('playing', function() {
            state.directPlayPending = false;
            state.directPlayPendingAttemptId = 0;
            debugLog('Media element event: playing');
            sendPlaybackStateToAndroid('playing');
        });

        mediaElement.addEventListener('pause', function() {
            state.directPlayPending = false;
            state.directPlayPendingAttemptId = 0;
            debugLog('Media element event: pause');
            sendPlaybackStateToAndroid('paused');
        });

        mediaElement.addEventListener('ended', function() {
            debugLog('Media element event: ended');
            sendPlaybackStateToAndroid('paused');
        });

        mediaElement.addEventListener('waiting', function() {
            debugLog('Media element event: waiting');
        });

        mediaElement.addEventListener('stalled', function() {
            debugLog('Media element event: stalled');
        });

        mediaElement.addEventListener('error', function() {
            var errorCode = mediaElement.error ? mediaElement.error.code : 'unknown';
            state.directPlayPending = false;
            state.directPlayPendingAttemptId = 0;
            debugLog('Media element event: error ' + errorCode);
            sendPlaybackStateToAndroid('paused');
        });

        mediaElement.RPlayerGatewayViewerMediaElementHook = true;
    }

    /**
     * Finds the best media element for direct standard playback control.
     *
     * @return {HTMLMediaElement|null} Last known or first available media element.
     */
    function findMediaElementForControl() {
        if (isUsableMediaElement(state.lastMediaElement)) {
            return state.lastMediaElement;
        }

        for (var index = state.installedWindows.length - 1; index >= 0; index -= 1) {
            var targetWindow = state.installedWindows[index];

            try {
                var mediaElement = targetWindow.document.querySelector('audio, video');

                if (mediaElement) {
                    rememberMediaElement(mediaElement);
                    return mediaElement;
                }
            } catch (error) {
                debugLog('Could not inspect media element for control: ' + error.message);
            }
        }

        return null;
    }

    /**
     * Formats buffered media ranges for diagnostics.
     *
     * @param {HTMLMediaElement} mediaElement Media element to inspect.
     * @return {string} Buffered ranges in seconds.
     */
    function mediaBufferedRanges(mediaElement) {
        var ranges = [];

        try {
            for (var index = 0; index < mediaElement.buffered.length; index += 1) {
                ranges.push(mediaElement.buffered.start(index).toFixed(2) + '-' + mediaElement.buffered.end(index).toFixed(2));
            }
        } catch (error) {
            return 'unavailable: ' + error.message;
        }

        return ranges.length > 0 ? ranges.join(',') : 'empty';
    }

    /**
     * Writes a compact diagnostic snapshot of the current media element.
     *
     * @param {HTMLMediaElement} mediaElement Media element to inspect.
     * @param {string} reason Diagnostic reason.
     */
    function logMediaElementDiagnostics(mediaElement, reason) {
        var source = mediaElement.currentSrc || mediaElement.src || '';
        var errorCode = mediaElement.error ? mediaElement.error.code : 'none';
        var duration = Number.isFinite(mediaElement.duration) ? mediaElement.duration.toFixed(3) : String(mediaElement.duration);

        debugLog(
            'Media element diagnostics [' + reason + ']: '
                + 'paused=' + mediaElement.paused
                + ', ended=' + mediaElement.ended
                + ', readyState=' + mediaElement.readyState
                + ', networkState=' + mediaElement.networkState
                + ', currentTime=' + mediaElement.currentTime.toFixed(3)
                + ', duration=' + duration
                + ', buffered=' + mediaBufferedRanges(mediaElement)
                + ', error=' + errorCode
                + ', source=' + source
        );
    }

    /**
     * Clears a pending direct play attempt when a later user action supersedes it.
     *
     * @param {string} reason Reason written to diagnostics.
     */
    function clearPendingDirectPlay(reason) {
        if (!state.directPlayPending) {
            return;
        }

        debugLog('Direct media element play pending state cleared: ' + reason);
        state.directPlayPending = false;
        state.directPlayPendingAttemptId = 0;
    }

    /**
     * Aligns a stale media element before Android asks RPlayer to resume playback.
     *
     * @param {HTMLMediaElement|null} mediaElement Media element to inspect.
     * @return {boolean} True when a stale element was paused before the play action.
     */
    function pauseStaleMediaElementBeforePlay(mediaElement) {
        if (!isUsableMediaElement(mediaElement)
            || state.lastPlaybackState !== 'paused'
            || mediaElement.paused
        ) {
            return false;
        }

        debugLog('Media element looks stale before external play; pausing it before resume.');
        logMediaElementDiagnostics(mediaElement, 'stale before external play');

        try {
            mediaElement.pause();
            clearPendingDirectPlay('stale media element was paused');
            logMediaElementDiagnostics(mediaElement, 'after stale pause');
            return true;
        } catch (error) {
            debugLog('Could not pause stale media element before play: ' + error.message);
            return false;
        }
    }

    /**
     * Requests playback through the standard HTMLMediaElement API.
     */
    function requestMediaElementPlay() {
        var mediaElement = findMediaElementForControl();

        if (!mediaElement || typeof mediaElement.play !== 'function') {
            debugLog('No media element available for direct play request.');
            return;
        }

        if (state.directPlayPending) {
            debugLog('Direct media element play already pending #' + state.directPlayPendingAttemptId + '; skipping duplicate request.');
            logMediaElementDiagnostics(mediaElement, 'duplicate play skipped #' + state.directPlayPendingAttemptId);
            return;
        }

        var playAttemptId = state.lastPlayAttemptId + 1;
        state.lastPlayAttemptId = playAttemptId;
        state.directPlayPending = true;
        state.directPlayPendingAttemptId = playAttemptId;
        logMediaElementDiagnostics(mediaElement, 'before play #' + playAttemptId);

        try {
            var playResult = mediaElement.play();
            debugLog('Direct media element play requested #' + playAttemptId + '.');

            if (playResult && typeof playResult.then === 'function') {
                playResult.then(function() {
                    if (state.directPlayPendingAttemptId !== playAttemptId) {
                        debugLog('Ignoring superseded direct media element play accepted #' + playAttemptId + '.');
                        return;
                    }

                    state.directPlayPending = false;
                    state.directPlayPendingAttemptId = 0;
                    debugLog('Direct media element play accepted #' + playAttemptId + '.');
                }).catch(function(error) {
                    if (state.directPlayPendingAttemptId !== playAttemptId) {
                        debugLog('Ignoring superseded direct media element play rejected #' + playAttemptId + ': ' + error.message);
                        return;
                    }

                    state.directPlayPending = false;
                    state.directPlayPendingAttemptId = 0;
                    debugLog('Direct media element play rejected #' + playAttemptId + ': ' + error.message);
                    logMediaElementDiagnostics(mediaElement, 'play rejected #' + playAttemptId);
                    sendPlaybackStateToAndroid('paused');
                });

            } else {
                state.directPlayPending = false;
                state.directPlayPendingAttemptId = 0;
                debugLog('Direct media element play returned no Promise #' + playAttemptId + '.');
            }
        } catch (error) {
            state.directPlayPending = false;
            state.directPlayPendingAttemptId = 0;
            debugLog('Direct media element play failed #' + playAttemptId + ': ' + error.message);
            logMediaElementDiagnostics(mediaElement, 'play failed #' + playAttemptId);
            sendPlaybackStateToAndroid('paused');
        }
    }

    /**
     * Finds the RPlayer track seeker in a target window.
     *
     * @param {Window} targetWindow Window object to inspect.
     * @return {HTMLInputElement|null} RPlayer seeker input or null when it is unavailable.
     */
    function findTrackSeeker(targetWindow) {
        var cachedSeeker = targetWindow.RPlayerGatewayViewerTrackSeeker;
        if (cachedSeeker && targetWindow.document.contains(cachedSeeker)) {
            return cachedSeeker;
        }

        var seeker = targetWindow.document.querySelector('input.rplayerSeeker[type="range"]');
        targetWindow.RPlayerGatewayViewerTrackSeeker = seeker;

        return seeker;
    }

    /**
     * Sends the local RPlayer track progress to Android.
     *
     * @param {Window} targetWindow Window object containing RPlayer UI.
     */
    function sendProgressToAndroid(targetWindow) {
        if (!nativeBridge || typeof nativeBridge.updateProgress !== 'function') {
            return;
        }

        var seeker = findTrackSeeker(targetWindow);
        if (!seeker) {
            return;
        }

        var startSeconds = Number(seeker.min || 0);
        var endSeconds = Number(seeker.max || 0);
        var valueSeconds = Number(seeker.value || 0);
        var durationSeconds = Math.max(0, endSeconds - startSeconds);
        var positionSeconds = Math.max(0, Math.min(durationSeconds, valueSeconds - startSeconds));

        if (!Number.isFinite(positionSeconds) || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
            return;
        }

        state.lastSeekerStartSeconds = startSeconds;

        var positionMs = Math.round(positionSeconds * 1000);
        var durationMs = Math.round(durationSeconds * 1000);
        var signature = Math.round(positionSeconds) + '/' + Math.round(durationSeconds);

        if (signature === state.lastProgressSignature) {
            return;
        }

        state.lastProgressSignature = signature;
        nativeBridge.updateProgress(positionMs, durationMs);
    }

    /**
     * Converts local Android track seek details to RPlayer's absolute album timeline.
     *
     * @param {string} action Browser Media Session action name.
     * @param {Object=} details Optional action details.
     * @return {Object} Details object safe to pass to RPlayer.
     */
    function normalizeActionDetails(action, details) {
        var normalizedDetails = details || {};

        if (action === 'seekto' && typeof normalizedDetails.seekTime === 'number') {
            normalizedDetails = Object.assign({}, normalizedDetails, {
                seekTime: state.lastSeekerStartSeconds + normalizedDetails.seekTime
            });
        }

        return normalizedDetails;
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

        if (action === 'play') {
            var mediaElement = findMediaElementForControl();
            var staleElementWasPaused = pauseStaleMediaElementBeforePlay(mediaElement);

            if (state.directPlayPending && !staleElementWasPaused) {
                debugLog('Skipping duplicate RPlayer play action while direct play is pending #' + state.directPlayPendingAttemptId + '.');
                return;
            }
        }

        debugLog('Dispatching action to RPlayer: ' + action);
        handler(normalizeActionDetails(action, details));

        if (action === 'play') {
            requestMediaElementPlay();
        }
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
        installPropertyWrapper(mediaSession, 'playbackState', currentPlaybackState, sendBrowserPlaybackStateToAndroid);

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
                    rememberMediaElement(this);

                    try {
                        var playResult = originalPlay.apply(this, arguments);

                        if (playResult && typeof playResult.catch === 'function') {
                            playResult.catch(function(error) {
                                debugLog('Media element play rejected: ' + error.message);
                                sendPlaybackStateToAndroid('paused');
                            });
                        }

                        return playResult;
                    } catch (error) {
                        debugLog('Media element play failed: ' + error.message);
                        sendPlaybackStateToAndroid('paused');
                        throw error;
                    }
                };

                mediaPrototype.pause = function() {
                    rememberMediaElement(this);
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
                    sendBrowserPlaybackStateToAndroid(browserPlaybackState);
                }

                sendProgressToAndroid(targetWindow);
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
