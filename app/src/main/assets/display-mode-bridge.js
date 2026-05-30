(function installRPlayerGatewayDisplayModeBridge() {
    'use strict';

    var rootWindow = window;
    var nativeBridge = rootWindow.RPlayerGatewayDisplayModeNative;

    /**
     * Writes a diagnostic message to WebView console and Android logcat.
     *
     * @param {string} message Diagnostic message.
     */
    function debugLog(message) {
        var fullMessage = '[RPGV display bridge] ' + message;

        if (rootWindow.console && typeof rootWindow.console.log === 'function') {
            rootWindow.console.log(fullMessage);
        }

        if (nativeBridge && typeof nativeBridge.log === 'function') {
            nativeBridge.log(message);
        }
    }

    if (!nativeBridge) {
        rootWindow.setTimeout(installRPlayerGatewayDisplayModeBridge, 500);
        return;
    }

    /**
     * Toggles Android fullscreen mode through the native bridge.
     *
     * @param {Event} event Click event from an RPlayer fullscreen icon.
     */
    function toggleFullscreen(event) {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        nativeBridge.toggleFullscreen();
    }

    /**
     * Installs click handlers on RPlayer fullscreen icons in one same-origin window.
     *
     * @param {Window} targetWindow Window object to inspect.
     */
    function installIntoWindow(targetWindow) {
        if (!targetWindow || !targetWindow.document) {
            return;
        }

        var icons = targetWindow.document.querySelectorAll(
            '.fullScreen.expand.arrows.icon, .fullScreen.expand.arrows.alternate.icon'
        );

        for (var index = 0; index < icons.length; index += 1) {
            var icon = icons[index];
            if (icon.RPlayerGatewayDisplayModeHook) {
                continue;
            }

            icon.RPlayerGatewayDisplayModeHook = true;
            icon.addEventListener('click', toggleFullscreen, true);
            debugLog('Fullscreen icon hooked.');
        }
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
                debugLog('Could not access frame for Display Mode bridge: ' + error.message);
            }
        }
    }

    debugLog('Script started at ' + rootWindow.location.href);
    scanWindows();
    rootWindow.setInterval(scanWindows, 1000);
}());
