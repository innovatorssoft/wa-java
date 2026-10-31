package id.ja_wa.feature.media;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploaderUrlTest {

    @Test
    void regularUploadUrlUsesMmsPath() {
        byte[] hash = new byte[32];
        for (int i = 0; i < 32; i++) hash[i] = (byte) i;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        String url = MediaUploader.uploadUrl("mmg.whatsapp.net", "auth-token",
            hash, MediaCrypto.MediaType.IMAGE, false);
        assertThat(url).isEqualTo("https://mmg.whatsapp.net/mms/image/" + token
            + "?auth=auth-token&token=" + token);
        assertThat(url).doesNotContain("server_thumb_gen");
    }

    @Test
    void newsletterUploadUrlUsesNewsletterPathAndThumbFlag() {
        byte[] hash = new byte[32];
        String url = MediaUploader.uploadUrl("mmg.whatsapp.net", "auth-token",
            hash, MediaCrypto.MediaType.VIDEO, true);
        assertThat(url).contains("/newsletter/newsletter-video/");
        assertThat(url).contains("&server_thumb_gen=1");
        String docUrl = MediaUploader.uploadUrl("mmg.whatsapp.net", "auth-token",
            hash, MediaCrypto.MediaType.DOCUMENT, true);
        assertThat(docUrl).contains("/newsletter/newsletter-document/");
        String audioUrl = MediaUploader.uploadUrl("mmg.whatsapp.net", "auth-token",
            hash, MediaCrypto.MediaType.AUDIO, true);
        assertThat(audioUrl).contains("/newsletter/newsletter-audio/");
    }

    @Test
    void uploadUrlRejectsUnknownType() {
        assertThatThrownBy(() -> MediaUploader.uploadUrl("h", "a", new byte[32], null, false))
            .isInstanceOf(Exception.class);
    }
}
