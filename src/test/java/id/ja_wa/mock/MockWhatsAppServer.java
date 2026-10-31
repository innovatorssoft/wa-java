package id.ja_wa.mock;

import id.ja_wa.domain.model.WaConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MockWhatsAppServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final BlockingQueue<byte[]> receivedPayloads = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean introHeaderVerified = new AtomicBoolean(false);
    private Socket clientSocket;
    private OutputStream clientOut;
    private Thread serverThread;

    public MockWhatsAppServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public String wsUrl() {
        return "ws://127.0.0.1:" + port();
    }

    public boolean isIntroHeaderVerified() {
        return introHeaderVerified.get();
    }

    public void start() {
        serverThread = Thread.ofVirtual().name("mock-wa-server").start(this::runServer);
    }

    private void runServer() {
        try {
            Socket socket = serverSocket.accept();
            synchronized (this) {
                this.clientSocket = socket;
                this.clientOut = socket.getOutputStream();
            }
            InputStream in = socket.getInputStream();

            if (!performWsHandshake(in, clientOut)) {
                return;
            }

            while (running.get() && !socket.isClosed()) {
                byte[] frame = readWsFrame(in);
                if (frame == null) break;

                int offset = 0;
                if (!introHeaderVerified.get() && frame.length >= WaConstants.WA_HEADER.length) {
                    byte[] intro = Arrays.copyOfRange(frame, 0, WaConstants.WA_HEADER.length);
                    if (Arrays.equals(intro, WaConstants.WA_HEADER)) {
                        introHeaderVerified.set(true);
                        offset = WaConstants.WA_HEADER.length;
                    }
                }

                while (offset + 3 <= frame.length) {
                    int len = ((frame[offset] & 0xFF) << 16)
                            | ((frame[offset + 1] & 0xFF) << 8)
                            |  (frame[offset + 2] & 0xFF);
                    offset += 3;
                    if (offset + len <= frame.length) {
                        byte[] payload = Arrays.copyOfRange(frame, offset, offset + len);
                        receivedPayloads.offer(payload);
                        offset += len;
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeClient();
        }
    }

    private boolean performWsHandshake(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        String wsKey = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                wsKey = line.substring("sec-websocket-key:".length()).trim();
            }
        }
        if (wsKey == null) return false;

        try {
            String magic = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String acceptKey = Base64.getEncoder().encodeToString(sha1.digest(magic.getBytes(StandardCharsets.UTF_8)));

            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] readWsFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int opcode = b0 & 0x0F;
        if (opcode == 0x08) {
            return null;
        }

        int b1 = in.read();
        if (b1 == -1) return null;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;

        if (length == 126) {
            int len1 = in.read();
            int len2 = in.read();
            if (len1 == -1 || len2 == -1) return null;
            length = ((len1 & 0xFF) << 8) | (len2 & 0xFF);
        } else if (length == 127) {
            byte[] lenBytes = in.readNBytes(8);
            if (lenBytes.length < 8) return null;
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (lenBytes[i] & 0xFF);
            }
        }

        byte[] mask = null;
        if (masked) {
            mask = in.readNBytes(4);
            if (mask.length < 4) return null;
        }

        byte[] payload = in.readNBytes((int) length);
        if (payload.length < length) return null;

        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return payload;
    }

    public synchronized void sendWaFrame(byte[] payload) throws IOException {
        if (clientOut == null || clientSocket == null || clientSocket.isClosed()) {
            throw new IOException("client not connected");
        }

        byte[] framed = new byte[3 + payload.length];
        framed[0] = (byte) ((payload.length >> 16) & 0xFF);
        framed[1] = (byte) ((payload.length >> 8) & 0xFF);
        framed[2] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, framed, 3, payload.length);

        int len = framed.length;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x82);

        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) {
                buf.write((len >> (i * 8)) & 0xFF);
            }
        }
        buf.write(framed);
        clientOut.write(buf.toByteArray());
        clientOut.flush();
    }

    public byte[] receivePayload(long timeout, TimeUnit unit) throws InterruptedException {
        return receivedPayloads.poll(timeout, unit);
    }

    public synchronized void closeClient() {
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        running.set(false);
        closeClient();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }
}
