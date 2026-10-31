package id.ja_wa.feature.appstate;

/**
 * Storage for per-collection app-state sync versions — the wa-java
 * counterpart of Baileys' {@code authState.keys} entries under
 * {@code 'app-state-sync-version'} ({@code lib/Socket/chats.js}:
 * {@code appPatch} reads {@code { [name]: currentSyncVersion }} and
 * writes back the new state after the IQ succeeds).
 *
 * <p>wa-java previously had no such store at all; Increment 5 added
 * {@link FileAppStateVersionStore} (SQLite table, or files under
 * {@code signalDir}), wired into {@code JaWaClient} at connect time,
 * with {@link InMemoryAppStateVersionStore} remaining as the fallback
 * for clients with no durable backend.
 *
 * <p>Contract: {@link #load(String)} returns null for a collection with
 * no stored state (the transport then starts from
 * {@link LtHashState#fresh()}); {@link #save(String, LtHashState)}
 * overwrites. Keys are collection names — the patch {@code type}
 * strings such as {@code "critical_unblock_low"} and {@code "regular"}.
 */
public interface AppStateVersionStore {

    /** @return stored state for the collection, or null if none */
    LtHashState load(String collectionName);

    /** Persists the state for the collection. */
    void save(String collectionName, LtHashState state);
}
