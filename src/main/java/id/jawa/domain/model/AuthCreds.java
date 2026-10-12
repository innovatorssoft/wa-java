package id.jawa.domain.model;

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

import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;

import java.security.SecureRandom;

public final class AuthCreds {

    public KeyPair25519 noiseKey;

    public KeyPair25519 signedIdentityKey;

    public SignedPreKey signedPreKey;

    public int registrationId;

    public byte[] advSecretKey;

    public int nextPreKeyId = 1;

    public int firstUnuploadedPreKeyId = 1;

    public int accountSyncCounter = 0;

    public byte[] account;

    public String meJid;

    public String meLid;

    public String pushName;

    public String platform;

    public AuthCreds() {}

    public static AuthCreds generate() {
        AuthCreds c = new AuthCreds();
        c.noiseKey         = Curve25519.generateKeyPair();
        c.signedIdentityKey = Curve25519.generateKeyPair();
        c.signedPreKey     = SignedPreKey.generate(1, c.signedIdentityKey);

        c.registrationId   = new SecureRandom().nextInt(0xFFFF + 1);
        c.advSecretKey     = Bytes.random(32);
        return c;
    }
}
