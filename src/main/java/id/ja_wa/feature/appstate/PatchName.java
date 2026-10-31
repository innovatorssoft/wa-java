package id.ja_wa.feature.appstate;


public enum PatchName {

    CRITICAL_BLOCK("critical_block"),

    CRITICAL_UNBLOCK_LOW("critical_unblock_low"),

    REGULAR_LOW("regular_low"),

    REGULAR_HIGH("regular_high"),

    REGULAR("regular");

    public final String wire;

    PatchName(String wire) { this.wire = wire; }

    public static PatchName fromWire(String s) {
        for (PatchName p : values()) if (p.wire.equals(s)) return p;
        return null;
    }
}
