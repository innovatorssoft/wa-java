package id.jawa.feature.signal;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.domain.store.FileSessionStorage;
import id.jawa.protocol.crypto.Curve25519;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

import java.util.List;

public final class JaWaProtocolStore extends InMemorySignalProtocolStore {

    private final FileSessionStorage sessionStorage;

    public JaWaProtocolStore(AuthCreds creds) {
        this(creds, null);
    }

    public JaWaProtocolStore(AuthCreds creds, FileSessionStorage sessionStorage) {
        super(toIdentityKeyPair(creds), creds.registrationId);
        this.sessionStorage = sessionStorage;
        if (creds.signedPreKey != null) {
            storeSignedPreKey(creds.signedPreKey.keyId(), toSignedPreKeyRecord(creds.signedPreKey));
        }
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        return sessionStorage != null ? sessionStorage.get(address) : super.loadSession(address);
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        return sessionStorage != null
            ? sessionStorage.contains(address)
            : super.containsSession(address);
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        if (sessionStorage != null) sessionStorage.put(address, record);
        else super.storeSession(address, record);
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        if (sessionStorage != null) sessionStorage.delete(address);
        else super.deleteSession(address);
    }

    @Override
    public void deleteAllSessions(String name) {
        if (sessionStorage != null) sessionStorage.deleteAll(name);
        else super.deleteAllSessions(name);
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        return sessionStorage != null
            ? sessionStorage.subDeviceIds(name)
            : super.getSubDeviceSessions(name);
    }

    private static IdentityKeyPair toIdentityKeyPair(AuthCreds creds) {
        try {
            byte[] prefixedPub = Curve25519.prependType(creds.signedIdentityKey.publicKey());
            IdentityKey pub = new IdentityKey(Curve.decodePoint(prefixedPub, 0));
            return new IdentityKeyPair(pub, Curve.decodePrivatePoint(creds.signedIdentityKey.privateKey()));
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid identity key in AuthCreds", e);
        }
    }

    private static SignedPreKeyRecord toSignedPreKeyRecord(SignedPreKey spk) {
        try {
            byte[] prefixedPub = Curve25519.prependType(spk.keyPair().publicKey());
            ECKeyPair kp = new ECKeyPair(
                Curve.decodePoint(prefixedPub, 0),
                Curve.decodePrivatePoint(spk.keyPair().privateKey()));
            return new SignedPreKeyRecord(spk.keyId(), 0L, kp, spk.signature());
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid signed pre-key in AuthCreds", e);
        }
    }

    @SuppressWarnings("unused")
    private static void touchKeyHelper() { KeyHelper.generateIdentityKeyPair(); }
}
