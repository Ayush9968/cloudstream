/**
 * CloudStream frontend recorder.
 *
 * Captures the camera with MediaRecorder, which emits a Blob every
 * TIMESLICE ms, and forwards each Blob down a WebSocket as a binary frame.
 *
 * Why the stream survives a crash: MediaRecorder puts the WebM header
 * (EBML + Segment + Tracks) in the FIRST blob only; every later blob is a
 * self-contained Cluster. So the concatenation of blobs 1.N is a valid
 * WebM file for any N - which is exactly what makes a truncated upload
 * still playable.
 */

const TIMESLICE_MS = 2000;   // matches the 2-second chunk requirement

const preview    = document.getElementById('preview');
const startBtn   = document.getElementById('startBtn');
const stopBtn    = document.getElementById('stopBtn');
const statusEl   = document.getElementById('status');
const chunkCount = document.getElementById('chunkCount');
const byteCount  = document.getElementById('byteCount');
const switchBtn  = document.getElementById('switchCameraBtn'); // New button reference

let socket   = null;
let recorder = null;
let stream   = null;
let chunks   = 0;
let bytes    = 0;
let currentFacingMode = 'user'; // Defaults to front/selfie camera

function log(message) {
    statusEl.textContent = message;
    console.log('[CloudStream]', message);
}

/** Picks the best codec this browser actually supports. */
function pickMimeType() {
    const candidates = [
        'video/webm; codecs=vp9,opus',
        'video/webm; codecs=vp8,opus',
        'video/webm'
    ];
    for (const type of candidates) {
        if (MediaRecorder.isTypeSupported(type)) return type;
    }
    return '';   // let the browser decide
}

/** Builds ws:// or wss:// to match the page's own protocol. */
function socketUrl() {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${window.location.host}/video-stream`;
}

/** Initializes or restarts the camera stream for preview */
async function initCamera() {
    if (stream) {
        stream.getTracks().forEach(track => track.stop());
    }
    try {
        stream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: currentFacingMode, width: { ideal: 1280 } },
            audio: true
        });
        preview.srcObject = stream;
    } catch (err) {
        log('Camera access denied or unavailable: ' + err.message);
    }
}

// Initialize camera on page load so you can preview before recording
window.addEventListener('DOMContentLoaded', initCamera);

// Handle camera switching
if (switchBtn) {
    switchBtn.addEventListener('click', () => {
        currentFacingMode = (currentFacingMode === 'user') ? 'environment' : 'user';
        initCamera();
    });
}

startBtn.addEventListener('click', async () => {
    startBtn.disabled = true;
    if (switchBtn) switchBtn.disabled = true; // Lock camera switcher during recording

    // Fallback in case camera failed to load initially
    if (!stream) {
        await initCamera();
        if (!stream) {
            startBtn.disabled = false;
            if (switchBtn) switchBtn.disabled = false;
            return;
        }
    }

    log('Connecting to server...');
    socket = new WebSocket(socketUrl());
    socket.binaryType = 'arraybuffer';

    socket.onopen = () => log('Connected. Waiting for Drive session...');

    socket.onmessage = (event) => {
        // Server sends READY:<filename> once the Drive session is live.
        if (typeof event.data === 'string' && event.data.startsWith('READY:')) {
            beginRecording(event.data.substring(6));
        }
    };

    socket.onerror = () => log('WebSocket error - is the server running?');

    socket.onclose = (event) => {
        log(`Connection closed (code ${event.code}). Server has flushed ${bytes} bytes to Drive.`);
        cleanUp();
    };
});

function beginRecording(fileName) {
    const mimeType = pickMimeType();
    recorder = new MediaRecorder(stream, {
        mimeType: mimeType,
        videoBitsPerSecond: 2_500_000
    });

    recorder.ondataavailable = (event) => {
        if (event.data.size === 0) return;
        if (!socket || socket.readyState !== WebSocket.OPEN) return;

        if (socket.bufferedAmount > 8 * 1024 * 1024) {
            console.warn('Socket congested:', socket.bufferedAmount, 'bytes queued');
        }

        socket.send(event.data);

        chunks += 1;
        bytes  += event.data.size;
        chunkCount.textContent = chunks;
        byteCount.textContent  = (bytes / 1024).toFixed(0) + ' KB';
    };

    recorder.start(TIMESLICE_MS);

    stopBtn.disabled = false;
    log(`Recording to "${fileName}". Sending every ${TIMESLICE_MS / 1000}s.`);
}

stopBtn.addEventListener('click', () => {
    stopBtn.disabled = true;
    log('Stopping...');

    if (recorder && recorder.state !== 'inactive') {
        recorder.onstop = () => {
            setTimeout(() => socket && socket.close(1000, 'User stopped'), 300);
        };
        recorder.stop();
    } else if (socket) {
        socket.close(1000, 'User stopped');
    }
});

function cleanUp() {
    if (stream) {
        stream.getTracks().forEach(track => track.stop());
        stream = null;
    }
    startBtn.disabled = false;
    stopBtn.disabled  = true;
    if (switchBtn) switchBtn.disabled = false; // Unlock camera switcher

    // Restart camera preview for the next recording
    initCamera();
}