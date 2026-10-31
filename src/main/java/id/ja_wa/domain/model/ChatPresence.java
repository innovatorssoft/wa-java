package id.ja_wa.domain.model;

/**
 * Ephemeral chat typing and recording presence indicators.
 */
public enum ChatPresence {

    /**
     * User is actively typing a text message in the chat.
     */
    COMPOSING,

    /**
     * User is actively recording an audio note or voice message.
     */
    RECORDING,

    /**
     * User has stopped typing or recording.
     */
    PAUSED
}
