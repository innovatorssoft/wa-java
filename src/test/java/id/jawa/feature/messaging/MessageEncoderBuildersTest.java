package id.jawa.feature.messaging;

import id.jawa.proto.Wa;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEncoderBuildersTest {

    private static byte[] bytes(int value, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) value;
        return out;
    }

    @Test
    void albumMessageDeclaresCountsAndRejectsSingleMember() {
        Wa.Message album = MessageEncoder.albumMessage(2, 1);
        assertThat(album.hasAlbumMessage()).isTrue();
        assertThat(album.getAlbumMessage().getExpectedImageCount()).isEqualTo(2);
        assertThat(album.getAlbumMessage().getExpectedVideoCount()).isEqualTo(1);
        assertThatThrownBy(() -> MessageEncoder.albumMessage(1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEncoder.albumMessage(0, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void albumAssociationLinksMemberToParentKey() {
        Wa.Message member = MessageEncoder.imageMessage("https://example.invalid/u", "/p",
            bytes(1, 32), bytes(2, 32), bytes(3, 32), 10, "image/jpeg", "hi");
        Wa.MessageKey parent = Wa.MessageKey.newBuilder()
            .setRemoteJid("123@g.us").setFromMe(true).setId("ALBUM1").build();
        Wa.Message linked = MessageEncoder.withAlbumAssociation(member, parent, 2);
        assertThat(linked.hasMessageContextInfo()).isTrue();
        var assoc = linked.getMessageContextInfo().getMessageAssociation();
        assertThat(assoc.getAssociationType())
            .isEqualTo(Wa.MessageAssociation.AssociationType.MEDIA_ALBUM);
        assertThat(assoc.getParentMessageKey().getId()).isEqualTo("ALBUM1");
        assertThat(assoc.getMessageIndex()).isEqualTo(2);
        assertThatThrownBy(() -> MessageEncoder.withAlbumAssociation(member, parent, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quizMessageUsesV5WithCorrectAnswer() {
        Wa.Message quiz = MessageEncoder.quizMessage("Capital of France?",
            List.of("Paris", "London", "Berlin"), 0);
        assertThat(quiz.hasPollCreationMessageV5()).isTrue();
        assertThat(quiz.hasPollCreationMessageV3()).isFalse();
        var pcm = quiz.getPollCreationMessageV5();
        assertThat(pcm.getPollType()).isEqualTo(Wa.Message.PollType.QUIZ);
        assertThat(pcm.getSelectableOptionsCount()).isEqualTo(1);
        assertThat(pcm.getCorrectAnswer().getOptionName()).isEqualTo("Paris");
        assertThat(pcm.getOptionsList()).hasSize(3);
        assertThat(quiz.getMessageContextInfo().getMessageSecret().size()).isEqualTo(32);
        assertThatThrownBy(() -> MessageEncoder.quizMessage("Q", List.of("only"), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEncoder.quizMessage("Q", List.of("a", "b"), 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mentionAllSetsNonJidMentionsAndDeduplicates() {
        // Note: List.of rejects null, so the null/blank cases are supplied
        // via a mutable list here.
        List<String> participants = new java.util.ArrayList<>();
        participants.add("111@s.whatsapp.net");
        participants.add("111@s.whatsapp.net");
        participants.add("  ");
        participants.add(null);
        participants.add("222@s.whatsapp.net");
        Wa.Message msg = MessageEncoder.mentionAllMessage("hello", participants);
        var ctx = msg.getExtendedTextMessage().getContextInfo();
        assertThat(ctx.getMentionedJidList())
            .containsExactly("111@s.whatsapp.net", "222@s.whatsapp.net");
        assertThat(ctx.getNonJidMentions()).isEqualTo(1);
    }

    @Test
    void groupMentionsCarryGroupJidAndSubject() {
        Wa.Message msg = MessageEncoder.textWithGroupMentions("hi",
            List.of(new MessageEncoder.GroupMentionInput("123@g.us", "Team")));
        var mentions = msg.getExtendedTextMessage().getContextInfo().getGroupMentionsList();
        assertThat(mentions).hasSize(1);
        assertThat(mentions.get(0).getGroupJid()).isEqualTo("123@g.us");
        assertThat(mentions.get(0).getGroupSubject()).isEqualTo("Team");
    }

    @Test
    void contactsArrayMessageHoldsMultipleContacts() {
        Wa.Message msg = MessageEncoder.contactsArrayMessage("Team", List.of(
            new MessageEncoder.ContactInput("Alice", "BEGIN:VCARD\nFN:Alice\nEND:VCARD"),
            new MessageEncoder.ContactInput("Bob", "BEGIN:VCARD\nFN:Bob\nEND:VCARD")));
        var arr = msg.getContactsArrayMessage();
        assertThat(arr.getDisplayName()).isEqualTo("Team");
        assertThat(arr.getContactsList()).hasSize(2);
        assertThat(arr.getContacts(1).getDisplayName()).isEqualTo("Bob");
        assertThatThrownBy(() -> MessageEncoder.contactsArrayMessage("x", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lottieWrapUsesFutureProofWrapperAndFlagOverloadSetsFlag() {
        Wa.Message sticker = MessageEncoder.stickerMessage("https://example.invalid/u", "/p",
            bytes(1, 32), bytes(2, 32), bytes(3, 32), 10, false);
        Wa.Message wrapped = MessageEncoder.wrapAsLottie(sticker);
        assertThat(wrapped.hasLottieStickerMessage()).isTrue();
        assertThat(wrapped.getLottieStickerMessage().getMessage()).isEqualTo(sticker);
        Wa.Message flagged = MessageEncoder.stickerMessage("https://example.invalid/u", "/p",
            bytes(1, 32), bytes(2, 32), bytes(3, 32), 10, false, true);
        assertThat(flagged.getStickerMessage().getIsLottie()).isTrue();
        assertThatThrownBy(() -> MessageEncoder.wrapAsLottie(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void richCodeAndTableMessagesCarrySubmessages() {
        Wa.Message code = MessageEncoder.richCodeMessage("java", List.of("System.out.println(1);"));
        var rich = code.getRichResponseMessage();
        assertThat(rich.getSubmessagesList()).hasSize(1);
        assertThat(rich.getSubmessages(0).getMessageType())
            .isEqualTo(Wa.AIRichResponseSubMessageType.AI_RICH_RESPONSE_CODE);
        assertThat(rich.getSubmessages(0).getCodeMetadata().getCodeLanguage()).isEqualTo("java");
        assertThat(rich.getSubmessages(0).getCodeMetadata().getCodeBlocks(0).getCodeContent())
            .isEqualTo("System.out.println(1);");

        Wa.Message table = MessageEncoder.richTableMessage("Scores", List.of(
            new MessageEncoder.TableRowInput(List.of("Name", "Score"), true),
            new MessageEncoder.TableRowInput(List.of("Alice", "10"), false)));
        var tableMeta = table.getRichResponseMessage().getSubmessages(0).getTableMetadata();
        assertThat(tableMeta.getTitle()).isEqualTo("Scores");
        assertThat(tableMeta.getRows(0).getIsHeading()).isTrue();
        assertThat(tableMeta.getRows(1).getItemsList()).containsExactly("Alice", "10");
        assertThatThrownBy(() -> MessageEncoder.richCodeMessage("java", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEncoder.richTableMessage("t", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paymentMessagesCarryProtoFields() {
        Wa.MessageKey requestKey = Wa.MessageKey.newBuilder()
            .setRemoteJid("111@s.whatsapp.net").setFromMe(false).setId("REQ1").build();
        Wa.Message send = MessageEncoder.sendPaymentMessage(MessageEncoder.text("thanks"), requestKey, "tx-1");
        assertThat(send.getSendPaymentMessage().getRequestMessageKey().getId()).isEqualTo("REQ1");
        assertThat(send.getSendPaymentMessage().getTransactionData()).isEqualTo("tx-1");
        Wa.Message request = MessageEncoder.requestPaymentMessage("USD", 1500, "222@s.whatsapp.net", "lunch");
        assertThat(request.getRequestPaymentMessage().getCurrencyCodeIso4217()).isEqualTo("USD");
        assertThat(request.getRequestPaymentMessage().getAmount1000()).isEqualTo(1500);
        assertThat(request.getRequestPaymentMessage().getRequestFrom()).isEqualTo("222@s.whatsapp.net");
    }

    @Test
    void interactiveAsTemplateSetsTemplateId() {
        Wa.Message interactive = MessageEncoder.interactiveCtaButtons("body", "footer",
            List.of(MessageEncoder.CtaButton.url("Visit", "https://example.invalid")));
        Wa.Message templated = MessageEncoder.interactiveAsTemplate(interactive, "tpl-9");
        assertThat(templated.getTemplateMessage().getTemplateId()).isEqualTo("tpl-9");
        assertThat(templated.getTemplateMessage().hasInteractiveMessageTemplate()).isTrue();
        Wa.Message generated = MessageEncoder.interactiveAsTemplate(interactive);
        assertThat(generated.getTemplateMessage().getTemplateId()).startsWith("template-");
        assertThatThrownBy(() -> MessageEncoder.interactiveAsTemplate(MessageEncoder.text("plain")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pollUpdateAndPollResultSnapshotShapes() {
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setRemoteJid("123@g.us").setFromMe(true).setId("POLL1").build();
        Wa.Message vote = MessageEncoder.pollUpdateMessage(key, bytes(9, 16), bytes(8, 12), 123456L);
        assertThat(vote.getPollUpdateMessage().getPollCreationMessageKey().getId()).isEqualTo("POLL1");
        assertThat(vote.getPollUpdateMessage().getVote().getEncPayload().size()).isEqualTo(16);
        assertThat(vote.getPollUpdateMessage().getSenderTimestampMs()).isEqualTo(123456L);

        Wa.Message quizResult = MessageEncoder.pollResultSnapshotMessage("Q",
            List.of(new MessageEncoder.PollVoteInput("Paris", 5)), true);
        assertThat(quizResult.hasPollResultSnapshotMessageV3()).isTrue();
        assertThat(quizResult.getPollResultSnapshotMessageV3().getPollType())
            .isEqualTo(Wa.Message.PollType.QUIZ);
        Wa.Message pollResult = MessageEncoder.pollResultSnapshotMessage("Q",
            List.of(new MessageEncoder.PollVoteInput("A", 2)), false);
        assertThat(pollResult.hasPollResultSnapshotMessage()).isTrue();
        assertThat(pollResult.getPollResultSnapshotMessage().getPollVotes(0).getOptionVoteCount()).isEqualTo(2);
    }

    @Test
    void groupStatusWrapFlagsContextAndWraps() {
        Wa.Message inner = MessageEncoder.textWithMentions("hi", List.of("111@s.whatsapp.net"));
        Wa.Message wrapped = MessageEncoder.groupStatusWrap(inner);
        assertThat(wrapped.hasGroupStatusMessageV2()).isTrue();
        var unwrapped = wrapped.getGroupStatusMessageV2().getMessage();
        assertThat(unwrapped.getExtendedTextMessage().getContextInfo().getIsGroupStatus()).isTrue();
        assertThat(unwrapped.getExtendedTextMessage().getContextInfo().getMentionedJidList())
            .containsExactly("111@s.whatsapp.net");
    }
}
