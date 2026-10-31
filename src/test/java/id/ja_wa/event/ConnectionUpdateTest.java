package id.ja_wa.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionUpdateTest {

    @Test
    void recordConstructionAndFactories() {
        ConnectionUpdate open = ConnectionUpdate.open();
        assertThat(open.connection()).isEqualTo(ConnectionUpdate.ConnectionState.OPEN);
        assertThat(open.qr()).isNull();
        assertThat(open.isNewLogin()).isNull();

        ConnectionUpdate connecting = ConnectionUpdate.connecting();
        assertThat(connecting.connection()).isEqualTo(ConnectionUpdate.ConnectionState.CONNECTING);

        ConnectionUpdate qr = ConnectionUpdate.qr("QR-STRING");
        assertThat(qr.qr()).isEqualTo("QR-STRING");
        // QR updates are partials: no connection state is asserted for them.
        assertThat(qr.connection()).isNull();

        ConnectionUpdate isNewLogin = ConnectionUpdate.isNewLogin(true);
        assertThat(isNewLogin.isNewLogin()).isTrue();
        assertThat(isNewLogin.connection()).isNull();

        ConnectionUpdate close = ConnectionUpdate.close("reason=401");
        assertThat(close.connection()).isEqualTo(ConnectionUpdate.ConnectionState.CLOSE);
        assertThat(close.lastDisconnectError()).isEqualTo("reason=401");
        assertThat(close.lastDisconnectDateEpochMs()).isNotNull();

        ConnectionUpdate closeDated = ConnectionUpdate.close("conflict", 1700000000000L);
        assertThat(closeDated.lastDisconnectDateEpochMs()).isEqualTo(1700000000000L);
    }

    @Test
    void fullPartialRecordAndReachoutTimeLock() {
        ConnectionUpdate.ReachoutTimeLock lock =
            new ConnectionUpdate.ReachoutTimeLock(true, 1700000000000L, "strict");
        ConnectionUpdate update = new ConnectionUpdate(
            ConnectionUpdate.ConnectionState.OPEN, null, true, false, true,
            null, null, lock);
        assertThat(update.receivedPendingNotifications()).isTrue();
        assertThat(update.isNewLogin()).isFalse();
        assertThat(update.isOnline()).isTrue();
        assertThat(update.reachoutTimeLock().isActive()).isTrue();
        assertThat(update.reachoutTimeLock().timeEnforcementEndsEpochMs()).isEqualTo(1700000000000L);
        assertThat(update.reachoutTimeLock().enforcementType()).isEqualTo("strict");
    }

    @Test
    void connectionStateEnumCoversBaileysValues() {
        assertThat(ConnectionUpdate.ConnectionState.valueOf("OPEN")).isNotNull();
        assertThat(ConnectionUpdate.ConnectionState.valueOf("CONNECTING")).isNotNull();
        assertThat(ConnectionUpdate.ConnectionState.valueOf("CLOSE")).isNotNull();
        assertThat(ConnectionUpdate.ConnectionState.valueOf("UNKNOWN")).isNotNull();
    }

    @Test
    void credsUpdatePairedFactory() {
        CredsUpdate creds = CredsUpdate.paired("123@s.whatsapp.net", "Javed");
        assertThat(creds.meJid()).isEqualTo("123@s.whatsapp.net");
        assertThat(creds.pushName()).isEqualTo("Javed");
        assertThat(creds.accountSyncCounter()).isNull();
        assertThat(creds.lastAccountSyncTimestamp()).isNull();
        assertThat(creds.myAppStateKeyId()).isNull();
    }
}
