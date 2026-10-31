package id.ja_wa.feature.messaging;

import com.google.protobuf.ByteString;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.crypto.Bytes;

import java.util.List;

/**
 * Builder and encoder utility for constructing WhatsApp Protocol Buffer {@link Wa.Message} payloads.
 * <p>
 * Supports text, interactive buttons, list dropdowns, carousels, media (image, audio, video, document,
 * sticker), location, contact cards, quoted replies, reactions, and PKCS#7/randomized padding.
 * <p>
 * Increment 2 additions (Baileys parity): album messages, quiz polls, group
 * mentions, mention-all, multi-contact arrays, lottie stickers, AI rich-response
 * code/table messages, payment messages, and interactive-as-template wrapping.
 * Increment 3 additions: spoiler messages (see {@link #spoilerWrap}) and
 * status mentions (see {@link StatusMentionBuilder}). Poll votes are absent:
 * a vote is an encrypted {@code PollEncValue} that requires the poll creation
 * key, which this builder does not hold (see {@link PollDecoder} for vote
 * decryption).
 */
public final class MessageEncoder {

    private MessageEncoder() {}

    public static byte[] encode(Wa.Message msg) {
        byte[] body = msg.toByteArray();
        return pad(body);
    }

    public static byte[] unpad(byte[] padded) {
        if (padded == null || padded.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = padded[padded.length - 1] & 0xFF;
        if (n == 0 || n > padded.length || n > 16) {
            throw new IllegalArgumentException("invalid pad length: " + n);
        }
        return Bytes.slice(padded, 0, padded.length - n);
    }

    public static byte[] pad(byte[] body) {
        int n = (Bytes.random(1)[0] & 0x0F) + 1;
        byte[] out = new byte[body.length + n];
        System.arraycopy(body, 0, out, 0, body.length);
        byte pad = (byte) n;
        for (int i = body.length; i < out.length; i++) out[i] = pad;
        return out;
    }

    public static Wa.Message text(String text) {
        return Wa.Message.newBuilder().setConversation(text).build();
    }

    public static Wa.Message statusTextMessage(String text, int backgroundArgb, int fontType) {
        var ext = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(text)
            .setBackgroundArgb(backgroundArgb);
        var font = Wa.Message.ExtendedTextMessage.FontType.forNumber(fontType);
        if (font != null) {
            ext.setFont(font);
        }
        return Wa.Message.newBuilder().setExtendedTextMessage(ext).build();
    }

    public static Wa.Message locationMessage(double latitude, double longitude,
                                             String name, String address) {
        Wa.Message.LocationMessage.Builder b = Wa.Message.LocationMessage.newBuilder()
            .setDegreesLatitude(latitude)
            .setDegreesLongitude(longitude);
        if (name != null && !name.isEmpty())       b.setName(name);
        if (address != null && !address.isEmpty()) b.setAddress(address);
        return Wa.Message.newBuilder().setLocationMessage(b.build()).build();
    }

    public static Wa.Message contactMessage(String displayName, String vcard) {
        Wa.Message.ContactMessage contact = Wa.Message.ContactMessage.newBuilder()
            .setDisplayName(displayName == null ? "" : displayName)
            .setVcard(vcard)
            .build();
        return Wa.Message.newBuilder().setContactMessage(contact).build();
    }

    public static Wa.Message viewOnceWrap(Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setViewOnceMessageV2(Wa.Message.FutureProofMessage.newBuilder()
                .setMessage(inner).build())
            .build();
    }

    public static Wa.Message viewOnceExtensionWrap(Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setViewOnceMessageV2Extension(Wa.Message.FutureProofMessage.newBuilder()
                .setMessage(inner).build())
            .build();
    }

    public static Wa.Message pollMessage(String name,
                                         List<String> options,
                                         int selectableCount) {
        Wa.Message.PollCreationMessage.Builder pcm = Wa.Message.PollCreationMessage.newBuilder()
            .setName(name)
            .setSelectableOptionsCount(selectableCount);
        for (String opt : options) {
            pcm.addOptions(Wa.Message.PollCreationMessage.Option.newBuilder()
                .setOptionName(opt).build());
        }
        Wa.MessageContextInfo mci = Wa.MessageContextInfo.newBuilder()
            .setMessageSecret(ByteString.copyFrom(Bytes.random(32)))
            .build();
        Wa.Message.Builder mb = Wa.Message.newBuilder().setMessageContextInfo(mci);
        if (selectableCount == 1) {
            mb.setPollCreationMessageV3(pcm.build());
        } else {
            mb.setPollCreationMessage(pcm.build());
        }
        return mb.build();
    }

    public static Wa.Message textWithMentions(String body, List<String> mentionedBareJids) {
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder();
        for (String jid : mentionedBareJids) ctx.addMentionedJid(jid);
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(body)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    /**
     * Baileys {@code mentionAll}: the same wire shape as {@link #textWithMentions},
     * with every participant JID supplied by the caller mentioned, plus
     * {@code ContextInfo.nonJidMentions = 1} — the flag Baileys sets for
     * mention-all (see {@code generateWAMessageContent} in Baileys'
     * {@code messages.js}). The participant list itself comes from the group
     * query or the in-memory store — this builder does not invent one, and
     * Baileys likewise performs no participant expansion in the builder.
     * Blank and duplicate JIDs are dropped.
     *
     * @param body message text
     * @param participantJids every participant JID to mention
     * @return extended-text message mentioning all supplied participants
     */
    public static Wa.Message mentionAllMessage(String body, List<String> participantJids) {
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder().setNonJidMentions(1);
        if (participantJids != null) {
            participantJids.stream()
                .filter(j -> j != null && !j.isBlank())
                .distinct()
                .forEach(ctx::addMentionedJid);
        }
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(body)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    /** One group mention target for {@link #textWithGroupMentions}. */
    public record GroupMentionInput(String groupJid, String groupSubject) {}

    /**
     * Extended-text message carrying {@code ContextInfo.groupMentions}, the
     * wire shape Baileys uses for group mentions. The group subject is
     * carried verbatim when supplied; it is not looked up.
     *
     * @param body message text
     * @param groups group mentions to attach
     * @return extended-text message with group mentions
     */
    public static Wa.Message textWithGroupMentions(String body, List<GroupMentionInput> groups) {
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder();
        if (groups != null) {
            for (GroupMentionInput g : groups) {
                if (g == null || g.groupJid() == null || g.groupJid().isBlank()) continue;
                Wa.GroupMention.Builder gm = Wa.GroupMention.newBuilder().setGroupJid(g.groupJid());
                if (g.groupSubject() != null) gm.setGroupSubject(g.groupSubject());
                ctx.addGroupMentions(gm.build());
            }
        }
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(body)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    /** One contact entry for {@link #contactsArrayMessage}. */
    public record ContactInput(String displayName, String vcard) {}

    /**
     * Multi-contact message (Baileys {@code contactsArrayMessage}): a single
     * display name plus one {@code ContactMessage} per entry. This is distinct
     * from {@link #contactMessage}, which carries exactly one contact.
     *
     * @param displayName array display name, null becomes empty
     * @param contacts contact entries; must contain at least one entry
     * @return contacts-array message
     * @throws IllegalArgumentException if {@code contacts} is null or empty
     */
    public static Wa.Message contactsArrayMessage(String displayName, List<ContactInput> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            throw new IllegalArgumentException("contacts must contain at least one entry");
        }
        Wa.Message.ContactsArrayMessage.Builder b = Wa.Message.ContactsArrayMessage.newBuilder()
            .setDisplayName(displayName == null ? "" : displayName);
        for (ContactInput c : contacts) {
            if (c == null) continue;
            Wa.Message.ContactMessage.Builder cb = Wa.Message.ContactMessage.newBuilder()
                .setDisplayName(c.displayName() == null ? "" : c.displayName());
            if (c.vcard() != null) cb.setVcard(c.vcard());
            b.addContacts(cb.build());
        }
        return Wa.Message.newBuilder().setContactsArrayMessage(b.build()).build();
    }

    /**
     * Album placeholder message (Baileys {@code albumMessage}): declares how
     * many images and videos the following album members will contain. The
     * member media messages themselves are built with the usual media
     * builders and linked with {@link #withAlbumAssociation}. Baileys throws
     * unless an album has at least two image/video items, and this builder
     * mirrors that validation on the counts.
     *
     * @param expectedImageCount expected image members, 0 when none
     * @param expectedVideoCount expected video members, 0 when none
     * @return message carrying only the album declaration
     * @throws IllegalArgumentException if the counts are negative or total
     *                                  fewer than two
     */
    public static Wa.Message albumMessage(int expectedImageCount, int expectedVideoCount) {
        if (expectedImageCount < 0 || expectedVideoCount < 0) {
            throw new IllegalArgumentException("album counts must not be negative");
        }
        if (expectedImageCount + expectedVideoCount < 2) {
            throw new IllegalArgumentException("an album needs at least two image/video members");
        }
        Wa.Message.AlbumMessage album = Wa.Message.AlbumMessage.newBuilder()
            .setExpectedImageCount(expectedImageCount)
            .setExpectedVideoCount(expectedVideoCount)
            .build();
        return Wa.Message.newBuilder().setAlbumMessage(album).build();
    }

    /**
     * Links a media message to its album: sets top-level
     * {@code MessageContextInfo.messageAssociation} to {@code MEDIA_ALBUM}
     * with the album's parent key and the member's index, preserving any
     * existing message-context info on the message.
     *
     * @param mediaMessage media member message to link
     * @param parentMessageKey key of the album parent message
     * @param messageIndex zero-based index of this member in the album
     * @return the message with album association set
     */
    public static Wa.Message withAlbumAssociation(Wa.Message mediaMessage,
                                                  Wa.MessageKey parentMessageKey,
                                                  int messageIndex) {
        if (mediaMessage == null) throw new IllegalArgumentException("mediaMessage must not be null");
        if (parentMessageKey == null) throw new IllegalArgumentException("parentMessageKey must not be null");
        if (messageIndex < 0) throw new IllegalArgumentException("messageIndex must not be negative");
        Wa.MessageAssociation association = Wa.MessageAssociation.newBuilder()
            .setAssociationType(Wa.MessageAssociation.AssociationType.MEDIA_ALBUM)
            .setParentMessageKey(parentMessageKey)
            .setMessageIndex(messageIndex)
            .build();
        Wa.MessageContextInfo mci = mediaMessage.hasMessageContextInfo()
            ? mediaMessage.getMessageContextInfo().toBuilder().setMessageAssociation(association).build()
            : Wa.MessageContextInfo.newBuilder().setMessageAssociation(association).build();
        return mediaMessage.toBuilder().setMessageContextInfo(mci).build();
    }

    /**
     * Quiz poll (Baileys fork poll with {@code pollType = QUIZ}): Baileys
     * builds quizzes as {@code pollCreationMessageV5} — the V3 shape plus
     * {@code correctAnswer} and {@code pollType = QUIZ} — single-select.
     * Like {@link #pollMessage}, option hashes are not set — the existing
     * poll builder does not compute them either. Note Baileys' send path
     * throws for quizzes sent outside a newsletter; this builder builds the
     * message, and that send-time restriction is the caller's to observe.
     *
     * @param name quiz question
     * @param options answer options, at least two
     * @param correctOptionIndex index of the correct option
     * @return quiz poll message (V5)
     * @throws IllegalArgumentException if the options are invalid
     */
    public static Wa.Message quizMessage(String name, List<String> options, int correctOptionIndex) {
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("a quiz needs at least two options");
        }
        if (correctOptionIndex < 0 || correctOptionIndex >= options.size()) {
            throw new IllegalArgumentException("correctOptionIndex out of range");
        }
        Wa.Message.PollCreationMessage.Builder pcm = Wa.Message.PollCreationMessage.newBuilder()
            .setName(name)
            .setSelectableOptionsCount(1)
            .setPollType(Wa.Message.PollType.QUIZ)
            .setCorrectAnswer(Wa.Message.PollCreationMessage.Option.newBuilder()
                .setOptionName(options.get(correctOptionIndex)).build());
        for (String opt : options) {
            pcm.addOptions(Wa.Message.PollCreationMessage.Option.newBuilder()
                .setOptionName(opt).build());
        }
        Wa.MessageContextInfo mci = Wa.MessageContextInfo.newBuilder()
            .setMessageSecret(ByteString.copyFrom(Bytes.random(32)))
            .build();
        return Wa.Message.newBuilder()
            .setMessageContextInfo(mci)
            .setPollCreationMessageV5(pcm.build())
            .build();
    }

    /** One poll-result vote for {@link #pollResultSnapshotMessage}. */
    public record PollVoteInput(String optionName, long voteCount) {}

    /**
     * Poll-result snapshot (Baileys fork {@code pollResult}): the aggregated
     * votes for a poll, as {@code pollResultSnapshotMessageV3} for a quiz
     * and {@code pollResultSnapshotMessage} otherwise, with
     * {@code pollType} set accordingly.
     *
     * @param name poll question
     * @param votes per-option vote counts
     * @param isQuiz true for a quiz result, false for a plain poll result
     * @return poll-result snapshot message
     */
    public static Wa.Message pollResultSnapshotMessage(String name, List<PollVoteInput> votes, boolean isQuiz) {
        Wa.Message.PollResultSnapshotMessage.Builder b = Wa.Message.PollResultSnapshotMessage.newBuilder()
            .setName(name == null ? "" : name)
            .setPollType(isQuiz ? Wa.Message.PollType.QUIZ : Wa.Message.PollType.POLL);
        if (votes != null) {
            for (PollVoteInput v : votes) {
                if (v == null) continue;
                b.addPollVotes(Wa.Message.PollResultSnapshotMessage.PollVote.newBuilder()
                    .setOptionName(v.optionName() == null ? "" : v.optionName())
                    .setOptionVoteCount(v.voteCount())
                    .build());
            }
        }
        Wa.Message.Builder mb = Wa.Message.newBuilder();
        if (isQuiz) {
            mb.setPollResultSnapshotMessageV3(b.build());
        } else {
            mb.setPollResultSnapshotMessage(b.build());
        }
        return mb.build();
    }

    /**
     * Poll-vote message (Baileys fork {@code pollUpdate}): a vote for a poll
     * creation message. The vote payload is caller-supplied encrypted bytes —
     * Baileys likewise takes the encrypted vote from the caller, and inbound
     * vote decryption is not wired in Baileys either (its
     * {@code pollUpdateMessage} receive branch is commented out).
     *
     * @param pollCreationMessageKey key of the poll creation message
     * @param encPayload encrypted vote payload bytes
     * @param encIv encrypted vote IV bytes
     * @param senderTimestampMs sender timestamp in milliseconds
     * @return poll-update message
     */
    public static Wa.Message pollUpdateMessage(Wa.MessageKey pollCreationMessageKey,
                                               byte[] encPayload, byte[] encIv,
                                               long senderTimestampMs) {
        if (pollCreationMessageKey == null) {
            throw new IllegalArgumentException("pollCreationMessageKey must not be null");
        }
        Wa.Message.PollUpdateMessage.Builder b = Wa.Message.PollUpdateMessage.newBuilder()
            .setPollCreationMessageKey(pollCreationMessageKey)
            .setVote(Wa.Message.PollEncValue.newBuilder()
                .setEncPayload(encPayload == null ? ByteString.EMPTY : ByteString.copyFrom(encPayload))
                .setEncIv(encIv == null ? ByteString.EMPTY : ByteString.copyFrom(encIv))
                .build())
            .setSenderTimestampMs(senderTimestampMs);
        return Wa.Message.newBuilder().setPollUpdateMessage(b.build()).build();
    }

    /**
     * Sticker message with explicit lottie flag. The existing
     * {@link #stickerMessage(String, String, byte[], byte[], byte[], long, boolean)}
     * overload never sets {@code isLottie}; this overload sets it exactly as
     * supplied, so lottie stickers are no longer silently downgraded to
     * ordinary animated stickers.
     */
    public static Wa.Message stickerMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            boolean isAnimated,
            boolean isLottie) {
        Wa.Message msg = stickerMessage(uploadedUrl, directPath, mediaKey, fileSha256,
            fileEncSha256, fileLength, isAnimated);
        Wa.Message.StickerMessage sticker = msg.getStickerMessage().toBuilder()
            .setIsLottie(isLottie)
            .build();
        return msg.toBuilder().setStickerMessage(sticker).build();
    }

    /**
     * Wraps a message as a lottie sticker message (Baileys fork
     * {@code isLottie} handling): {@code lottieStickerMessage} is a
     * {@code FutureProofMessage} wrapper around the inner message in
     * wa.proto, not a flag on the sticker itself — Baileys wraps the whole
     * built message this way. The {@code StickerMessage.isLottie} flag
     * remains available via the flag overload of {@link #stickerMessage}
     * for callers that need the proto flag instead.
     *
     * @param inner message to wrap (typically a sticker message)
     * @return lottie-sticker-wrapped message
     */
    public static Wa.Message wrapAsLottie(Wa.Message inner) {
        if (inner == null) throw new IllegalArgumentException("inner must not be null");
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(inner)
            .build();
        return Wa.Message.newBuilder().setLottieStickerMessage(wrap).build();
    }

    /**
     * Convenience for a lottie sticker: builds a plain sticker message and
     * wraps it with {@link #wrapAsLottie}, matching the Baileys fork's
     * {@code isLottie} wrapper shape.
     */
    public static Wa.Message lottieStickerMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            boolean isAnimated) {
        Wa.Message sticker = stickerMessage(uploadedUrl, directPath, mediaKey, fileSha256,
            fileEncSha256, fileLength, isAnimated);
        return wrapAsLottie(sticker);
    }

    /**
     * Wraps a message as a group-status message (Baileys fork
     * {@code groupStatus}): sets {@code ContextInfo.isGroupStatus = true} on
     * the inner message's context info where the inner type carries one
     * (extended text, interactive, image, video, document, audio, sticker,
     * list, buttons, contact, location), then wraps the result in
     * {@code groupStatusMessageV2}. For inner types with no context info
     * (e.g. plain {@code conversation} text), only the wrapper is set —
     * Baileys likewise has no context info to flag on a bare conversation
     * string — and callers should prefer an extended-text inner message.
     *
     * @param inner message to wrap
     * @return group-status-wrapped message
     */
    public static Wa.Message groupStatusWrap(Wa.Message inner) {
        if (inner == null) throw new IllegalArgumentException("inner must not be null");
        Wa.Message.Builder b = inner.toBuilder();
        if (inner.hasExtendedTextMessage()) {
            Wa.ContextInfo ctx = inner.getExtendedTextMessage().hasContextInfo()
                ? inner.getExtendedTextMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setExtendedTextMessage(inner.getExtendedTextMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasInteractiveMessage()) {
            Wa.ContextInfo ctx = inner.getInteractiveMessage().hasContextInfo()
                ? inner.getInteractiveMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setInteractiveMessage(inner.getInteractiveMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasImageMessage()) {
            Wa.ContextInfo ctx = inner.getImageMessage().hasContextInfo()
                ? inner.getImageMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setImageMessage(inner.getImageMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasVideoMessage()) {
            Wa.ContextInfo ctx = inner.getVideoMessage().hasContextInfo()
                ? inner.getVideoMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setVideoMessage(inner.getVideoMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasDocumentMessage()) {
            Wa.ContextInfo ctx = inner.getDocumentMessage().hasContextInfo()
                ? inner.getDocumentMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setDocumentMessage(inner.getDocumentMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasAudioMessage()) {
            Wa.ContextInfo ctx = inner.getAudioMessage().hasContextInfo()
                ? inner.getAudioMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setAudioMessage(inner.getAudioMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasStickerMessage()) {
            Wa.ContextInfo ctx = inner.getStickerMessage().hasContextInfo()
                ? inner.getStickerMessage().getContextInfo().toBuilder().setIsGroupStatus(true).build()
                : Wa.ContextInfo.newBuilder().setIsGroupStatus(true).build();
            b.setStickerMessage(inner.getStickerMessage().toBuilder().setContextInfo(ctx));
        }
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(b.build())
            .build();
        return Wa.Message.newBuilder().setGroupStatusMessageV2(wrap).build();
    }

    /**
     * Wraps a message as a spoiler message, following Baileys'
     * {@code generateWAMessageContent} ({@code lib/Utils/messages.js}):
     * the inner message's {@code ContextInfo.isSpoiler} flag is set where
     * the inner type carries a context info, and the result is wrapped in
     * {@code spoilerMessage}. Inner types with no context info (e.g. plain
     * {@code conversation} text) get only the wrapper — Baileys likewise
     * has no context info to flag on a bare conversation string — and
     * callers should prefer an extended-text inner message.
     * <p>
     * Wire fields (verified against Baileys' compiled WAProto, not guessed):
     * {@code Message.spoilerMessage} is field 118
     * ({@code FutureProofMessage}) and {@code ContextInfo.isSpoiler} is
     * field 73 (bool). Both were added to {@code wa.proto} in Increment 3
     * and regenerated with the pinned protoc; they are new optional
     * fields, so old readers ignore them and old writers simply omit them.
     *
     * @param inner message to wrap
     * @return spoiler-wrapped message
     */
    public static Wa.Message spoilerWrap(Wa.Message inner) {
        if (inner == null) throw new IllegalArgumentException("inner must not be null");
        Wa.Message.Builder b = inner.toBuilder();
        if (inner.hasExtendedTextMessage()) {
            Wa.ContextInfo ctx = inner.getExtendedTextMessage().hasContextInfo()
                ? inner.getExtendedTextMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setExtendedTextMessage(inner.getExtendedTextMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasImageMessage()) {
            Wa.ContextInfo ctx = inner.getImageMessage().hasContextInfo()
                ? inner.getImageMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setImageMessage(inner.getImageMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasVideoMessage()) {
            Wa.ContextInfo ctx = inner.getVideoMessage().hasContextInfo()
                ? inner.getVideoMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setVideoMessage(inner.getVideoMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasDocumentMessage()) {
            Wa.ContextInfo ctx = inner.getDocumentMessage().hasContextInfo()
                ? inner.getDocumentMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setDocumentMessage(inner.getDocumentMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasAudioMessage()) {
            Wa.ContextInfo ctx = inner.getAudioMessage().hasContextInfo()
                ? inner.getAudioMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setAudioMessage(inner.getAudioMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasStickerMessage()) {
            Wa.ContextInfo ctx = inner.getStickerMessage().hasContextInfo()
                ? inner.getStickerMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setStickerMessage(inner.getStickerMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasInteractiveMessage()) {
            Wa.ContextInfo ctx = inner.getInteractiveMessage().hasContextInfo()
                ? inner.getInteractiveMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setInteractiveMessage(inner.getInteractiveMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasListMessage()) {
            Wa.ContextInfo ctx = inner.getListMessage().hasContextInfo()
                ? inner.getListMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setListMessage(inner.getListMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasButtonsMessage()) {
            Wa.ContextInfo ctx = inner.getButtonsMessage().hasContextInfo()
                ? inner.getButtonsMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setButtonsMessage(inner.getButtonsMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasContactMessage()) {
            Wa.ContextInfo ctx = inner.getContactMessage().hasContextInfo()
                ? inner.getContactMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setContactMessage(inner.getContactMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasContactsArrayMessage()) {
            Wa.ContextInfo ctx = inner.getContactsArrayMessage().hasContextInfo()
                ? inner.getContactsArrayMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setContactsArrayMessage(inner.getContactsArrayMessage().toBuilder().setContextInfo(ctx));
        } else if (inner.hasLocationMessage()) {
            Wa.ContextInfo ctx = inner.getLocationMessage().hasContextInfo()
                ? inner.getLocationMessage().getContextInfo().toBuilder().setIsSpoiler(true).build()
                : Wa.ContextInfo.newBuilder().setIsSpoiler(true).build();
            b.setLocationMessage(inner.getLocationMessage().toBuilder().setContextInfo(ctx));
        }
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(b.build())
            .build();
        return Wa.Message.newBuilder().setSpoilerMessage(wrap).build();
    }

    /** One table row for {@link #richTableMessage}. */
    public record TableRowInput(List<String> items, boolean heading) {}

    /**
     * AI rich-response code message: one {@code CODE} submessage carrying the
     * language and code blocks. Highlight type is left at the proto default
     * for every block — wa-java has no syntax-highlighting input to map,
     * whereas Baileys tokenizes code into highlighted spans. Difference from
     * Baileys' fork: Baileys wraps the rich-response message in a
     * {@code botForwardedMessage} carrying a generated unified response and
     * generated verification proofs; this builder produces the core
     * {@code richResponseMessage} payload only, and does not fabricate
     * proofs.
     *
     * @param language code language, e.g. {@code "java"}
     * @param codeBlocks code block contents, at least one
     * @return rich-response message with a code submessage
     */
    public static Wa.Message richCodeMessage(String language, List<String> codeBlocks) {
        if (codeBlocks == null || codeBlocks.isEmpty()) {
            throw new IllegalArgumentException("codeBlocks must contain at least one block");
        }
        Wa.AIRichResponseCodeMetadata.Builder meta = Wa.AIRichResponseCodeMetadata.newBuilder();
        if (language != null) meta.setCodeLanguage(language);
        for (String block : codeBlocks) {
            if (block == null) continue;
            meta.addCodeBlocks(Wa.AIRichResponseCodeMetadata.AIRichResponseCodeBlock.newBuilder()
                .setCodeContent(block).build());
        }
        Wa.AIRichResponseSubMessage sub = Wa.AIRichResponseSubMessage.newBuilder()
            .setMessageType(Wa.AIRichResponseSubMessageType.AI_RICH_RESPONSE_CODE)
            .setCodeMetadata(meta.build())
            .build();
        Wa.AIRichResponseMessage rich = Wa.AIRichResponseMessage.newBuilder()
            .setMessageType(Wa.AIRichResponseMessageType.AI_RICH_RESPONSE_TYPE_STANDARD)
            .addSubmessages(sub)
            .build();
        return Wa.Message.newBuilder().setRichResponseMessage(rich).build();
    }

    /**
     * AI rich-response table message: one {@code TABLE} submessage with the
     * given title and rows. Row heading flags are carried verbatim.
     *
     * @param title table title, null becomes empty
     * @param rows table rows, at least one
     * @return rich-response message with a table submessage
     */
    public static Wa.Message richTableMessage(String title, List<TableRowInput> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must contain at least one row");
        }
        Wa.AIRichResponseTableMetadata.Builder meta = Wa.AIRichResponseTableMetadata.newBuilder();
        if (title != null) meta.setTitle(title);
        for (TableRowInput row : rows) {
            if (row == null) continue;
            Wa.AIRichResponseTableMetadata.AIRichResponseTableRow.Builder rb =
                Wa.AIRichResponseTableMetadata.AIRichResponseTableRow.newBuilder()
                    .setIsHeading(row.heading());
            if (row.items() != null) rb.addAllItems(row.items());
            meta.addRows(rb.build());
        }
        Wa.AIRichResponseSubMessage sub = Wa.AIRichResponseSubMessage.newBuilder()
            .setMessageType(Wa.AIRichResponseSubMessageType.AI_RICH_RESPONSE_TABLE)
            .setTableMetadata(meta.build())
            .build();
        Wa.AIRichResponseMessage rich = Wa.AIRichResponseMessage.newBuilder()
            .setMessageType(Wa.AIRichResponseMessageType.AI_RICH_RESPONSE_TYPE_STANDARD)
            .addSubmessages(sub)
            .build();
        return Wa.Message.newBuilder().setRichResponseMessage(rich).build();
    }

    /**
     * Send-payment message: a note plus the key of the payment request being
     * answered and opaque transaction data, exactly the proto fields Baileys
     * carries for {@code sendPaymentMessage}. Amount/currency live on the
     * request message, not on this message.
     *
     * @param noteMessage note content, null becomes an empty message
     * @param requestMessageKey key of the answered payment request, may be null
     * @param transactionData opaque transaction data, may be null
     * @return send-payment message
     */
    public static Wa.Message sendPaymentMessage(Wa.Message noteMessage,
                                                Wa.MessageKey requestMessageKey,
                                                String transactionData) {
        Wa.Message.SendPaymentMessage.Builder b = Wa.Message.SendPaymentMessage.newBuilder()
            .setNoteMessage(noteMessage == null ? Wa.Message.getDefaultInstance() : noteMessage);
        if (requestMessageKey != null) b.setRequestMessageKey(requestMessageKey);
        if (transactionData != null) b.setTransactionData(transactionData);
        return Wa.Message.newBuilder().setSendPaymentMessage(b.build()).build();
    }

    /**
     * Request-payment message: currency, amount in thousandths of the unit
     * (matching the proto's {@code amount1000} field), requester JID, and an
     * optional note text.
     *
     * @param currencyCodeIso4217 ISO 4217 currency code
     * @param amount1000 amount in thousandths
     * @param requestFrom requester JID
     * @param noteText note text, may be null
     * @return request-payment message
     */
    public static Wa.Message requestPaymentMessage(String currencyCodeIso4217,
                                                   long amount1000,
                                                   String requestFrom,
                                                   String noteText) {
        Wa.Message.RequestPaymentMessage.Builder b = Wa.Message.RequestPaymentMessage.newBuilder();
        if (currencyCodeIso4217 != null) b.setCurrencyCodeIso4217(currencyCodeIso4217);
        b.setAmount1000(amount1000);
        if (requestFrom != null) b.setRequestFrom(requestFrom);
        if (noteText != null) b.setNoteMessage(text(noteText));
        return Wa.Message.newBuilder().setRequestPaymentMessage(b.build()).build();
    }

    /**
     * Wraps an interactive message as a template message (Baileys
     * interactive-as-template): the {@code interactiveMessageTemplate} oneof
     * variant of {@code TemplateMessage}, with a generated template id
     * ({@code "template-"} + current time in milliseconds, matching Baileys'
     * {@code 'template-' + Date.now()} fallback).
     *
     * @param interactiveMessage message carrying an interactive message
     * @return template message wrapping it
     * @throws IllegalArgumentException if the input has no interactive message
     */
    public static Wa.Message interactiveAsTemplate(Wa.Message interactiveMessage) {
        return interactiveAsTemplate(interactiveMessage, null);
    }

    /**
     * Wraps an interactive message as a template message with an explicit
     * template id (Baileys uses the caller-supplied id when present).
     *
     * @param interactiveMessage message carrying an interactive message
     * @param templateId template id, or null for a generated one
     * @return template message wrapping it
     * @throws IllegalArgumentException if the input has no interactive message
     */
    public static Wa.Message interactiveAsTemplate(Wa.Message interactiveMessage, String templateId) {
        if (interactiveMessage == null || !interactiveMessage.hasInteractiveMessage()) {
            throw new IllegalArgumentException("interactiveMessage must carry an interactive message");
        }
        Wa.Message.TemplateMessage.Builder template = Wa.Message.TemplateMessage.newBuilder()
            .setInteractiveMessageTemplate(interactiveMessage.getInteractiveMessage())
            .setTemplateId(templateId != null ? templateId
                : "template-" + System.currentTimeMillis());
        return Wa.Message.newBuilder().setTemplateMessage(template.build()).build();
    }

    private static ByteString bs(byte[] b) {
        return ByteString.copyFrom(b);
    }

    public record ListRow(String rowId, String title, String description) {}

    public record ListSection(String title, List<ListRow> rows) {}

    public static Wa.Message listMessage(String title,
                                         String body,
                                         String footer,
                                         String buttonText,
                                         List<ListSection> sections) {
        Wa.Message.ListMessage.Builder b = Wa.Message.ListMessage.newBuilder()
            .setTitle(title)
            .setDescription(body)
            .setButtonText(buttonText)
            .setListType(Wa.Message.ListMessage.ListType.SINGLE_SELECT);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (ListSection s : sections) {
            Wa.Message.ListMessage.Section.Builder sb = Wa.Message.ListMessage.Section.newBuilder();
            if (s.title() != null) sb.setTitle(s.title());
            for (ListRow r : s.rows()) {
                Wa.Message.ListMessage.Row.Builder rb = Wa.Message.ListMessage.Row.newBuilder();
                if (r.rowId() != null) rb.setRowId(r.rowId());
                if (r.title() != null) rb.setTitle(r.title());
                if (r.description() != null) rb.setDescription(r.description());
                sb.addRows(rb.build());
            }
            b.addSections(sb.build());
        }
        return Wa.Message.newBuilder().setListMessage(b.build()).build();
    }

    public record CtaButton(String name, String paramsJson) {

        public static CtaButton url(String displayText, String url) {
            return new CtaButton("cta_url",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"url\":\"" + escape(url)
              + "\",\"merchant_url\":\"" + escape(url) + "\"}");
        }

        public static CtaButton copy(String displayText, String copyCode) {
            return new CtaButton("cta_copy",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"copy_code\":\"" + escape(copyCode) + "\"}");
        }

        public static CtaButton call(String displayText, String phoneNumber) {
            return new CtaButton("cta_call",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"phone_number\":\"" + escape(phoneNumber) + "\"}");
        }

        public static CtaButton quickReply(String displayText, String id) {
            return new CtaButton("quick_reply",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"id\":\"" + escape(id) + "\"}");
        }

        public static CtaButton singleSelect(String displayText, List<ListSection> sections) {
            StringBuilder b = new StringBuilder("{\"title\":\"").append(escape(displayText));
            b.append("\",\"sections\":[");
            for (int i = 0; i < sections.size(); i++) {
                if (i > 0) b.append(",");
                ListSection s = sections.get(i);
                b.append("{\"title\":\"").append(escape(s.title() == null ? "" : s.title()));
                b.append("\",\"rows\":[");
                for (int j = 0; j < s.rows().size(); j++) {
                    if (j > 0) b.append(",");
                    ListRow r = s.rows().get(j);
                    b.append("{\"id\":\"").append(escape(r.rowId() == null ? "" : r.rowId()));
                    b.append("\",\"title\":\"").append(escape(r.title() == null ? "" : r.title()));
                    b.append("\",\"description\":\"").append(escape(r.description() == null ? "" : r.description()));
                    b.append("\"}");
                }
                b.append("]}");
            }
            b.append("]}");
            return new CtaButton("single_select", b.toString());
        }
    }

    public record CarouselCard(String title, String caption,
                               Wa.Message.ImageMessage imageHeader,
                               Wa.Message.VideoMessage videoHeader,
                               String footer,
                               List<CtaButton> buttons) {}

    public static Wa.Message interactiveCarousel(String body, String footer,
                                                 List<CarouselCard> cards) {
        Wa.Message.InteractiveMessage.CarouselMessage.Builder cm =
            Wa.Message.InteractiveMessage.CarouselMessage.newBuilder()
                .setMessageVersion(1);
        for (CarouselCard card : cards) {
            Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
                Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                    .setMessageVersion(1);
            for (CtaButton btn : card.buttons()) {
                nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                    .setName(btn.name())
                    .setButtonParamsJson(btn.paramsJson())
                    .build());
            }
            Wa.Message.InteractiveMessage.Header.Builder hb = Wa.Message.InteractiveMessage.Header.newBuilder()
                .setTitle(card.title() == null ? "" : card.title())
                .setHasMediaAttachment(true);
            if (card.imageHeader() != null)       hb.setImageMessage(card.imageHeader());
            else if (card.videoHeader() != null)  hb.setVideoMessage(card.videoHeader());
            else throw new IllegalArgumentException("CarouselCard requires imageHeader or videoHeader");

            Wa.Message.InteractiveMessage.Builder cardMsg = Wa.Message.InteractiveMessage.newBuilder()
                .setHeader(hb.build())
                .setBody(Wa.Message.InteractiveMessage.Body.newBuilder()
                    .setText(card.caption() == null ? "" : card.caption())
                    .build())
                .setNativeFlowMessage(nf.build());
            if (card.footer() != null && !card.footer().isEmpty()) {
                cardMsg.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder()
                    .setText(card.footer()).build());
            }
            cm.addCards(cardMsg.build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setCarouselMessage(cm.build());
        if (body != null && !body.isEmpty()) {
            im.setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build());
        }
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    public static Wa.Message interactiveCtaButtons(String body, String footer,
                                                   List<CtaButton> buttons) {
        Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
            Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                .setMessageVersion(1);
        for (CtaButton b : buttons) {
            nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                .setName(b.name())
                .setButtonParamsJson(b.paramsJson())
                .build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build())
            .setNativeFlowMessage(nf.build());
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.toString();
    }

    public record QuickReplyButton(String buttonId, String displayText) {}

    public static Wa.Message buttonsMessage(String body, String footer,
                                            List<QuickReplyButton> buttons) {
        Wa.Message.ButtonsMessage.Builder b = Wa.Message.ButtonsMessage.newBuilder()
            .setContentText(body)
            .setHeaderType(Wa.Message.ButtonsMessage.HeaderType.EMPTY);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (QuickReplyButton qb : buttons) {
            Wa.Message.ButtonsMessage.Button button = Wa.Message.ButtonsMessage.Button.newBuilder()
                .setButtonId(qb.buttonId())
                .setButtonText(Wa.Message.ButtonsMessage.Button.ButtonText.newBuilder()
                    .setDisplayText(qb.displayText())
                    .build())
                .setType(Wa.Message.ButtonsMessage.Button.Type.RESPONSE)
                .build();
            b.addButtons(button);
        }
        return Wa.Message.newBuilder().setButtonsMessage(b.build()).build();
    }

    public static Wa.Message videoMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String caption, int seconds, int width, int height) {
        Wa.Message.VideoMessage.Builder b = Wa.Message.VideoMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        if (seconds > 0) b.setSeconds(seconds);
        if (width > 0)   b.setWidth(width);
        if (height > 0)  b.setHeight(height);
        return Wa.Message.newBuilder().setVideoMessage(b.build()).build();
    }

    public static Wa.Message audioMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            int seconds, boolean ptt) {
        Wa.Message.AudioMessage.Builder b = Wa.Message.AudioMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setPtt(ptt)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (seconds > 0) b.setSeconds(seconds);
        return Wa.Message.newBuilder().setAudioMessage(b.build()).build();
    }

    public static Wa.Message documentMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String fileName, String title) {
        Wa.Message.DocumentMessage.Builder b = Wa.Message.DocumentMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (fileName != null && !fileName.isEmpty()) b.setFileName(fileName);
        if (title != null && !title.isEmpty())       b.setTitle(title);
        return Wa.Message.newBuilder().setDocumentMessage(b.build()).build();
    }

    public static Wa.Message imageMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            String mimetype,
            String caption) {
        Wa.Message.ImageMessage.Builder b = Wa.Message.ImageMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(ByteString.copyFrom(mediaKey))
            .setFileSha256(ByteString.copyFrom(fileSha256))
            .setFileEncSha256(ByteString.copyFrom(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        return Wa.Message.newBuilder().setImageMessage(b.build()).build();
    }

    public static Wa.Message stickerMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            boolean isAnimated) {
        Wa.Message.StickerMessage.Builder b = Wa.Message.StickerMessage.newBuilder()
            .setUrl(uploadedUrl != null ? uploadedUrl : "")
            .setDirectPath(directPath != null ? directPath : "")
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype("image/webp")
            .setIsAnimated(isAnimated)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        return Wa.Message.newBuilder().setStickerMessage(b.build()).build();
    }

    public static Wa.Message reply(String text,
                                   String quotedMsgId,
                                   String quotedSender,
                                   String quotedText) {
        Wa.Message quotedStub = Wa.Message.newBuilder()
            .setConversation(quotedText == null ? "" : quotedText)
            .build();
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder()
            .setStanzaId(quotedMsgId)
            .setQuotedMessage(quotedStub);
        if (quotedSender != null && !quotedSender.isBlank()) {
            ctx.setParticipant(quotedSender);
        }
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(text)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    public static Wa.Message quote(Wa.Message message, String quotedMsgId, String quotedSender, String quotedText) {
        if (message == null) return null;
        Wa.Message quotedStub = Wa.Message.newBuilder()
            .setConversation(quotedText == null ? "" : quotedText)
            .build();
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder()
            .setStanzaId(quotedMsgId)
            .setQuotedMessage(quotedStub);
        if (quotedSender != null && !quotedSender.isBlank()) {
            ctx.setParticipant(quotedSender);
        }
        Wa.Message.Builder builder = message.toBuilder();
        if (message.hasInteractiveMessage()) {
            builder.setInteractiveMessage(message.getInteractiveMessage().toBuilder().setContextInfo(ctx.build()));
        } else if (message.hasExtendedTextMessage()) {
            builder.setExtendedTextMessage(message.getExtendedTextMessage().toBuilder().setContextInfo(ctx.build()));
        } else if (message.hasConversation()) {
            builder.setExtendedTextMessage(Wa.Message.ExtendedTextMessage.newBuilder()
                .setText(message.getConversation())
                .setContextInfo(ctx.build())
                .build())
                .clearConversation();
        }
        return builder.build();
    }

    public static Wa.Message edit(String chatJid,
                                  String targetMsgId,
                                  Wa.Message newContent,
                                  long timestampMs) {
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setFromMe(true)
            .setId(targetMsgId)
            .setRemoteJid(chatJid)
            .build();
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key)
            .setType(Wa.Message.ProtocolMessage.Type.MESSAGE_EDIT)
            .setEditedMessage(newContent)
            .setTimestampMs(timestampMs)
            .build();
        Wa.Message inner = Wa.Message.newBuilder().setProtocolMessage(protocol).build();
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(inner)
            .build();
        return Wa.Message.newBuilder().setEditedMessage(wrap).build();
    }

    public static Wa.Message revoke(String chatJid,
                                    String targetMsgId,
                                    String targetParticipant,
                                    boolean fromMe) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null && !targetParticipant.isBlank()) {
            key.setParticipant(targetParticipant);
        }
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key.build())
            .setType(Wa.Message.ProtocolMessage.Type.REVOKE)
            .build();
        return Wa.Message.newBuilder().setProtocolMessage(protocol).build();
    }

    public static Wa.Message reaction(String chatJid,
                                      String targetMsgId,
                                      String targetParticipant,
                                      boolean fromMe,
                                      String emoji,
                                      long timestampMs) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null) key.setParticipant(targetParticipant);

        Wa.Message.ReactionMessage reaction = Wa.Message.ReactionMessage.newBuilder()
            .setKey(key.build())
            .setText(emoji == null ? "" : emoji)
            .setSenderTimestampMs(timestampMs)
            .build();
        return Wa.Message.newBuilder().setReactionMessage(reaction).build();
    }

    public static Wa.Message deviceSent(String destinationJid, Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setDeviceSentMessage(Wa.Message.DeviceSentMessage.newBuilder()
                .setDestinationJid(destinationJid)
                .setMessage(inner)
                .build())
            .build();
    }
}
