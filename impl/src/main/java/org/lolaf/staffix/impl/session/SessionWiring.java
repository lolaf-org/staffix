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
package org.lolaf.staffix.impl.session;

import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.VoidMessageLogger;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.codec.decoders.FixMessageParser;
import org.lolaf.staffix.codec.decoders.FixTFieldsRegistry;
import org.lolaf.staffix.codec.decoders.FixTMessageFieldsRegistry;
import org.lolaf.staffix.codec.decoders.FixTMessageTypeRegistry;
import org.lolaf.staffix.impl.FailSafeFixApplication;
import org.lolaf.staffix.impl.FixSessionRuntimeDependencies;
import org.lolaf.staffix.impl.executor.MessageExecutorsRuntime;
import org.lolaf.staffix.impl.executor.SessionMessageExecutors;
import org.lolaf.staffix.impl.session.codec.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * What a FIX session is built from, and the order its components are put in.
 *
 * <p>Two jobs that only happen once. Working out which dictionary this session speaks, and therefore which
 * registries, encoders and admin codec it uses; and building its components and registering them in band order, the
 * state first so that every other one reads a session that has already moved.
 *
 * <p>The session is handed in half built, which every component then keeps. That is what it costs to have them hold
 * the session rather than the session hold their dependencies: nothing may call back into it while this runs.
 *
 * <p>A component is a local of the constructor, not a field: once registered it lives in
 * {@link FixSessionLayerComponents} and is asked for by class. The fields are what is not a component and has
 * nowhere else to be found, such as the registries, the store, the logger and the parser.
 */
final class SessionWiring {

    final FixSessionId fixSessionId;
    final FixApplication fixApplication;
    final FieldsRegistry fieldsRegistry;
    final MessageTypeRegistry messageTypeRegistry;
    final Clock clock;
    final FixSessionLayerComponents components;
    final FixMessagesStore.FixSessionMessagesStore messagesStore;
    final FixMessagesLogger.Logger messagesLogger;
    final FixAdminMessagesCodec adminMessagesCodec;
    final FixMessageParser messageParser;
    final SessionMessageExecutors messageExecutors;
    final ExecutorService disconnectedSessionsExecutor;

    SessionWiring(FixSessionImpl fixSession, String fixInstanceId, FixSessionSettings settings,
                  FixSessionRuntimeDependencies runtimeDependencies, ScheduledExecutorService scheduler,
                  IOSettings ioSettings, MessageExecutorsRuntime messageExecutorsRuntime, Clock providedClock) {
        this.fixSessionId = settings.getFixSessionId();
        this.fixApplication = new FailSafeFixApplication(runtimeDependencies.getFixApplicationFactory()
                .getInstance(settings.getFixApplicationInstanceId()));
        this.clock = providedClock == null ? ClockImpl.get() : providedClock;
        TimeUnit sendingTimeAccuracy = settings.getSendingTimeAccuracy();

        FixDictionaryId fixDictionaryId = FixDictionaryId.of(settings.getDictionaryId(), dictionaryVersion(fixSessionId));
        this.fieldsRegistry = fieldsRegistry(settings, fixDictionaryId, fixSessionId);
        this.messageTypeRegistry = FixTMessageTypeRegistry.get(fixDictionaryId, fixSessionId.getFixVersion());
        FixMessageEncoderFactory encoderFactory = FixMessageEncoderFactory.Registry.getInstance(fixDictionaryId);

        // before the admin codec, whose encoders report their encoding to it from the moment they are built
        PluginsComponent plugins = new PluginsComponent(fixInstanceId, fixSessionId, fixSession);

        FixSessionScheduleManager scheduleManager = new FixSessionScheduleManager(settings, clock);
        FixSessionStateComponent state = new FixSessionStateComponent(
                settings.getFixSessionType().equals(FixSession.FixSessionType.ACCEPTOR),
                settings.getDesiredSessionState(), scheduleManager);
        this.components = new FixSessionLayerComponents();
        this.messagesStore = new FailSafeFixSessionMessagesStore(runtimeDependencies.getFixMessagesStore().getStore(fixSessionId));
        this.adminMessagesCodec = adminMessagesCodec(fixSession, settings, fixDictionaryId, scheduler, state, plugins);
        this.messagesLogger = runtimeDependencies.getFixMessagesLogger() != null
                ? runtimeDependencies.getFixMessagesLogger().getLogger(fixInstanceId, fixSessionId, messageTypeRegistry)
                : VoidMessageLogger.getInstance();
        this.messageExecutors = messageExecutorsRuntime.newSessionExecutors();
        this.disconnectedSessionsExecutor = runtimeDependencies.getDisconnectedSessionsExecutor();

        OutgoingMessagesComponent outgoingMessages = new OutgoingMessagesComponent(fixSession, components, fixApplication, messagesStore,
                messagesLogger, fixSessionId, clock, sendingTimeAccuracy, ioSettings);
        IntFunction<ByteBuffer> encodersAllocator = settings.isMessageEncodersDirectByteBuffers()
                ? ByteBuffer::allocateDirect : ByteBuffer::allocate;
        CodecsComponent codecs = new CodecsComponent(fixSession, settings, adminMessagesCodec, fixApplication, fieldsRegistry,
                messageTypeRegistry, encoderFactory, encodersAllocator, outgoingMessages::getWriteTasksQueueCapacity,
                fixSessionId, clock, sendingTimeAccuracy, plugins);
        LogonLogoutComponent logonLogout = new LogonLogoutComponent(fixSession, state, components, settings, adminMessagesCodec,
                messagesStore, fixApplication, scheduler, codecs);
        HeldOutgoingMessagesComponent heldOutgoingMessages = new HeldOutgoingMessagesComponent(fixSession, settings);
        RetransmissionComponent retransmission = new RetransmissionComponent(fixSession, settings, adminMessagesCodec, messagesStore,
                fixApplication, fixInstanceId, fixSessionId, scheduler, clock, messageTypeRegistry, fieldsRegistry,
                messagesLogger, heldOutgoingMessages, logonLogout);
        MessageRejectsComponent messageRejects = new MessageRejectsComponent(fixSession, adminMessagesCodec, messagesStore, clock);
        IncomingMessagesComponent incomingMessages = new IncomingMessagesComponent(fixSession, components, state, messagesStore,
                retransmission, messageRejects);
        // after the component that answers it: the parser reports what it decoded straight to the sequence rules
        this.messageParser = new FixMessageParser(fixSessionId.invert(), messageTypeRegistry, fieldsRegistry, messagesLogger,
                settings.getValidationSettings(), clock, incomingMessages);
        AdminOperationsComponent adminOperations = new AdminOperationsComponent(fixSession, state, settings, adminMessagesCodec, messagesStore,
                messageTypeRegistry, fieldsRegistry, clock, sendingTimeAccuracy, fixSessionId, logonLogout);

        // in band order: the state first, so every other one reads a session that has already moved, then the
        // session's own concerns. The plugins and the application follow in start(), once they exist
        components.register(state);
        components.register(retransmission);
        components.register(new CancelOnDisconnectComponent(fixSession, fixApplication, state, scheduler, messageExecutors));
        components.register(heldOutgoingMessages);
        components.register(incomingMessages);
        components.register(messageRejects);
        components.register(outgoingMessages);
        components.register(codecs);
        components.register(adminOperations);
        components.register(new HeartbeatsComponent(fixSession, state, settings, adminMessagesCodec,
                new RttEstimator(settings.getRttMeasurementSettings()), scheduler, clock));
        components.register(new SessionTimeWindowComponent(fixSession, fixApplication, state, scheduleManager, settings, scheduler));
        components.register(logonLogout);
        components.register(plugins);
    }

    /**
     * What cannot be settled before the application has declared its decoders: which plugins want this session, and
     * the notifier, registered last of all so that an application callback sees a session in its final state.
     *
     * <p>Not a reaction to the session starting: the codecs read which plugins are here as they react to it, so the
     * fan out must find them already asked.
     */
    void setupApplicationComponents(FixSessionImpl fixSession, FixSessionRuntimeDependencies runtimeDependencies,
                                    List<FixMessageDecoder> decoders) {
        CodecsComponent codecs = components.get(CodecsComponent.class);
        components.get(PluginsComponent.class).setup(runtimeDependencies, codecs.getIncomingMessageTypes(),
                codecs.getOutgoingMessageTypes());
        components.register(new ApplicationNotifierComponent(fixApplication, fixSession, fieldsRegistry, messageTypeRegistry, decoders));
    }

    /**
     * A FIXT session names the application version its messages are in; a regular one is its own dictionary.
     */
    private static FixRegularVersion dictionaryVersion(FixSessionId fixSessionId) {
        if (fixSessionId.getFixVersion() instanceof FixtVersion) {
            return FixApplVerID.getFixVersionForCode(fixSessionId.getDefaultApplVerID().getCode());
        }
        if (fixSessionId.getFixVersion() instanceof FixRegularVersion) {
            return (FixRegularVersion) fixSessionId.getFixVersion();
        }
        return null;
    }

    /**
     * The dictionary's own registry, wrapped so that fields it does not describe can be added as they arrive when
     * the session allows that, and carrying the two cancel on disconnect fields, which are user defined by nature:
     * the specification does not name them, so each counterparty agrees its own tags.
     */
    private static FieldsRegistry fieldsRegistry(FixSessionSettings settings, FixDictionaryId fixDictionaryId, FixSessionId fixSessionId) {
        FieldsRegistry dictionaryRegistry = FixTFieldsRegistry.get(fixDictionaryId, fixSessionId.getFixVersion());
        FieldsRegistry fieldsRegistry = settings.getValidationSettings().isAllowUnknownFields()
                || settings.getValidationSettings().isAllowUserDefinedFields()
                ? new AddingFieldsOnTheFlyRegistry(settings.getValidationSettings(), dictionaryRegistry) : dictionaryRegistry;
        if (settings.getCancelOnDisconnectSettings().isEnabled()) {
            fieldsRegistry.addUserDefinedField(settings.getCancelOnDisconnectSettings().getCancelOnDisconnectTypeFieldCode(),
                    FieldType.MULTIPLESTRINGVALUE, FieldLocation.BODY);
            fieldsRegistry.addUserDefinedField(settings.getCancelOnDisconnectSettings().getCodTimeoutWindowFieldCode(),
                    FieldType.INT, FieldLocation.BODY);
        }
        return fieldsRegistry;
    }

    private FixAdminMessagesCodec adminMessagesCodec(FixSessionImpl fixSession, FixSessionSettings settings,
                                                     FixDictionaryId fixDictionaryId, ScheduledExecutorService scheduler,
                                                     FixSessionStateComponent state, PluginsComponent plugins) {
        AdminMessageCodecContext context = AdminMessageCodecContext.builder()
                .fixSession(fixSession)
                .messageTypeRegistry(messageTypeRegistry)
                .messageFieldsRegistry(FixTMessageFieldsRegistry.get(fixDictionaryId, fixSessionId.getFixVersion(), fieldsRegistry))
                .fieldsRegistry(fieldsRegistry)
                .fixApplication(fixApplication)
                .fixSessionStateComponent(state)
                .encodingListener(plugins)
                .fixSessionLayerComponents(components)
                .fixSessionMessagesStore(messagesStore)
                .fixSessionSettings(settings)
                .clock(clock)
                .executor(scheduler)
                .build();

        if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_42)) {
            return new Fix42AdminMessagesCodec(context);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_43)) {
            return new Fix43AdminMessagesCodec(context);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_44)) {
            return new Fix44AdminMessagesCodec(context);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50)
                || fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50_SP1)
                || fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50_SP2)) {
            throw new IllegalStateException("Fix version 5.x is only supported using fix version FIXT and a target FIX session defaultApplVerID");
        } else if (fixSessionId.getFixVersion().equals(FixtVersion.FIXT_11)) {
            return new FixTAdminMessagesCodec(context, fixSessionId);
        } else {
            throw new IllegalStateException("Fix version " + fixSessionId.getFixVersion() + " is not supported");
        }
    }
}
