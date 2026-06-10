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
    var nativeStreamingDownloadActive = false;
    var activeNativeDownloads = null;

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

    /**
     * Converts an object or array-like collection to a plain array of values.
     *
     * @param {*} collection RPlayer collection to normalize.
     * @returns {Array} Collection values.
     */
    function collectionValues(collection) {
        if (!collection) {
            return [];
        }

        return Object.keys(collection).map(function(key) {
            return collection[key];
        });
    }

    /**
     * Checks whether a download checkbox is enabled.
     *
     * @param {string} checkboxId DOM id of the related checkbox.
     * @param {boolean} fallback Value used when the checkbox is not present.
     * @returns {boolean} Current checkbox state.
     */
    function isChecked(checkboxId, fallback) {
        if (!checkboxId) {
            return fallback !== false;
        }

        var checkbox = window.jQuery && window.jQuery('#' + checkboxId);
        if (!checkbox || checkbox.length === 0) {
            return fallback !== false;
        }

        return checkbox.is(':checked');
    }

    /**
     * Checks whether one RPlayer bundle option is enabled.
     *
     * @param {string} optionId DOM id of the bundle option checkbox.
     * @returns {boolean} true when the option is checked.
     */
    function isBundleOptionChecked(optionId) {
        return isChecked('rplayerCheckboxDownloadBundleOptions_' + optionId, true);
    }

    /**
     * Resolves a RPlayer source path against the current proxied document.
     *
     * @param {string} sourcePath Relative or absolute RPlayer asset path.
     * @returns {string} Absolute local proxy URL.
     */
    function resolveSourceUrl(sourcePath) {
        return new URL(sourcePath, document.baseURI).href;
    }

    /**
     * Joins ZIP path segments into one relative archive path.
     *
     * @param {...string} parts Path segments.
     * @returns {string} ZIP entry path.
     */
    function zipPath() {
        return joinZipPath(Array.prototype.slice.call(arguments));
    }

    /**
     * Disables the RPlayer download controls while native ZIP streaming is active.
     *
     * @param {object} downloads RPlayer downloads instance.
     */
    function disableDownloadUi(downloads) {
        if (!window.jQuery) {
            return;
        }

        window.jQuery('#rplayerDownloads .button.rplayerDownloadSubmit').addClass('loading disabled');
        window.jQuery('#rplayerDownloads input').attr('disabled', 'disabled');
        window.jQuery(downloads.rplayerCfg.app.htmlSelectors.mainWindow + ' .downloadsButton').css({
            opacity: '0.3'
        });
    }

    /**
     * Restores the RPlayer download controls after native ZIP streaming ends.
     *
     * @param {object} downloads RPlayer downloads instance.
     */
    function restoreDownloadUi(downloads) {
        if (!window.jQuery || !downloads) {
            return;
        }

        window.jQuery('#rplayerDownloads .button.rplayerDownloadSubmit').removeClass('loading disabled');
        window.jQuery('#rplayerDownloads input').removeAttr('disabled');
        window.jQuery(downloads.rplayerCfg.app.htmlSelectors.mainWindow + ' .downloadsButton').css({
            opacity: '1'
        });
    }

    /**
     * Updates the existing Semantic UI download progress bar.
     *
     * @param {number} completedEntries Number of finished native ZIP entries.
     * @param {number} expectedEntries Number of planned native ZIP entries.
     * @param {string} currentEntry Recently completed ZIP entry path.
     */
    function updateNativeDownloadProgress(completedEntries, expectedEntries, currentEntry) {
        if (!window.jQuery) {
            return;
        }

        var progress = window.jQuery('#rplayerDownloadsProgress');
        var percent = expectedEntries > 0
            ? Math.max(0, Math.min(100, Math.round((completedEntries / expectedEntries) * 100)))
            : 0;

        progress.addClass('active').css({
            opacity: '1'
        }).attr('title', currentEntry || 'Preparing ZIP archive');

        if (typeof progress.progress === 'function') {
            progress.progress({
                percent: percent
            });
        } else {
            progress.find('.bar').css({
                width: percent + '%'
            });
        }
    }

    /**
     * Completes the native download UI state after Android finishes or fails the ZIP.
     */
    function finishNativeDownloadUi() {
        nativeStreamingDownloadActive = false;
        restoreDownloadUi(activeNativeDownloads);
        activeNativeDownloads = null;

        if (window.jQuery) {
            window.jQuery('#rplayerDownloadsProgress').removeClass('active');
        }
    }

    /**
     * Synchronizes RPlayer's download flags with the current checkbox state.
     *
     * @param {object} downloads RPlayer downloads instance.
     */
    function syncDownloadSelection(downloads) {
        collectionValues(downloads.download.mp3).forEach(function(entry) {
            entry.download = isChecked(entry.checkboxId, entry.download);
        });

        collectionValues(downloads.download.others).forEach(function(group) {
            collectionValues(group.files).forEach(function(entry) {
                entry.download = isChecked(entry.checkboxId, entry.download);
            });
        });

        collectionValues(downloads.download.unsorted).forEach(function(entry) {
            entry.download = isChecked(entry.checkboxId, entry.download);
        });

        downloads.download.coverImage.download = isBundleOptionChecked('CoverImage');
        collectionValues(downloads.download.slideshowImages).forEach(function(entry) {
            entry.download = isBundleOptionChecked('SlideshowImages');
        });
    }

    /**
     * Adds one source file entry to a native ZIP plan.
     *
     * @param {object[]} plan Native ZIP entry plan.
     * @param {string} path ZIP entry path.
     * @param {string} sourcePath RPlayer source path.
     */
    function addSourceEntry(plan, path, sourcePath) {
        if (!sourcePath) {
            return;
        }

        plan.push({
            type: 'source',
            path: path,
            sourceUrl: resolveSourceUrl(sourcePath)
        });
    }

    /**
     * Adds one UTF-8 text entry to a native ZIP plan.
     *
     * @param {object[]} plan Native ZIP entry plan.
     * @param {string} path ZIP entry path.
     * @param {string} text Text content.
     */
    function addTextEntry(plan, path, text) {
        plan.push({
            type: 'text',
            path: path,
            text: text || ''
        });
    }

    /**
     * Builds the list of native ZIP entries requested by RPlayer.
     *
     * @param {object} downloads RPlayer downloads instance.
     * @param {string} baseFolderName Root folder name inside the ZIP archive.
     * @returns {object[]} Native ZIP entry plan.
     */
    function buildNativeZipPlan(downloads, baseFolderName) {
        var plan = [];

        collectionValues(downloads.download.mp3).forEach(function(entry) {
            if (!entry.download || !entry.srcFile) {
                return;
            }

            addSourceEntry(plan, zipPath(baseFolderName, entry.fileName), entry.srcFile);

            if (isBundleOptionChecked('TracksImages') && entry.srcImgFile) {
                addSourceEntry(
                    plan,
                    zipPath(baseFolderName, 'images', entry.mediaName + '.' + downloads.getFileExtension(entry.srcImgFile)),
                    entry.srcImgFile
                );
            }
        });

        if (isBundleOptionChecked('LyricsFile')) {
            collectionValues(downloads.lyrics).forEach(function(entry) {
                addTextEntry(plan, zipPath(baseFolderName, 'info', 'lyrics', entry.fileName), entry.html);
            });
        }

        if (isBundleOptionChecked('InfoFile')) {
            collectionValues(downloads.story).forEach(function(entry) {
                addTextEntry(plan, zipPath(baseFolderName, 'info', 'tracks', entry.fileName), entry.html);
            });
        }

        if (isBundleOptionChecked('AlbumInfoFile')) {
            addTextEntry(plan, zipPath(baseFolderName, 'info', 'index.htm'), downloads.getAlbumInfoHtml());
        }

        collectionValues(downloads.download.others).forEach(function(group) {
            collectionValues(group.files).forEach(function(entry) {
                if (!entry.download) {
                    return;
                }

                addSourceEntry(
                    plan,
                    zipPath(baseFolderName, 'attachments', group.mediaName, entry.folder, entry.fileName),
                    entry.srcFile
                );
            });
        });

        collectionValues(downloads.download.unsorted).forEach(function(entry) {
            if (!entry.download) {
                return;
            }

            addSourceEntry(plan, zipPath(baseFolderName, 'attachments', entry.folder, entry.fileName), entry.srcFile);
        });

        if (downloads.download.coverImage.download) {
            addSourceEntry(plan, zipPath(baseFolderName, downloads.download.coverImage.fileName), downloads.download.coverImage.srcFile);
        }

        collectionValues(downloads.download.slideshowImages).forEach(function(entry) {
            if (!entry.download) {
                return;
            }

            addSourceEntry(plan, zipPath(baseFolderName, 'images', 'slideshow', entry.fileName), entry.srcFile);
        });

        collectionValues(downloads.justZipIt).forEach(function(entry) {
            if (entry.srcFile) {
                addSourceEntry(plan, zipPath(baseFolderName, entry.folder, entry.fileName), entry.srcFile);
            } else if (entry.data && typeof entry.data === 'string') {
                addTextEntry(plan, zipPath(baseFolderName, entry.folder, entry.fileName), entry.data);
            }
        });

        return plan;
    }

    /**
     * Enqueues one planned native ZIP entry into Android.
     *
     * @param {string} downloadId Native ZIP session identifier.
     * @param {object} entry Planned ZIP entry.
     */
    function enqueueNativeZipEntry(downloadId, entry) {
        if (entry.type === 'source') {
            window.RPlayerGatewayDownloads.log('Native ZIP source entry queued: ' + entry.path + ' <- ' + entry.sourceUrl);
            window.RPlayerGatewayDownloads.addNativeZipSourceEntry(downloadId, entry.path, entry.sourceUrl);
            return;
        }

        window.RPlayerGatewayDownloads.log('Native ZIP text entry queued: ' + entry.path + ' (' + String(entry.text || '').length + ' chars)');
        window.RPlayerGatewayDownloads.addNativeZipTextEntry(downloadId, entry.path, entry.text || '');
    }

    /**
     * Builds one complete RPlayer ZIP archive by streaming source files natively.
     *
     * @param {object} downloads RPlayer downloads instance.
     */
    function runNativeStreamingDownload(downloads) {
        var downloadId;
        var baseFolderName;
        var plan;

        if (nativeStreamingDownloadActive) {
            window.RPlayerGatewayDownloads.log('Native streaming download is already active; repeated click ignored.');
            return;
        }

        nativeStreamingDownloadActive = true;
        activeNativeDownloads = downloads;
        downloadId = createDownloadId();
        baseFolderName = downloads.rplayerCfg.album.info.composer
            + ' - '
            + downloads.rplayerCfg.album.info.year
            + ' - '
            + downloads.rplayerCfg.album.info.name;

        disableDownloadUi(downloads);
        syncDownloadSelection(downloads);

        try {
            plan = buildNativeZipPlan(downloads, baseFolderName);
            updateNativeDownloadProgress(0, plan.length, 'Preparing ZIP archive');
            window.RPlayerGatewayDownloads.beginNativeZip(downloadId);
            window.RPlayerGatewayDownloads.setNativeZipExpectedEntries(downloadId, plan.length);
            window.RPlayerGatewayDownloads.log('Native streaming download started for: ' + baseFolderName + ', entries=' + plan.length);

            plan.forEach(function(entry) {
                enqueueNativeZipEntry(downloadId, entry);
            });

            window.RPlayerGatewayDownloads.finishNativeZip(downloadId, baseFolderName + '.zip');
        } catch (error) {
            nativeStreamingDownloadActive = false;
            restoreDownloadUi(downloads);
            activeNativeDownloads = null;
            window.RPlayerGatewayDownloads.failDownload(downloadId, 'Native streaming download failed: ' + (error && error.message ? error.message : error));
        }
    }

    window.RPlayerGatewayNativeDownloadProgress = {
        /**
         * Receives native ZIP progress from Android.
         *
         * @param {number} completedEntries Number of finished native ZIP entries.
         * @param {number} expectedEntries Number of planned native ZIP entries.
         * @param {string} currentEntry Recently completed ZIP entry path.
         */
        update: function(completedEntries, expectedEntries, currentEntry) {
            if (completedEntries < 0) {
                finishNativeDownloadUi();
                return;
            }

            updateNativeDownloadProgress(completedEntries, expectedEntries, currentEntry || 'Preparing ZIP archive');

            if (expectedEntries > 0 && completedEntries >= expectedEntries && !currentEntry) {
                finishNativeDownloadUi();
                window.RPlayerGatewayDownloads.log('Native streaming download finished.');
            }
        }
    };

    /**
     * Replaces RPlayer's RAM-heavy downloadAction with a native streaming ZIP plan.
     */
    function installNativeDownloadActionHook() {
        var downloads = window.RPlayerGatewayViewerDownloads;
        if (!downloads) {
            window.setTimeout(installNativeDownloadActionHook, 500);
            return;
        }

        if (downloads.rplayerGatewayNativeDownloadActionHook) {
            return;
        }

        downloads.rplayerGatewayOriginalDownloadAction = downloads.downloadAction;
        downloads.downloadAction = function() {
            runNativeStreamingDownload(downloads);
        };
        downloads.rplayerGatewayNativeDownloadActionHook = true;
        window.RPlayerGatewayDownloads.log('Native streaming downloadAction hook installed.');
    }

    window.RPlayerGatewayViewerSaveAsHook = true;
    installNativeZipHook();
    installNativeDownloadActionHook();
}());
