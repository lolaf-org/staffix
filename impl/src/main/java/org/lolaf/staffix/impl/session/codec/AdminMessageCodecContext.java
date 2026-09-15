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
package org.lolaf.staffix.impl.session.codec;

import lombok.Builder;
import lombok.Value;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.impl.session.FixSessionImplState;

import java.util.concurrent.Executor;

/**
 * What an admin codec needs to build a message: the session, its settings, and the registries for its
 * dictionary.
 */
@Value
@Builder(toBuilder = true)
public class AdminMessageCodecContext {

    FixApplication fixApplication;
    FixSessionImpl fixSession;
    FixSessionImplState fixSessionImplState;
    FieldsRegistry fieldsRegistry;
    MessageTypeRegistry messageTypeRegistry;
    MessageFieldsRegistry messageFieldsRegistry;
    FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    FixSessionSettings fixSessionSettings;
    Clock clock;
    Executor executor;
    FixAdminMessagesCodec fixAdminMessagesCodec;
}