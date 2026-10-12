package id.jawa.feature.appstate;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

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
