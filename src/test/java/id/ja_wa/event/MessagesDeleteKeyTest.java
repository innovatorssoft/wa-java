package id.ja_wa.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesDeleteKeyTest {

    @Test
    void participantAltIsBackwardsCompatible() {
        MessagesDelete.MessageKey legacy =
            new MessagesDelete.MessageKey("m1", "123@g.us", false, "111@s.whatsapp.net");
        assertThat(legacy.participantAlt()).isNull();
        assertThat(legacy.participant()).isEqualTo("111@s.whatsapp.net");

        MessagesDelete.MessageKey withAlt = new MessagesDelete.MessageKey(
            "m1", "123@g.us", false, "111@lid", "111@s.whatsapp.net");
        assertThat(withAlt.participant()).isEqualTo("111@lid");
        assertThat(withAlt.participantAlt()).isEqualTo("111@s.whatsapp.net");

        MessagesDelete delete = new MessagesDelete(java.util.List.of(withAlt));
        assertThat(delete.keys()).containsExactly(withAlt);
    }
}
