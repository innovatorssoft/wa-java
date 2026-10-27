package id.jawa.event;

import id.jawa.feature.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaEventBusTest {

    private static class RecordingListener implements WaEventListener {
        final List<Object> typed = new ArrayList<>();
        final List<BaileysEventType> genericTypes = new ArrayList<>();
        final List<Object> genericPayloads = new ArrayList<>();

        @Override
        public void onConnectionUpdate(ConnectionUpdate update) { typed.add(update); }

        @Override
        public void onMessageUpsert(MessageUpsert upsert) { typed.add(upsert); }

        @Override
        public void onChatsUpsert(ChatsUpsert upsert) { typed.add(upsert); }

        @Override
        public void onEvent(BaileysEventType type, Object payload) {
            genericTypes.add(type);
            genericPayloads.add(payload);
        }
    }

    @Test
    void multipleListenersAllReceiveTypedAndGeneric() {
        WaEventBus bus = new WaEventBus();
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        bus.addListener(first);
        bus.addListener(second);

        ConnectionUpdate update = ConnectionUpdate.open();
        bus.dispatch(BaileysEventType.CONNECTION_UPDATE, update);

        assertThat(first.typed).containsExactly(update);
        assertThat(second.typed).containsExactly(update);
        assertThat(first.genericTypes).containsExactly(BaileysEventType.CONNECTION_UPDATE);
        assertThat(second.genericTypes).containsExactly(BaileysEventType.CONNECTION_UPDATE);
        assertThat(first.genericPayloads).containsExactly(update);
    }

    @Test
    void removalStopsDelivery() {
        WaEventBus bus = new WaEventBus();
        RecordingListener listener = new RecordingListener();
        bus.addListener(listener);
        assertThat(bus.listenerCount()).isEqualTo(1);
        bus.removeListener(listener);
        assertThat(bus.listenerCount()).isEqualTo(0);

        bus.dispatch(BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.open());
        assertThat(listener.typed).isEmpty();
        assertThat(listener.genericTypes).isEmpty();
    }

    @Test
    void throwingListenerDoesNotPreventOthers() {
        WaEventBus bus = new WaEventBus();
        WaEventListener throwing = new WaEventListener() {
            @Override
            public void onConnectionUpdate(ConnectionUpdate update) {
                throw new IllegalStateException("typed boom");
            }

            @Override
            public void onEvent(BaileysEventType type, Object payload) {
                throw new IllegalStateException("generic boom");
            }
        };
        RecordingListener good = new RecordingListener();
        bus.addListener(throwing);
        bus.addListener(good);

        ConnectionUpdate update = ConnectionUpdate.open();
        bus.dispatch(BaileysEventType.CONNECTION_UPDATE, update);

        assertThat(good.typed).containsExactly(update);
        assertThat(good.genericTypes).containsExactly(BaileysEventType.CONNECTION_UPDATE);
    }

    @Test
    void genericFallbackFiresForUnwiredEventsToo() {
        WaEventBus bus = new WaEventBus();
        RecordingListener listener = new RecordingListener();
        bus.addListener(listener);

        ChatsUpsert upsert = new ChatsUpsert(List.of("123@s.whatsapp.net"));
        bus.dispatch(BaileysEventType.CHATS_UPSERT, upsert);

        assertThat(listener.typed).containsExactly(upsert);
        assertThat(listener.genericTypes).containsExactly(BaileysEventType.CHATS_UPSERT);
    }

    @Test
    void messageUpsertDispatchCarriesDecodedListAndNotifyType() {
        WaEventBus bus = new WaEventBus();
        RecordingListener listener = new RecordingListener();
        bus.addListener(listener);

        MessageReceiver.Decoded decoded = new MessageReceiver.Decoded(
            "123@s.whatsapp.net", null, "MSG1", null, null, "hi", null, 1700000000L);
        MessageUpsert upsert = new MessageUpsert(List.of(decoded), "notify");
        bus.dispatch(BaileysEventType.MESSAGES_UPSERT, upsert);

        assertThat(listener.typed).containsExactly(upsert);
        assertThat(((MessageUpsert) listener.typed.get(0)).messages()).containsExactly(decoded);
        assertThat(((MessageUpsert) listener.typed.get(0)).type()).isEqualTo("notify");
    }

    @Test
    void dispatchWithNullTypeIsNoop() {
        WaEventBus bus = new WaEventBus();
        RecordingListener listener = new RecordingListener();
        bus.addListener(listener);
        bus.dispatch(null, ConnectionUpdate.open());
        assertThat(listener.typed).isEmpty();
        assertThat(listener.genericTypes).isEmpty();
    }

    @Test
    void fromNameMapsAll34BaileysNamesAndNullForUnknown() {
        assertThat(BaileysEventType.values()).hasSize(34);
        assertThat(BaileysEventType.fromName("connection.update"))
            .isEqualTo(BaileysEventType.CONNECTION_UPDATE);
        assertThat(BaileysEventType.fromName("messages.upsert"))
            .isEqualTo(BaileysEventType.MESSAGES_UPSERT);
        assertThat(BaileysEventType.fromName("message-receipt.update"))
            .isEqualTo(BaileysEventType.MESSAGE_RECEIPT_UPDATE);
        assertThat(BaileysEventType.fromName("presence.update"))
            .isEqualTo(BaileysEventType.PRESENCE_UPDATE);
        assertThat(BaileysEventType.fromName("call"))
            .isEqualTo(BaileysEventType.CALL);
        assertThat(BaileysEventType.fromName("message-capping.update"))
            .isEqualTo(BaileysEventType.MESSAGE_CAPPING_UPDATE);
        assertThat(BaileysEventType.fromName("event"))
            .isEqualTo(BaileysEventType.EVENT);
        // Every constant round-trips through its own Baileys name.
        for (BaileysEventType type : BaileysEventType.values()) {
            assertThat(BaileysEventType.fromName(type.baileysName())).isEqualTo(type);
        }
        assertThat(BaileysEventType.fromName("blocklist.set")).isNull();
        assertThat(BaileysEventType.fromName("not.a.real.event")).isNull();
        assertThat(BaileysEventType.fromName(null)).isNull();
    }

    @Test
    void enumBaileysNamesAreUnique() {
        Set<String> names = new HashSet<>();
        for (BaileysEventType type : BaileysEventType.values()) {
            assertThat(names.add(type.baileysName()))
                .as("duplicate Baileys name: %s", type.baileysName())
                .isTrue();
        }
        assertThat(names).hasSize(34);
    }
}
