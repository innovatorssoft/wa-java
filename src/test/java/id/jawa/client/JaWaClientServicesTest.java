package id.jawa.client;

import id.jawa.domain.store.FileAuthStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JaWaClientServicesTest {

    @Test
    void subServicesAreProperlyInitialized(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(client.messages()).isNotNull();
        assertThat(client.groups()).isNotNull();
        assertThat(client.media()).isNotNull();
        assertThat(client.newsletters()).isNotNull();
        assertThat(client.profile()).isNotNull();
        assertThat(client.chats()).isNotNull();

        assertThat(client.messages()).isSameAs(client.messages());
        assertThat(client.groups()).isSameAs(client.groups());
        assertThat(client.media()).isSameAs(client.media());
        assertThat(client.newsletters()).isSameAs(client.newsletters());
        assertThat(client.profile()).isSameAs(client.profile());
        assertThat(client.chats()).isSameAs(client.chats());
    }

    @Test
    void generateMessageIdHasWebPrefixAndValidFormat(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);
        String id1 = client.generateMessageId();
        String id2 = client.messages().generateMessageId();

        assertThat(id1).startsWith("3EB0").hasSize(22);
        assertThat(id2).startsWith("3EB0").hasSize(22);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void setDisappearingTimerReturnsFutureForValidInputs(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.chats().setDisappearingTimer(null, 86400)).isNotNull();
        assertThat(client.groups().setDisappearingTimer(null, 0)).isNotNull();
    }
}
