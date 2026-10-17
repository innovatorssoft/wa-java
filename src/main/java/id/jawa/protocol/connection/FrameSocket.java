package id.jawa.protocol.connection;


import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import id.jawa.domain.model.WaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket transport framing layer for WhatsApp Web protocol.
 * <p>
 * Handles WebSocket connection lifecycle, frame length prefixing (3-byte big-endian integers),
 * protocol preamble intro headers (ED header), and thread-safe incoming frame buffering.
 */
public final class FrameSocket implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FrameSocket.class);

    private static final byte[] CLOSED_SENTINEL = new byte[0];

    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final AtomicBoolean introSent = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte[] introHeader;
    private final byte[] readBuf = new byte[WaConstants.FRAME_MAX_SIZE];
    private int readBufLen = 0;
    private final String wsUrl;
    private WebSocket ws;

    /**
     * Creates a new FrameSocket instance.
     *
     * @param routingInfo optional edge routing info bytes
     * @param wsUrl target WebSocket endpoint URL
     */
    public FrameSocket(byte[] routingInfo, String wsUrl) {
        this.introHeader = (routingInfo == null || routingInfo.length == 0)
            ? WaConstants.WA_HEADER
            : buildRoutedHeader(routingInfo);
        this.wsUrl = (wsUrl == null || wsUrl.isBlank()) ? WaConstants.WS_URL : wsUrl;
    }

    public FrameSocket(byte[] routingInfo) {
        this(routingInfo, WaConstants.WS_URL);
    }

    public FrameSocket() {
        this(null, WaConstants.WS_URL);
    }

    public String wsUrl() {
        return wsUrl;
    }

    public void connect() throws IOException, WebSocketException {
        if (ws != null) throw new IllegalStateException("already connected");
        ws = new WebSocketFactory()
                .setConnectionTimeout(15_000)
                .createSocket(wsUrl)
                .addHeader("Origin", WaConstants.ORIGIN)
                .addListener(new Adapter());
        ws.connect();
        LOG.debug("WS connected to {}", wsUrl);
    }

    public synchronized void send(byte[] payload) {
        if (ws == null || closed.get()) throw new IllegalStateException("socket not open");
        if (payload.length > WaConstants.FRAME_MAX_SIZE) {
            throw new IllegalArgumentException("frame too large: " + payload.length);
        }
        int introLen = introSent.compareAndSet(false, true) ? introHeader.length : 0;
        byte[] frame = new byte[introLen + 3 + payload.length];
        if (introLen > 0) System.arraycopy(introHeader, 0, frame, 0, introLen);
        frame[introLen]     = (byte) ((payload.length >> 16) & 0xFF);
        frame[introLen + 1] = (byte) ((payload.length >> 8)  & 0xFF);
        frame[introLen + 2] = (byte) ( payload.length        & 0xFF);
        System.arraycopy(payload, 0, frame, introLen + 3, payload.length);
        ws.sendBinary(frame);
    }

    public byte[] receive(long timeoutMs) throws InterruptedException {
        byte[] f = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (f == CLOSED_SENTINEL) throw new IllegalStateException("socket closed");
        if (f == null && closed.get()) throw new IllegalStateException("socket closed");
        return f;
    }

    public boolean isOpen() { return ws != null && !closed.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ws != null) ws.disconnect();
    }

    private static byte[] buildRoutedHeader(byte[] routingInfo) {

        int rl = routingInfo.length;
        byte[] h = new byte[7 + rl + WaConstants.WA_HEADER.length];
        h[0] = 'E'; h[1] = 'D';
        h[2] = 0; h[3] = 1;
        h[4] = (byte) ((rl >> 16) & 0xFF);
        h[5] = (byte) ((rl >> 8)  & 0xFF);
        h[6] = (byte) ( rl        & 0xFF);
        System.arraycopy(routingInfo, 0, h, 7, rl);
        System.arraycopy(WaConstants.WA_HEADER, 0, h, 7 + rl, WaConstants.WA_HEADER.length);
        return h;
    }

    private final class Adapter extends WebSocketAdapter {
        @Override
        public void onBinaryMessage(WebSocket s, byte[] binary) {
            appendAndDispatch(binary);
        }
        @Override
        public void onTextMessage(WebSocket s, String text) {
            LOG.warn("unexpected WS text frame: {}", text);
        }
        @Override
        public void onError(WebSocket s, WebSocketException cause) {
            LOG.warn("WS error", cause);
        }
        @Override
        public void onDisconnected(WebSocket s, WebSocketFrame serverCloseFrame,
                                   WebSocketFrame clientCloseFrame, boolean closedByServer) {
            LOG.debug("WS disconnected (closedByServer={})", closedByServer);
            closed.set(true);

            incoming.offer(CLOSED_SENTINEL);
        }
    }

    private synchronized void appendAndDispatch(byte[] chunk) {
        if (readBufLen + chunk.length > readBuf.length) {

            throw new IllegalStateException("read buffer overflow");
        }
        System.arraycopy(chunk, 0, readBuf, readBufLen, chunk.length);
        readBufLen += chunk.length;

        int offset = 0;
        while (true) {
            if (readBufLen - offset < 3) break;
            int len = ((readBuf[offset] & 0xFF) << 16)
                    | ((readBuf[offset + 1] & 0xFF) << 8)
                    |  (readBuf[offset + 2] & 0xFF);
            if (readBufLen - offset - 3 < len) break;
            byte[] frame = new byte[len];
            System.arraycopy(readBuf, offset + 3, frame, 0, len);
            incoming.add(frame);
            offset += 3 + len;
        }
        if (offset > 0) {
            int remaining = readBufLen - offset;
            if (remaining > 0) System.arraycopy(readBuf, offset, readBuf, 0, remaining);
            readBufLen = remaining;
        }
    }
}
