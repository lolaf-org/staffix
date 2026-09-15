/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.api.msg;

import java.util.function.IntSupplier;

/**
 * One MsgType(35) value from a dictionary.
 *
 * <p>{@link #isAdmin()} decides who handles the message - an admin message is the session layer's and never
 * reaches an application - and {@link #isStorable()} whether it goes to the message store, since only a message
 * that can be resent needs keeping. It is an {@link IntSupplier} so the parser can route on a hash of the code
 * rather than on the string.
 */
public interface MessageType extends IntSupplier {

    /**
     * The FIX protocol reserves the MsgType(35) values starting with this character for user defined messages.
     */
    char USER_DEFINED_PREFIX = 'U';

    /**
     * Create an instance of MessageType, warning the provided implementation will not match against instances stored
     * into {@link MessageTypeRegistry} which are singleton by design.
     * Except for writing tests this method should probably never be used
     */
    static MessageType of(String code, boolean admin) {
        return new MessageTypeImpl(code, admin);
    }

    String code();

    boolean isAdmin();

    /**
     * Whether this is a user defined message type, i.e. one whose MsgType(35) starts with
     * {@link #USER_DEFINED_PREFIX}. Such a message type is a valid one even when the dictionary in use does not
     * define it: it is then merely an unsupported one, which changes how a received message of that type has to be
     * rejected (a business level rejection rather than a session level one).
     */
    default boolean isUserDefined() {
        return !code().isEmpty() && code().charAt(0) == USER_DEFINED_PREFIX;
    }

    /**
     * Whether messages of this type must be recorded in the message store (and thus be available for resend).
     * All non-admin (business) messages are storable; among admin messages only {@link CoreMessageType#REJECT}
     * is storable. This rule is resolved at code-generation time so implementations return a constant.
     */
    boolean isStorable();

    byte[] serialized();
}