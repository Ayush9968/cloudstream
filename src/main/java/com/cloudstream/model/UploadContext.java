package com.cloudstream.model;

import lombok.Setter;

import java.io.ByteArrayOutputStream;

/**
 * Per-connection upload state.
 * Spring creates exactly ONE handler instance shared across every client, so
 * none of this can live in handler fields. One UploadContext exists per
 * WebSocket session, held in a ConcurrentHashMap keyed by session ID.
 */
public class UploadContext {

    /** Name the recording will carry in Drive. */
    private final String fileName;

    /** Resumable session URL returned by Drive's initiation call. */
    private String uploadUrl;

    /** Bytes received but not yet aligned to a 256 KB boundary. */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /** Running count of bytes already committed. Drives the Content-Range header. */
    private long bytesUploaded = 0L;

    /**
     * Guards against a double flush. Both handleTransportError and
     * afterConnectionClosed fire on an abrupt drop; finalising twice
     * would corrupt the file.
     */
    private boolean finalised = false;

    public UploadContext(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public ByteArrayOutputStream getBuffer() {
        return buffer;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public void addBytesUploaded(long count) {
        this.bytesUploaded += count;
    }

    public boolean isFinalised() {
        return finalised;
    }

    public void markFinalised() {
        this.finalised = true;
    }

    /** Total size the file will have once the residual buffer is flushed. */
    public long projectedTotalSize() {
        return bytesUploaded + buffer.size();
    }
}