(function installRPlayerGatewayDownloadBridge() {
    'use strict';

    var chunkSize = 196608;
    var base64SliceSize = 32768;

    if (window.RPlayerGatewayViewerSaveAsHook) {
        return;
    }

    if (typeof window.saveAs !== 'function') {
        window.setTimeout(installRPlayerGatewayDownloadBridge, 500);
        return;
    }

    var originalSaveAs = window.saveAs;

    /**
     * Creates a short unique identifier for one Android-side download session.
     *
     * @returns {string} Download session identifier.
     */
    function createDownloadId() {
        return String(Date.now()) + '-' + Math.random().toString(16).slice(2);
    }

    /**
     * Sends a generated Blob to the Android bridge in Base64 chunks.
     *
     * @param {Blob} blob Generated ZIP blob from RPlayer/FileSaver.
     * @param {string} fileName Suggested output filename.
     */
    function sendBlobToAndroid(blob, fileName) {
        var downloadId = createDownloadId();
        var safeName = fileName || 'rplayer-download.zip';
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
     * Joins ZIP path segments using forward slashes.
     *
     * @param {string[]} parts Path segments collected by folder().
     * @returns {string} Relative ZIP entry path.
     */
    function joinZipPath(parts) {
        return parts
            .filter(function(part) {
                return part !== undefined && part !== null && String(part).trim() !== '';
            })
            .map(function(part) {
                return String(part).replace(/^\/+|\/+$/g, '');
            })
            .filter(function(part) {
                return part !== '';
            })
            .join('/');
    }

    /**
     * Converts a byte slice to Base64 without building one huge argument list.
     *
     * @param {Uint8Array} bytes Binary bytes to encode.
     * @returns {string} Base64-encoded bytes.
     */
    function bytesToBase64(bytes) {
        var binary = '';
        for (var offset = 0; offset < bytes.length; offset += base64SliceSize) {
            var slice = bytes.subarray(offset, Math.min(offset + base64SliceSize, bytes.length));
            binary += String.fromCharCode.apply(null, slice);
        }

        return window.btoa(binary);
    }

    /**
     * Sends one binary ZIP entry to Android in Base64 chunks.
     *
     * @param {string} downloadId Native ZIP session identifier.
     * @param {string} path Relative ZIP entry path.
     * @param {ArrayBuffer|ArrayBufferView} data Binary entry payload.
     */
    function sendBinaryZipEntry(downloadId, path, data) {
        var bytes;
        if (data instanceof ArrayBuffer) {
            bytes = new Uint8Array(data);
        } else if (ArrayBuffer.isView(data)) {
            bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        } else {
            throw new Error('Unsupported native ZIP binary entry type.');
        }

        window.RPlayerGatewayDownloads.beginNativeZipEntry(downloadId, path);
        for (var offset = 0; offset < bytes.length; offset += chunkSize) {
            var chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length));
            window.RPlayerGatewayDownloads.appendNativeZipEntryChunk(downloadId, bytesToBase64(chunk));
        }
        window.RPlayerGatewayDownloads.finishNativeZipEntry(downloadId);
    }

    /**
     * Minimal folder facade compatible with the subset of JSZip used by RPlayer.
     *
     * @param {NativeZipArchive} archive Parent native ZIP archive facade.
     * @param {string[]} parts Current folder path segments.
     * @constructor
     */
    function NativeZipFolder(archive, parts) {
        this.archive = archive;
        this.parts = parts || [];
    }

    /**
     * Creates a nested folder facade.
     *
     * @param {string} name Folder name.
     * @returns {NativeZipFolder} Nested folder facade.
     */
    NativeZipFolder.prototype.folder = function(name) {
        if (name === undefined || name === null || String(name).trim() === '') {
            return this;
        }

        return new NativeZipFolder(this.archive, this.parts.concat([name]));
    };

    /**
     * Adds one file to the Android-side native ZIP stream.
     *
     * @param {string} name File name relative to this folder.
     * @param {string|ArrayBuffer|ArrayBufferView} data File content.
     * @returns {NativeZipFolder} This folder facade for JSZip-style chaining.
     */
    NativeZipFolder.prototype.file = function(name, data) {
        var path = joinZipPath(this.parts.concat([name]));

        if (typeof data === 'string') {
            window.RPlayerGatewayDownloads.log('Native ZIP text entry: ' + path + ' (' + data.length + ' chars)');
            window.RPlayerGatewayDownloads.addNativeZipTextEntry(this.archive.downloadId, path, data);
            return this;
        }

        window.RPlayerGatewayDownloads.log('Native ZIP binary entry: ' + path);
        sendBinaryZipEntry(this.archive.downloadId, path, data);
        return this;
    };

    /**
     * Minimal JSZip replacement that streams RPlayer ZIP entries to Android.
     *
     * @constructor
     */
    function NativeZipArchive() {
        this.downloadId = createDownloadId();
        this.archive = this;
        this.parts = [];
        window.RPlayerGatewayDownloads.beginNativeZip(this.downloadId);
    }

    NativeZipArchive.prototype = Object.create(NativeZipFolder.prototype);
    NativeZipArchive.prototype.constructor = NativeZipArchive;

    /**
     * Finalizes the JSZip-compatible flow with a sentinel consumed by saveAs().
     *
     * @returns {Promise<object>} Promise resolving to a native ZIP sentinel.
     */
    NativeZipArchive.prototype.generateAsync = function() {
        return Promise.resolve({
            rplayerGatewayNativeZip: true,
            downloadId: this.downloadId
        });
    };

    /**
     * Installs the native ZIP facade after RPlayer's JSZip dependency is available.
     */
    function installNativeZipHook() {
        if (window.RPlayerGatewayViewerNativeZipHook) {
            return;
        }

        if (!window.RPlayerGatewayDownloads || typeof window.JSZip !== 'function') {
            window.setTimeout(installNativeZipHook, 500);
            return;
        }

        window.RPlayerGatewayViewerOriginalJSZip = window.JSZip;
        window.JSZip = NativeZipArchive;
        window.RPlayerGatewayViewerNativeZipHook = true;
        window.RPlayerGatewayDownloads.log('Native ZIP hook installed.');
    }

    /**
     * Replaces FileSaver saveAs(blob, filename) with the Android save dialog path.
     *
     * @param {*} blob First saveAs argument, expected to be a Blob or native ZIP sentinel.
     * @param {string} fileName Suggested output filename.
     * @returns {*} Original saveAs result when the argument cannot be handled here.
     */
    window.saveAs = function(blob, fileName) {
        if (blob && blob.rplayerGatewayNativeZip && window.RPlayerGatewayDownloads) {
            window.RPlayerGatewayDownloads.finishNativeZip(blob.downloadId, fileName || 'rplayer-download.zip');
            return undefined;
        }

        if (blob && typeof blob.slice === 'function' && window.RPlayerGatewayDownloads) {
            sendBlobToAndroid(blob, fileName);
            return undefined;
        }

        return originalSaveAs.apply(this, arguments);
    };

    window.RPlayerGatewayViewerSaveAsHook = true;
    installNativeZipHook();
}());
