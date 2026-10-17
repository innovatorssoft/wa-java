package id.jawa.domain.store;


import id.jawa.protocol.crypto.KeyPair25519;

import java.util.Map;

public interface SignalKeyStore {

    KeyPair25519 getPreKey(int id);

    void putPreKey(int id, KeyPair25519 kp);

    void removePreKey(int id);

    Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive);
}
