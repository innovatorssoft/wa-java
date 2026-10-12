package id.jawa.domain.store;

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

import id.jawa.protocol.crypto.KeyPair25519;

import java.util.Map;

public interface SignalKeyStore {

    KeyPair25519 getPreKey(int id);

    void putPreKey(int id, KeyPair25519 kp);

    void removePreKey(int id);

    Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive);
}
