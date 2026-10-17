package id.jawa.domain.model;

/**
 * Information describing a companion WhatsApp device linked to an account.
 *
 * @param id Companion device registration index (e.g. 0 for primary phone, >0 for companion)
 * @param keyIndex Identity key derivation index
 * @param hosted True if device is cloud-hosted by Meta infrastructure
 */
public record DeviceInfo(int id, int keyIndex, boolean hosted) {

    /**
     * Constructs a DeviceInfo instance with default non-hosted status.
     *
     * @param id Companion device index
     * @param keyIndex Key derivation index
     */
    public DeviceInfo(int id, int keyIndex) {
        this(id, keyIndex, false);
    }

    /**
     * Converts device coordinates to a full WhatsApp JID with device component.
     *
     * @param user Account phone number or username
     * @param server Server domain string (e.g. s.whatsapp.net, lid)
     * @return Formatted multi-device Jid object
     */
    public Jid asJid(String user, String server) {
        int domain = switch (server) {
            case Jid.SERVER_LID         -> Jid.DOMAIN_LID;
            case Jid.SERVER_HOSTED      -> Jid.DOMAIN_HOSTED;
            case Jid.SERVER_HOSTED_LID  -> Jid.DOMAIN_HOSTED_LID;
            default                     -> Jid.DOMAIN_WHATSAPP;
        };
        return new Jid(user, server, id, 0, domain);
    }
}
