package com.cloudstream.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Drives the Google Drive v3 resumable upload protocol over raw HTTP.
 *
 * The protocol in three acts:
 *
 *   1. INITIATE  POST .../files?uploadType=resumable
 *                Body = JSON metadata. Response carries a 'Location' header
 *                which is the session URL. Everything after this is a PUT
 *                to that URL.
 *
 *   2. STREAM    PUT <sessionUrl>
 *                Content-Range: bytes <start>-<end>/*
 *                The '*' means "total size unknown - more is coming".
 *                Drive replies 308 (Resume Incomplete). Every chunk sent this
 *                way MUST be an exact multiple of 256 KB.
 *
 *   3. FINALISE  PUT <sessionUrl>
 *                Content-Range: bytes <start>-<end>/<total>
 *                A real total instead of '*' tells Drive the file is complete.
 *                This final chunk is EXEMPT from the 256 KB rule.
 *                Drive replies 200/201 with the file metadata.
 */
@Service
public class DriveUploadService {

    private static final Logger log = LoggerFactory.getLogger(DriveUploadService.class);

    private static final String INITIATE_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable";
    private static final String FILES_URL =
            "https://www.googleapis.com/drive/v3/files";

    private static final String MIME_TYPE = "video/webm";

    private final DriveAuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * followRedirects(NEVER) is essential. Drive signals "chunk accepted,
     * keep going" with HTTP 308, which is officially Permanent Redirect.
     * An auto-following client would chase it and break the upload.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${google.drive.target-folder-id}")
    private String targetFolderId;

    @Value("${google.drive.make-public:false}")
    private boolean makePublic;

    public DriveUploadService(DriveAuthService authService) {
        this.authService = authService;
    }

    // ------------------------------------------------------------ 1. INITIATE

    /**
     * Opens a resumable session and returns its URL.
     *
     * Note X-Upload-Content-Type but deliberately NO X-Upload-Content-Length:
     * omitting the length is precisely how you declare "unknown size".
     */
    public String initiateSession(String fileName) throws IOException, InterruptedException {

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("name", fileName);
        metadata.put("mimeType", MIME_TYPE);
        if (targetFolderId != null && !targetFolderId.isBlank()
                && !targetFolderId.startsWith("PASTE_")) {
            metadata.putArray("parents").add(targetFolderId);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INITIATE_URL))
                .header("Authorization", "Bearer " + authService.getAccessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", MIME_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Drive session initiation failed: HTTP "
                    + response.statusCode() + " - " + response.body());
        }

        String sessionUrl = response.headers()
                .firstValue("Location")
                .orElseThrow(() -> new IOException(
                        "Drive returned 200 but no Location header."));

        log.info("Resumable session opened for '{}'", fileName);
        return sessionUrl;
    }

    // -------------------------------------------------------------- 2. STREAM

    /**
     * Pushes one 256 KB-aligned chunk. The caller guarantees alignment.
     *
     * @param offset bytes already committed; this chunk starts here
     */
    public void uploadChunk(String sessionUrl, byte[] chunk, long offset)
            throws IOException, InterruptedException {

        long start = offset;
        long end = offset + chunk.length - 1;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sessionUrl))
                .header("Content-Range", "bytes " + start + "-" + end + "/*")
                .timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        // 308 = "Resume Incomplete" = chunk stored, send the next one.
        // A 200/201 here would mean Drive thinks we're finished - a bug.
        if (response.statusCode() != 308) {
            throw new IOException("Chunk upload failed at offset " + offset
                    + ": HTTP " + response.statusCode() + " - " + response.body());
        }

        log.debug("Chunk committed | bytes {}-{} | size={} KB",
                start, end, chunk.length / 1024);
    }

    // ------------------------------------------------------------ 3. FINALISE

    /**
     * Sends the last chunk (any size, including zero) with a real total,
     * converting the open session into a closed, playable Drive file.
     *
     * @param finalChunk residual bytes; may be empty
     * @param offset     bytes already committed
     * @return the Drive file ID
     */
    public String finaliseUpload(String sessionUrl, byte[] finalChunk, long offset)
            throws IOException, InterruptedException {

        long total = offset + finalChunk.length;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(sessionUrl))
                .timeout(Duration.ofSeconds(60));

        if (finalChunk.length == 0) {
            // Nothing left to send, but the session still needs closing.
            // 'bytes */<total>' with an empty body is the documented way
            // to finalise without contributing further data.
            builder.header("Content-Range", "bytes */" + total)
                    .PUT(HttpRequest.BodyPublishers.noBody());
        } else {
            long start = offset;
            long end = total - 1;
            builder.header("Content-Range", "bytes " + start + "-" + end + "/" + total)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(finalChunk));
        }

        HttpResponse<String> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Finalisation failed: HTTP "
                    + response.statusCode() + " - " + response.body());
        }

        JsonNode body = mapper.readTree(response.body());
        String fileId = body.path("id").asText();

        log.info("Upload finalised | fileId={} | totalBytes={} ({} MB)",
                fileId, total, total / (1024 * 1024));

        if (makePublic) {
            grantLinkAccess(fileId);
        }
        return fileId;
    }

    // --------------------------------------------------------------- cleanup

    /**
     * Cancels a session that produced no data, so we don't leave a zero-byte
     * file in Drive when a client connects and immediately disconnects.
     */
    public void abortSession(String sessionUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sessionUrl))
                    .DELETE()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Empty session aborted.");
        } catch (Exception e) {
            // Abandoned sessions expire on Google's side within a week anyway.
            log.warn("Could not abort session cleanly: {}", e.getMessage());
        }
    }

    /**
     * Adds an "anyone with the link can view" permission. Convenience for
     * demos only - it is not part of the upload protocol.
     */
    private void grantLinkAccess(String fileId) {
        try {
            ObjectNode permission = mapper.createObjectNode();
            permission.put("role", "reader");
            permission.put("type", "anyone");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FILES_URL + "/" + fileId + "/permissions"))
                    .header("Authorization", "Bearer " + authService.getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(permission.toString()))
                    .build();

            http.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Shareable link: https://drive.google.com/file/d/{}/view", fileId);
        } catch (Exception e) {
            log.warn("Could not set public permission: {}", e.getMessage());
        }
    }
}