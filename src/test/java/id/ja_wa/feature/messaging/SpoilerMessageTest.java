package id.ja_wa.feature.messaging;

import id.ja_wa.proto.Wa;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpoilerMessageTest {

    @Test
    void spoilerWrapSetsFlagAndWrapsExtendedText() throws Exception {
        Wa.Message inner = MessageEncoder.textWithMentions("secret", List.of("111@s.whatsapp.net"));
        Wa.Message wrapped = MessageEncoder.spoilerWrap(inner);
        assertThat(wrapped.hasSpoilerMessage()).isTrue();
        Wa.Message unwrapped = wrapped.getSpoilerMessage().getMessage();
        assertThat(unwrapped.getExtendedTextMessage().getContextInfo().getIsSpoiler()).isTrue();
        assertThat(unwrapped.getExtendedTextMessage().getContextInfo().getMentionedJidList())
            .containsExactly("111@s.whatsapp.net");

        // Wire round-trip: the new fields survive serialisation with the
        // Baileys field numbers (spoilerMessage=118, isSpoiler=73).
        Wa.Message reparsed = Wa.Message.parseFrom(wrapped.toByteArray());
        assertThat(reparsed.hasSpoilerMessage()).isTrue();
        assertThat(reparsed.getSpoilerMessage().getMessage()
            .getExtendedTextMessage().getContextInfo().getIsSpoiler()).isTrue();
    }

    @Test
    void spoilerWrapFlagsMediaAndWrapsPlainTextWithoutFlag() {
        Wa.Message image = MessageEncoder.imageMessage("https://example.invalid/u", "/p",
            new byte[32], new byte[32], new byte[32], 10, "image/jpeg", "caption");
        Wa.Message wrappedImage = MessageEncoder.spoilerWrap(image);
        assertThat(wrappedImage.getSpoilerMessage().getMessage()
            .getImageMessage().getContextInfo().getIsSpoiler()).isTrue();

        Wa.Message plain = MessageEncoder.text("plain");
        Wa.Message wrappedPlain = MessageEncoder.spoilerWrap(plain);
        assertThat(wrappedPlain.hasSpoilerMessage()).isTrue();
        assertThat(wrappedPlain.getSpoilerMessage().getMessage().getConversation())
            .isEqualTo("plain");

        assertThatThrownBy(() -> MessageEncoder.spoilerWrap(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
