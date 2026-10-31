package id.ja_wa.feature.messaging;

import id.ja_wa.domain.model.Jid;
import id.ja_wa.domain.model.ProfilePictureInfo;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

/**
 * Pure query builders and response parsers for Baileys'
 * {@code profilePictureUrl} ({@code lib/Socket/chats.js}) and the
 * store's {@code fetchImageUrl} ({@code lib/Store/make-in-memory-store.js}).
 * <p>
 * This class performs no I/O: it builds the {@code iq} stanza and parses
 * the response. Live execution needs JaWaClient wiring (its {@code query}
 * path), which is deliberately not done here. Note that JaWaClient
 * already carries its own equivalents
 * ({@code buildProfilePictureQuery}/{@code parseProfilePictureResponse});
 * this class is the transport-independent form for other callers, and
 * additionally supports the tcToken content Baileys attaches for
 * non-self user JIDs — JaWaClient's builder does not carry tcToken.
 * The parent should consolidate the two builders rather than letting
 * them drift.
 * <p>
 * Query shape (Baileys {@code profilePictureUrl}):
 * {@code <iq target="<bareJid>" to="s.whatsapp.net" type="get"
 * xmlns="w:profile:picture"><picture type="<type>" query="url"
 * [tcToken...]/></iq>}, where {@code type} is {@code "image"} (high
 * resolution) or {@code "preview"} (low resolution). Baileys normalises
 * the target with {@code jidNormalizedUser} (bare JID); this builder
 * does the same via {@link Jid#bare(String)}.
 * <p>
 * Store shape (Baileys {@code fetchImageUrl}): the store returns the
 * cached {@code imgUrl} when the contact holds one, and otherwise
 * fetches via {@code profilePictureUrl} and caches the result. That
 * cache policy is implemented by the caller on {@code InMemoryStore}
 * contacts (see {@code updateContactImgUrl}); this class supplies only
 * the query and the parse, because the store holds no transport to
 * fetch with.
 *
 * @see GroupMetadataQuery for the group-metadata equivalent
 */
public final class ProfilePictureQuery {

    /** High-resolution picture, Baileys' default {@code type}. */
    public static final String TYPE_IMAGE = "image";

    /** Low-resolution preview picture. */
    public static final String TYPE_PREVIEW = "preview";

    private ProfilePictureQuery() {}

    /**
     * Builds the profile-picture query stanza.
     *
     * @param iqId stanza id assigned by the caller
     * @param targetJid user, group or newsletter JID (bare form is used)
     * @param preview true for {@code "preview"}, false for {@code "image"}
     * @return {@code iq} node to send
     */
    public static BinaryNode buildQuery(String iqId, String targetJid, boolean preview) {
        return buildQuery(iqId, targetJid, preview, null);
    }

    /**
     * Builds the profile-picture query stanza with optional tcToken
     * content. Baileys includes a tcToken only for non-self user JIDs
     * when the server property for profile-picture privacy tokens is
     * set; building the token itself (which needs auth state) is the
     * caller's job, and passing null omits it.
     *
     * @param iqId stanza id assigned by the caller
     * @param targetJid user, group or newsletter JID (bare form is used)
     * @param preview true for {@code "preview"}, false for {@code "image"}
     * @param tcTokenContent tcToken child nodes, or null to omit
     * @return {@code iq} node to send
     */
    public static BinaryNode buildQuery(String iqId, String targetJid, boolean preview,
                                        List<BinaryNode> tcTokenContent) {
        if (targetJid == null || targetJid.isBlank()) {
            throw new IllegalArgumentException("targetJid must not be empty");
        }
        String normalized = Jid.bare(targetJid);
        BinaryNode picture = new BinaryNode("picture",
            Map.of("type", preview ? TYPE_PREVIEW : TYPE_IMAGE, "query", "url"),
            tcTokenContent == null || tcTokenContent.isEmpty() ? null : List.copyOf(tcTokenContent));
        return new BinaryNode("iq",
            Map.of(
                "target", normalized,
                "to", Jid.SERVER_WHATSAPP,
                "type", "get",
                "xmlns", "w:profile:picture",
                "id", iqId == null ? "" : iqId),
            List.of(picture));
    }

    /**
     * Parses a profile-picture response: the {@code <picture>} child's
     * {@code url} attribute (Baileys returns
     * {@code child?.attrs?.url}).
     *
     * @param response response stanza, or null
     * @return picture info, or null when the response is an error, has
     *         no {@code <picture>} child, has status {@code 204} or
     *         {@code 304} (no picture / not modified), or carries no URL
     */
    public static ProfilePictureInfo parse(BinaryNode response) {
        if (response == null || "error".equals(response.attr("type"))) return null;
        BinaryNode pic = response.child("picture");
        if (pic == null) return null;
        String status = pic.attr("status");
        if ("204".equals(status) || "304".equals(status)) return null;
        String url = pic.attr("url");
        if (url == null || url.isBlank()) return null;
        return new ProfilePictureInfo(pic.attr("id"), url, pic.attr("type"), pic.attr("direct_path"));
    }
}
