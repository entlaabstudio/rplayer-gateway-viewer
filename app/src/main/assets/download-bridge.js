(function installRPlayerGatewayDownloadBridge() {
    'use strict';

    var chunkSize = 196608;

    if (window.RPlayerGatewayViewerSaveAsHook) {
        return;
    }

    if (typeof window.saveAs !== 'function') {
        window.setTimeout(installRPlayerGatewayDownloadBridge, 500);
        return;
    }

    var originalSaveAs = window.saveAs;

    /**
     * Sends a generated Blob to the Android bridge in Base64 chunks.
     *
     * @param {Blob} blob Generated ZIP blob from RPlayer/FileSaver.
     * @param {string} fileName Suggested output filename.
     */
    function sendBlobToAndroid(blob, fileName) {
        var downloadId = String(Date.now()) + '-' + Math.random().toString(16).slice(2);
        var safeName = fileName || 'unexpected-tracks.zip';
        var mimeType = blob.type || 'application/zip';
        var offset = 0;
        var chunkIndex = 0;

        window.RPlayerGatewayDownloads.beginDownload(downloadId, safeName, mimeType, blob.size || 0);

        /**
         * Reads and forwards the next Blob slice.
         */
        function readNextChunk() {
            if (offset >= blob.size) {
                window.RPlayerGatewayDownloads.finishDownload(downloadId);
                return;
            }

            var slice = blob.slice(offset, Math.min(offset + chunkSize, blob.size));
            var reader = new FileReader();

            reader.onload = function() {
                var result = String(reader.result || '');
                var commaIndex = result.indexOf(',');
                var base64 = commaIndex >= 0 ? result.substring(commaIndex + 1) : result;

                window.RPlayerGatewayDownloads.appendChunk(downloadId, chunkIndex, base64);
                offset += chunkSize;
                chunkIndex += 1;
                readNextChunk();
            };

            reader.onerror = function() {
                window.RPlayerGatewayDownloads.failDownload(downloadId, 'Could not read generated ZIP chunk.');
            };

            reader.readAsDataURL(slice);
        }

        readNextChunk();
    }

    /**
     * Replaces FileSaver saveAs(blob, filename) with the Android save dialog path.
     *
     * @param {*} blob First saveAs argument, expected to be a Blob.
     * @param {string} fileName Suggested output filename.
     * @returns {*} Original saveAs result when the argument is not a Blob.
     */
    window.saveAs = function(blob, fileName) {
        if (blob && typeof blob.slice === 'function' && window.RPlayerGatewayDownloads) {
            sendBlobToAndroid(blob, fileName);
            return undefined;
        }

        return originalSaveAs.apply(this, arguments);
    };

    window.RPlayerGatewayViewerSaveAsHook = true;
}());
