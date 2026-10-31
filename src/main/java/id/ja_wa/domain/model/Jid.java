package id.ja_wa.domain.model;

import java.util.Objects;

/**
 * Parsed WhatsApp Jabber Identifier (JID) representing users, groups, channels, or companion devices.
 *
 * @param user Phone number or entity unique identifier string
 * @param server Domain server address (e.g. "s.whatsapp.net", "g.us", "lid", "newsletter")
 * @param device Multi-device companion device index (0 for primary device)
 * @param agent Multi-agent index
 * @param domainType Binary protocol integer domain categorization
 */
public record Jid(String user, String server, int device, int agent, int domainType) {

    public static final String SERVER_WHATSAPP = "s.whatsapp.net";
    public static final String SERVER_GROUP = "g.us";
    public static final String SERVER_BROADCAST = "broadcast";
    public static final String SERVER_LID = "lid";
    public static final String SERVER_NEWSLETTER = "newsletter";
    public static final String SERVER_HOSTED = "hosted";
    public static final String SERVER_HOSTED_LID = "hosted.lid";
    public static final String SERVER_INTEROP = "interop";
    public static final String SERVER_MSGR = "msgr";
    public static final String SERVER_CALL = "call";
    public static final String SERVER_BOT = "bot";
    public static final String SERVER_CUS = "c.us";

    public static final int DOMAIN_WHATSAPP = 0;
    public static final int DOMAIN_LID = 1;
    public static final int DOMAIN_HOSTED = 128;
    public static final int DOMAIN_HOSTED_LID = 129;

    public Jid {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(server, "server");
    }

    /**
     * Constructs a Jid for a user and server domain with default device 0.
     *
     * @param user Account phone number or username
     * @param server Target server domain
     * @return Created Jid instance
     */
    public static Jid of(String user, String server) {
        return new Jid(user, server, 0, 0, domainTypeFor(server, 0));
    }

    /**
     * Constructs a multi-device Jid with explicit device index.
     *
     * @param user Account phone number or username
     * @param server Target server domain
     * @param device Companion device index
     * @return Created Jid instance
     */
    public static Jid of(String user, String server, int device) {
        return new Jid(user, server, device, 0, domainTypeFor(server, 0));
    }

    /**
     * Checks whether this JID targets a specific companion device index (> 0).
     *
     * @return True if device index is non-zero
     */
    public boolean hasDevice() { return device != 0; }

    /**
     * Checks whether this JID targets an explicit agent index (> 0).
     *
     * @return True if agent index is non-zero
     */
    public boolean hasAgent()  { return agent  != 0; }

    /**
     * Parses a string formatted as user[:device][_agent]@server into a Jid instance.
     *
     * @param s String representation of the WhatsApp JID
     * @return Parsed Jid object, or null if string format is invalid
     */
    public static Jid parse(String s) {
        if (s == null) return null;
        int at = s.indexOf('@');
        if (at < 0) return null;
        String server = s.substring(at + 1);
        String userCombined = s.substring(0, at);

        String userAgent;
        int device = 0;
        int colon = userCombined.indexOf(':');
        if (colon >= 0) {
            userAgent = userCombined.substring(0, colon);
            device = Integer.parseInt(userCombined.substring(colon + 1));
        } else {
            userAgent = userCombined;
        }

        String user;
        int agent = 0;
        int under = userAgent.indexOf('_');
        if (under >= 0) {
            user = userAgent.substring(0, under);
            agent = Integer.parseInt(userAgent.substring(under + 1));
        } else {
            user = userAgent;
        }

        return new Jid(user, server, device, agent, domainTypeFor(server, agent));
    }

    private static int domainTypeFor(String server, int agent) {
        return switch (server) {
            case SERVER_LID         -> DOMAIN_LID;
            case SERVER_HOSTED      -> DOMAIN_HOSTED;
            case SERVER_HOSTED_LID  -> DOMAIN_HOSTED_LID;
            default                 -> agent != 0 ? agent : DOMAIN_WHATSAPP;
        };
    }

    /**
     * Resolves the server domain name corresponding to a binary domain type code.
     *
     * @param domainType Binary protocol domain code
     * @param fallback Default server name to return if domain type is unmapped
     * @return Resolved server domain string
     */
    public static String serverForDomain(int domainType, String fallback) {
        return switch (domainType) {
            case DOMAIN_LID         -> SERVER_LID;
            case DOMAIN_HOSTED      -> SERVER_HOSTED;
            case DOMAIN_HOSTED_LID  -> SERVER_HOSTED_LID;
            default                 -> fallback;
        };
    }

    /**
     * Formats this JID into a canonical string representation.
     *
     * @return Full string representation including device and agent components
     */
    public String asString() {
        StringBuilder sb = new StringBuilder(user.length() + server.length() + 8);
        sb.append(user);
        if (agent != 0)  sb.append('_').append(agent);
        if (device != 0) sb.append(':').append(device);
        sb.append('@').append(server);
        return sb.toString();
    }

    /**
     * Strips device and agent components from a string JID to yield bare user@server.
     *
     * @param s String representation of JID
     * @return Normalized bare JID string
     */
    public static String bare(String s) {
        if (s == null) return null;
        int at = s.indexOf('@');
        if (at < 0) return s;
        String userCombined = s.substring(0, at);
        int colon = userCombined.indexOf(':');
        String user = colon >= 0 ? userCombined.substring(0, colon) : userCombined;
        return user + "@" + s.substring(at + 1);
    }

    /**
     * Returns the normalized bare user@server representation of this JID.
     *
     * @return Bare JID string
     */
    public String toBareString() {
        return user + "@" + server;
    }

    @Override
    public String toString() { return asString(); }
}
