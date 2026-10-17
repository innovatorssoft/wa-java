package id.jawa.protocol.connection;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.Receipt;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.mock.MockWhatsAppServer;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryDecoder;
import id.jawa.protocol.codec.BinaryEncoder;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class E2EConnectionIntegrationTest {

    private MockWhatsAppServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWhatsAppServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void jaWaClientInitiatesHandshakeWithValidClientHelloProtobuf(@TempDir Path tempDir) throws Exception {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("e2e_test.session"));
        JaWaClient client = new JaWaClient(store)
                .wsUrl(server.wsUrl())
                .autoReconnect(false);

        CountDownLatch clientStarted = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        Thread.ofVirtual().name("test-client").start(() -> {
            try {
                clientStarted.countDown();
                client.connect();
            } catch (Throwable t) {
                connectError.set(t);
            }
        });

        assertThat(clientStarted.await(2, TimeUnit.SECONDS)).isTrue();

        byte[] clientHelloBytes = server.receivePayload(5, TimeUnit.SECONDS);
        assertThat(clientHelloBytes).isNotNull();

        assertThat(server.isIntroHeaderVerified()).isTrue();

        Wa.HandshakeMessage handshakeMsg = Wa.HandshakeMessage.parseFrom(clientHelloBytes);
        assertThat(handshakeMsg.hasClientHello()).isTrue();
        assertThat(handshakeMsg.getClientHello().getEphemeral().size()).isEqualTo(32);

        client.close();
    }

    @Test
    void postHandshakeTransportExchangesEncryptedBinaryStanzas() throws Exception {
        byte[] clientKey = new byte[32];
        byte[] serverKey = new byte[32];
        Arrays.fill(clientKey, (byte) 0x11);
        Arrays.fill(serverKey, (byte) 0x22);

        NoiseTransport clientTransport = new NoiseTransport(clientKey, serverKey);
        NoiseTransport serverTransport = new NoiseTransport(serverKey, clientKey);

        BinaryNode receiptNode = new BinaryNode("receipt",
                Map.of("id", "TEST_MSG_123", "from", "628111@s.whatsapp.net", "type", "read"),
                null);
        byte[] plainXml = BinaryEncoder.encode(receiptNode);

        byte[] ciphertext = serverTransport.encrypt(plainXml);
        byte[] decrypted = clientTransport.decrypt(ciphertext);

        BinaryNode decodedNode = BinaryDecoder.decode(decrypted);
        assertThat(decodedNode.tag()).isEqualTo("receipt");
        assertThat(decodedNode.attr("id")).isEqualTo("TEST_MSG_123");
        assertThat(decodedNode.attr("type")).isEqualTo("read");

        Receipt receipt = Receipt.parse(decodedNode);
        assertThat(receipt).isNotNull();
        assertThat(receipt.type()).isEqualTo("read");
        assertThat(receipt.msgIds()).containsExactly("TEST_MSG_123");
    }

    @Test
    void handlesServerAbruptCloseGracefully() throws Exception {
        try (FrameSocket socket = new FrameSocket(null, server.wsUrl())) {
            socket.connect();
            assertThat(socket.isOpen()).isTrue();

            server.closeClient();

            AtomicBoolean closed = new AtomicBoolean(false);
            try {
                socket.receive(2_000);
            } catch (IllegalStateException e) {
                closed.set(true);
            }
            assertThat(closed.get()).isTrue();
        }
    }
}
