package com.technotramp.unexpectedtracks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ID3v2.3 writer for RPlayer download ZIP entries.
 *
 * <p>The implementation mirrors the browser-id3-writer behavior used by the
 * original RPlayer download manager, but keeps MP3 bytes streaming through the
 * native Android ZIP pipeline.</p>
 */
final class Id3TagWriter {
    private static final int ID3_HEADER_SIZE = 10;
    private static final int FRAME_HEADER_SIZE = 10;
    private static final int PADDING_SIZE = 4096;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF16LE_BOM = new byte[] {(byte) 0xff, (byte) 0xfe};

    private Id3TagWriter() {
    }

    /**
     * Builds an ID3v2.3 tag from RPlayer track metadata.
     *
     * @param metadata track metadata supplied by the JavaScript download bridge
     * @param coverImage optional front cover image bytes
     * @param iconImage optional icon image bytes
     * @return complete ID3v2.3 tag bytes ready to prepend before MP3 data
     * @throws IOException when the tag cannot be encoded
     */
    static byte[] buildTag(JSONObject metadata, byte[] coverImage, byte[] iconImage) throws IOException {
        List<Frame> frames = new ArrayList<>();

        addTextFrame(frames, "TRCK", metadata.optString("trackNumber", ""));
        addCommentFrame(frames, metadata.optJSONObject("comment"));
        addTextFrame(frames, "TIT2", metadata.optString("title", ""));
        addTextFrame(frames, "TPE1", joinStringArray(metadata.optJSONArray("artists"), "/"));
        addTextFrame(frames, "TALB", metadata.optString("album", ""));
        addTextFrame(frames, "TPE2", metadata.optString("albumArtist", ""));
        addTextFrame(frames, "TCON", joinStringArray(metadata.optJSONArray("genres"), ";"));
        addTextFrame(frames, "TPUB", metadata.optString("label", ""));
        addTextFrame(frames, "TCOP", metadata.optString("copyright", ""));
        addTextFrame(frames, "TLAN", metadata.optString("language", ""));
        addNumericFrame(frames, "TBPM", metadata.optString("bpm", ""));
        addTextFrame(frames, "TSRC", metadata.optString("isrc", ""));
        addNumericFrame(frames, "TYER", metadata.optString("year", ""));

        if (coverImage != null && coverImage.length > 0) {
            addPictureFrame(frames, 3, coverImage);
        }

        if (iconImage != null && iconImage.length > 0) {
            addPictureFrame(frames, 1, iconImage);
        }

        ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
        for (Frame frame : frames) {
            frameBytes.write(frame.id.getBytes(StandardCharsets.ISO_8859_1));
            writeUint32(frameBytes, frame.payload.length);
            frameBytes.write(0);
            frameBytes.write(0);
            frameBytes.write(frame.payload);
        }

        byte[] framePayload = frameBytes.toByteArray();
        int tagSize = framePayload.length + PADDING_SIZE;

        ByteArrayOutputStream tag = new ByteArrayOutputStream(ID3_HEADER_SIZE + tagSize);
        tag.write(new byte[] {'I', 'D', '3', 3, 0, 0});
        writeSyncSafe(tag, tagSize);
        tag.write(framePayload);
        tag.write(new byte[PADDING_SIZE]);
        return tag.toByteArray();
    }

    /**
     * Copies MP3 data while stripping an existing ID3v2 tag when present.
     *
     * @param inputStream source MP3 stream
     * @param outputStream destination stream
     * @param buffer reusable copy buffer
     * @return number of MP3 source bytes written after optional tag removal
     * @throws IOException when the source or destination stream fails
     */
    static long copyMp3WithoutExistingTag(InputStream inputStream, OutputStream outputStream, byte[] buffer)
        throws IOException {
        byte[] header = new byte[ID3_HEADER_SIZE];
        int headerBytes = readUpTo(inputStream, header, ID3_HEADER_SIZE);

        if (headerBytes == ID3_HEADER_SIZE && isId3v2Header(header)) {
            skipFully(inputStream, syncSafeToInt(header, 6));
        } else if (headerBytes > 0) {
            outputStream.write(header, 0, headerBytes);
        }

        long written = headerBytes == ID3_HEADER_SIZE && isId3v2Header(header) ? 0 : headerBytes;
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
            written += read;
        }

        return written;
    }

    private static void addTextFrame(List<Frame> frames, String id, String value) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(1);
        payload.write(UTF16LE_BOM);
        payload.write(normalize(value).getBytes(StandardCharsets.UTF_16LE));
        frames.add(new Frame(id, payload.toByteArray()));
    }

    private static void addNumericFrame(List<Frame> frames, String id, String value) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0);
        payload.write(normalize(value).getBytes(WINDOWS_1252));
        frames.add(new Frame(id, payload.toByteArray()));
    }

    private static void addCommentFrame(List<Frame> frames, JSONObject comment) throws IOException {
        String description = "";
        String text = "";
        String language = "eng";

        if (comment != null) {
            description = comment.optString("description", "");
            text = comment.optString("text", "");
            language = normalizeLanguage(comment.optString("language", language));
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(1);
        payload.write(language.getBytes(WINDOWS_1252));
        payload.write(UTF16LE_BOM);
        payload.write(normalize(description).getBytes(StandardCharsets.UTF_16LE));
        payload.write(0);
        payload.write(0);
        payload.write(UTF16LE_BOM);
        payload.write(normalize(text).getBytes(StandardCharsets.UTF_16LE));
        frames.add(new Frame("COMM", payload.toByteArray()));
    }

    private static void addPictureFrame(List<Frame> frames, int pictureType, byte[] imageBytes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0);
        payload.write(detectMimeType(imageBytes).getBytes(WINDOWS_1252));
        payload.write(0);
        payload.write(pictureType);
        payload.write(0);
        payload.write(imageBytes);
        frames.add(new Frame("APIC", payload.toByteArray()));
    }

    private static String joinStringArray(JSONArray array, String delimiter) {
        if (array == null || array.length() == 0) {
            return "";
        }

        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index += 1) {
            String value = array.optString(index, "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }

        return String.join(delimiter, values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeLanguage(String value) {
        String language = normalize(value).trim();
        if (language.length() < 3) {
            return "eng";
        }

        return language.substring(0, 3);
    }

    private static void writeUint32(ByteArrayOutputStream outputStream, int value) {
        outputStream.write((value >>> 24) & 0xff);
        outputStream.write((value >>> 16) & 0xff);
        outputStream.write((value >>> 8) & 0xff);
        outputStream.write(value & 0xff);
    }

    private static void writeSyncSafe(ByteArrayOutputStream outputStream, int value) {
        outputStream.write((value >>> 21) & 0x7f);
        outputStream.write((value >>> 14) & 0x7f);
        outputStream.write((value >>> 7) & 0x7f);
        outputStream.write(value & 0x7f);
    }

    private static int syncSafeToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0x7f) << 21)
            | ((bytes[offset + 1] & 0x7f) << 14)
            | ((bytes[offset + 2] & 0x7f) << 7)
            | (bytes[offset + 3] & 0x7f);
    }

    private static boolean isId3v2Header(byte[] header) {
        return header[0] == 'I'
            && header[1] == 'D'
            && header[2] == '3'
            && header[3] < (byte) 0xff
            && header[4] < (byte) 0xff
            && (header[6] & 0x80) == 0
            && (header[7] & 0x80) == 0
            && (header[8] & 0x80) == 0
            && (header[9] & 0x80) == 0;
    }

    private static int readUpTo(InputStream inputStream, byte[] buffer, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = inputStream.read(buffer, total, length - total);
            if (read < 0) {
                break;
            }

            total += read;
        }

        return total;
    }

    private static void skipFully(InputStream inputStream, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() < 0) {
                    return;
                }

                skipped = 1;
            }

            remaining -= (int) skipped;
        }
    }

    private static String detectMimeType(byte[] bytes) {
        if (bytes.length >= 4
            && (bytes[0] & 0xff) == 0x89
            && bytes[1] == 'P'
            && bytes[2] == 'N'
            && bytes[3] == 'G') {
            return "image/png";
        }

        if (bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }

        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }

        if (bytes.length >= 12
            && bytes[0] == 'R'
            && bytes[1] == 'I'
            && bytes[2] == 'F'
            && bytes[3] == 'F'
            && bytes[8] == 'W'
            && bytes[9] == 'E'
            && bytes[10] == 'B'
            && bytes[11] == 'P') {
            return "image/webp";
        }

        return "image/jpeg";
    }

    private static final class Frame {
        private final String id;
        private final byte[] payload;

        private Frame(String id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }
}
