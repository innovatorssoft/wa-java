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

import id.jawa.protocol.crypto.Hkdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record AppStateKey(byte[] keyId, byte[] keyData) {

    public AppStateKey {
        if (keyData != null && keyData.length != 32) {
            throw new IllegalArgumentException("app-state keyData must be 32 bytes, got " + keyData.length);
        }
    }

    public record Expanded(byte[] index, byte[] valueEnc, byte[] valueMac,
                           byte[] snapshotMac, byte[] patchMac) {}

    public Expanded expand() {
        byte[] info = "WhatsApp Mutation Keys".getBytes(StandardCharsets.UTF_8);
        byte[] out = Hkdf.derive(keyData, new byte[0], info, 160);
        return new Expanded(
            Arrays.copyOfRange(out, 0,   32),
            Arrays.copyOfRange(out, 32,  64),
            Arrays.copyOfRange(out, 64,  96),
            Arrays.copyOfRange(out, 96,  128),
            Arrays.copyOfRange(out, 128, 160));
    }
}
