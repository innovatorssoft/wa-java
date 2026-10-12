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

import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyRecord;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemorySenderKeyStore implements SenderKeyStore {

    private final ConcurrentMap<SenderKeyName, byte[]> records = new ConcurrentHashMap<>();
    private final FileSenderKeyStorage storage;

    public InMemorySenderKeyStore() {
        this(null);
    }

    public InMemorySenderKeyStore(FileSenderKeyStorage storage) {
        this.storage = storage;
    }

    @Override
    public void storeSenderKey(SenderKeyName senderKeyName, SenderKeyRecord record) {
        byte[] serialized = record.serialize();
        records.put(senderKeyName, serialized);
        if (storage != null) storage.put(senderKeyName, serialized);
    }

    @Override
    public SenderKeyRecord loadSenderKey(SenderKeyName senderKeyName) {
        byte[] serialized = records.get(senderKeyName);
        if (serialized == null && storage != null) serialized = storage.get(senderKeyName);
        if (serialized == null) return new SenderKeyRecord();
        try {
            return new SenderKeyRecord(serialized);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("corrupt sender-key record for " + senderKeyName, e);
        }
    }

    public boolean isEmpty(SenderKeyName name) {
        byte[] s = records.get(name);
        if ((s == null || s.length == 0) && storage != null) s = storage.get(name);
        return s == null || s.length == 0;
    }
}
