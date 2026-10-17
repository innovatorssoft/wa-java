package id.jawa.feature.signal;


import id.jawa.domain.model.Jid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for bulk installing Signal sessions from fetched pre-key bundles.
 */
public final class SessionBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(SessionBootstrap.class);

    private SessionBootstrap() {}

    public static List<SignalProtocolAddress> installAll(JaWaProtocolStore store,
                                                         List<PreKeyBundleFetcher.FetchedBundle> bundles) {
        List<SignalProtocolAddress> ok = new ArrayList<>();
        for (var fb : bundles) {
            SignalProtocolAddress addr = addressFor(fb.address());
            SessionBuilder builder = new SessionBuilder(store, addr);
            try {
                builder.process(fb.bundle());
                ok.add(addr);
                LOG.debug("Session installed for {}", addr);
            } catch (InvalidKeyException | UntrustedIdentityException e) {
                LOG.warn("Failed to install session for {}: {}", addr, e.toString());
            }
        }
        return ok;
    }

    public static SignalProtocolAddress addressFor(Jid jid) {
        return new SignalProtocolAddress(jid.user(), jid.device());
    }
}
