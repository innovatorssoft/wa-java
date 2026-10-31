package id.ja_wa.feature.appstate;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable-app-state version state for one collection, mirroring Baileys'
 * LTHash state object ({@code { version, hash, indexValueMap }} in
 * {@code lib/Utils/chat-utils.js}: {@code newLTHashState()} /
 * {@code ensureLTHashStateVersion()}).
 *
 * <p>{@code version} is the collection's sync version (0 for a fresh
 * state). {@code hash} is the 128-byte LtHash. {@code indexValueMap}
 * maps the standard-base64 {@code indexMac} of each live index to its
 * current {@code valueMac}, so a later SET can subtract the previous
 * value and a later REMOVE can subtract the stored one.
 *
 * <p>Baileys stores this under the key {@code 'app-state-sync-version'}
 * in its key store; wa-java's counterpart is
 * {@link AppStateVersionStore}, durably backed since Increment 5 by
 * {@link FileAppStateVersionStore} (or in-memory, for clients with no
 * durable backend). Until a durable version store is wired into a
 * given JaWaClient, a transport that starts from {@link #fresh()}
 * assumes the server accepts a patch built on a fresh LtHash state —
 * that assumption is documented on {@link AppPatchTransport}, not
 * hidden here.
 */
public record LtHashState(long version, byte[] hash, Map<String, byte[]> indexValueMap) {

    /** A fresh state: version 0, all-zero 128-byte hash, empty map. */
    public static LtHashState fresh() {
        return new LtHashState(0L, new byte[128], Map.of());
    }

    public LtHashState {
        if (hash == null || hash.length != 128) {
            throw new IllegalArgumentException("LtHash hash must be 128 bytes");
        }
        hash = hash.clone();
        if (indexValueMap == null) {
            indexValueMap = Map.of();
        } else {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : indexValueMap.entrySet()) {
                copy.put(e.getKey(), e.getValue() == null ? null : e.getValue().clone());
            }
            indexValueMap = Collections.unmodifiableMap(copy);
        }
    }

    /** @return a mutable copy of the index-value map (values also copied) */
    public Map<String, byte[]> mutableIndexValueMap() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : indexValueMap.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? null : e.getValue().clone());
        }
        return copy;
    }

    /** Standard-base64 map key Baileys uses for an index MAC. */
    public static String mapKey(byte[] indexMac) {
        return Base64.getEncoder().encodeToString(indexMac);
    }
}
