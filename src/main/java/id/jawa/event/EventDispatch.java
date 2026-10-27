package id.jawa.event;

/**
 * One parsed event ready for {@link WaEventBus#dispatch}: a Baileys event
 * type plus its payload record. Notification parsers return lists of these
 * because a single stanza can produce several events (e.g. a group-create
 * notification yields both {@code chats.upsert} and {@code groups.upsert}).
 *
 * @param type event type
 * @param payload payload record for {@code type}
 */
public record EventDispatch(BaileysEventType type, Object payload) {}
