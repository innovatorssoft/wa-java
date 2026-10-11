package id.jawa.protocol.connection;

import id.jawa.mock.MockWhatsAppServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FrameSocketIntegrationTest {

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
    void connectsAndExchangesFramedPayloadsWithIntroHeader() throws Exception {
        try (FrameSocket socket = new FrameSocket(null, server.wsUrl())) {
            socket.connect();
            assertThat(socket.isOpen()).isTrue();

            byte[] outbound = "Hello Mock WhatsApp Server".getBytes(StandardCharsets.UTF_8);
            socket.send(outbound);

            byte[] receivedByServer = server.receivePayload(5, TimeUnit.SECONDS);
            assertThat(receivedByServer).isNotNull();
            assertThat(receivedByServer).isEqualTo(outbound);
            assertThat(server.isIntroHeaderVerified()).isTrue();

            byte[] serverPayload = "Server Handshake / Stanza Response".getBytes(StandardCharsets.UTF_8);
            server.sendWaFrame(serverPayload);

            byte[] receivedByClient = socket.receive(5_000);
            assertThat(receivedByClient).isNotNull();
            assertThat(receivedByClient).isEqualTo(serverPayload);
        }
    }

    @Test
    void handlesSequentialMultipleFrames() throws Exception {
        try (FrameSocket socket = new FrameSocket(null, server.wsUrl())) {
            socket.connect();

            for (int i = 0; i < 5; i++) {
                byte[] clientMsg = ("Message-" + i).getBytes(StandardCharsets.UTF_8);
                socket.send(clientMsg);
                byte[] serverGot = server.receivePayload(2, TimeUnit.SECONDS);
                assertThat(serverGot).isEqualTo(clientMsg);

                byte[] serverMsg = ("Ack-" + i).getBytes(StandardCharsets.UTF_8);
                server.sendWaFrame(serverMsg);
                byte[] clientGot = socket.receive(2_000);
                assertThat(clientGot).isEqualTo(serverMsg);
            }
        }
    }
}
