package com.cloudstream.handler;

import com.cloudstream.model.UploadContext;
import com.cloudstream.service.DriveUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives the live WebM byte stream from the browser and feeds it into the
 * Drive resumable upload session.
 *
 * Buffering strategy (Constraint 1):
 *   MediaRecorder emits variable-sized blobs; Drive demands 256 KB-aligned
 *   chunks. So every frame is appended to a per-session buffer, and we only
 *   push when the buffer holds at least one full 256 KB block - carving off
 *   the largest exact multiple and keeping the remainder for next time.
 *
 * Fail-safe (Constraint 3):
 *   afterConnectionClosed fires for BOTH clean closes and abrupt drops, so
 *   whatever is still in memory gets flushed there as the final chunk.
 */
@Component
public class VideoStreamHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoStreamHandler.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Live upload state keyed by WebSocketSession#getId().
     * Concurrent because connects and disconnects land on different threads.
     */
    private final Map<String, UploadContext> activeUploads = new ConcurrentHashMap<>();

    private final DriveUploadService driveService;

    @Value("${cloudstream.upload.chunk-size:262144}")
    private int chunkSize;

    public VideoStreamHandler(DriveUploadService driveService) {
        this.driveService = driveService;
    }

    // ---------------------------------------------------------------- connect

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Stream opened | session={} | remote={}",
                session.getId(), session.getRemoteAddress());

        String fileName = "CloudStream_" + LocalDateTime.now().format(STAMP) + ".webm";
        UploadContext ctx = new UploadContext(fileName);

        try {
            ctx.setUploadUrl(driveService.initiateSession(fileName));
            activeUploads.put(session.getId(), ctx);

            // Tell the client it is safe to start recording. Without this the
            // first blobs could arrive before the Drive session exists.
            session.sendMessage(new TextMessage("READY:" + fileName));

        } catch (Exception e) {
            log.error("Could not open Drive session - closing socket.", e);
            session.close(CloseStatus.SERVER_ERROR.withReason("Drive init failed"));
        }
    }

    // ------------------------------------------------------------- data frame

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UploadContext ctx = activeUploads.get(session.getId());
        if (ctx == null) {
            log.warn("Binary frame for unknown session {} - ignoring.", session.getId());
            return;
        }

        ByteBuffer payload = message.getPayload();
        byte[] incoming = new byte[payload.remaining()];
        payload.get(incoming);

        // Serialise per session: frames arrive on container threads and the
        // buffer is not thread-safe.
        synchronized (ctx) {
            if (ctx.isFinalised()) {
                return;     // late frame after close - drop it
            }

            ByteBuffer.class.getName();          // (no-op, keeps imports honest)
            ctx.getBuffer().write(incoming, 0, incoming.length);

            log.debug("Frame received | session={} | +{} B | buffered={} KB",
                    session.getId(), incoming.length, ctx.getBuffer().size() / 1024);

            if (ctx.getBuffer().size() >= chunkSize) {
                flushAlignedBlocks(ctx);
            }
        }
    }

    /**
     * Carves the largest exact multiple of 256 KB out of the buffer, uploads
     * it, and retains the remainder. Sending a non-aligned chunk before the
     * final one makes Drive reject the entire session.
     */
    private void flushAlignedBlocks(UploadContext ctx) {
        byte[] buffered = ctx.getBuffer().toByteArray();

        int sendable = (buffered.length / chunkSize) * chunkSize;
        if (sendable == 0) {
            return;
        }

        byte[] aligned = new byte[sendable];
        System.arraycopy(buffered, 0, aligned, 0, sendable);

        try {
            driveService.uploadChunk(ctx.getUploadUrl(), aligned, ctx.getBytesUploaded());
            ctx.addBytesUploaded(sendable);

            // Reset and put back only the leftover tail.
            ctx.getBuffer().reset();
            ctx.getBuffer().write(buffered, sendable, buffered.length - sendable);

        } catch (Exception e) {
            // Keep the bytes in the buffer; the final flush may still save them.
            log.error("Chunk upload failed at offset {} - retaining buffer.",
                    ctx.getBytesUploaded(), e);
        }
    }

    // ------------------------------------------------------------- disconnect

    /**
     * THE FAIL-SAFE. Fires on a clean close and on an abrupt drop alike -
     * phone dies, tab killed, Wi-Fi lost. Whatever is still in memory must
     * reach Drive here or the recording is truncated.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // remove() makes the flush idempotent: whichever of the close/error
        // paths runs second finds nothing and does nothing.
        UploadContext ctx = activeUploads.remove(session.getId());
        if (ctx == null) {
            return;
        }

        log.info("Stream closed | session={} | code={} | reason={}",
                session.getId(), status.getCode(), status.getReason());

        synchronized (ctx) {
            if (ctx.isFinalised()) {
                return;
            }
            ctx.markFinalised();

            try {
                // Nothing ever arrived - cancel rather than leave an empty file.
                if (ctx.projectedTotalSize() == 0) {
                    driveService.abortSession(ctx.getUploadUrl());
                    return;
                }

                // Push any remaining aligned blocks first...
                if (ctx.getBuffer().size() >= chunkSize) {
                    flushAlignedBlocks(ctx);
                }

                // ...then send the residual tail as the final chunk. This one
                // is exempt from the 256 KB rule and carries the real total,
                // which is what makes the file playable.
                ByteArrayOutputStream remaining = ctx.getBuffer();
                String fileId = driveService.finaliseUpload(
                        ctx.getUploadUrl(),
                        remaining.toByteArray(),
                        ctx.getBytesUploaded());

                log.info("Recording '{}' saved | fileId={}", ctx.getFileName(), fileId);

            } catch (Exception e) {
                log.error("Finalisation failed for session {} - file may be " +
                        "incomplete on Drive.", session.getId(), e);
            }
        }
    }

    /**
     * Transport-level failure. Spring calls afterConnectionClosed right after,
     * so we only log here and let the single flush path above do the work.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error | session={}", session.getId(), exception);
    }
}