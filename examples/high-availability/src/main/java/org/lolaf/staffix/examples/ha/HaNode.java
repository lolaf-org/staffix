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
package org.lolaf.staffix.examples.ha;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.primos.ClusterConfig;
import org.lolaf.primos.Peer;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.primos.PrimosLeaderElectionSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.lolaf.staffix.examples.ha.Topology.*;

/**
 * One node of the acceptor pair, {@code 1} or {@code 2}: the same engine on both, answering each Email with an Email
 * naming the node. Its stores and its primos term and vote are kept under {@code target/node-<id>}, so a restarted node
 * carries on from them. Stopped by SIGTERM, it stops its engine, which hands the sessions and the leadership over.
 */
@Slf4j
public final class HaNode {

    public static void main(String[] args) throws InterruptedException {
        int nodeId = Integer.parseInt(args[0]);
        int otherId = 3 - nodeId;
        Path directory = Path.of("target", "node-" + nodeId);

        FixEngine engine = FixEngineBuilder.builder()
                .instanceId("node-" + nodeId)
                .fixMessagesStore(FileMessageStoreSettings.builder()
                        .storageDirectoryPath(directory.resolve("store").toString())
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId("app")
                        .application("app", new Gateway(nodeId))
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(FixSessionSettings.builder()
                                .fixSessionId(GATEWAY_SESSION)
                                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                                .fixApplicationFactoryInstanceId("app")
                                .fixApplicationInstanceId("app")
                                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                                .haSessionSettings(HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
                                .build())
                        .build())
                .highAvailability(ReplicationHaSettings.builder()
                        .nodeId(String.valueOf(nodeId))
                        .peer("1", replicationAddress(1))
                        .peer("2", replicationAddress(2))
                        .leaderElection(PrimosLeaderElectionSettings.builder()
                                .cluster(ClusterConfig.builder()
                                        .applicationName(CLUSTER)
                                        .nodeId(nodeId)
                                        .peer(Peer.application(otherId, HOST, electionPort(otherId)))
                                        .witness(Peer.witness(WITNESS_ID, HOST, WITNESS_PORT))
                                        .build())
                                .stateDirectory(directory.resolve("primos"))
                                .bindAddress(new InetSocketAddress(HOST, electionPort(nodeId)))
                                .clusterSecret(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8))
                                .build())
                        .build())
                .build().instance().start();

        // Started on both nodes, it binds only on the leader.
        engine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixAddress(nodeId)).build()).start();
        log.info("Node {} started, FIX on {}", nodeId, fixAddress(nodeId));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Node {} stopping", nodeId);
            engine.stop(Deadline.of(Duration.ofSeconds(10)));
            log.info("Node {} stopped", nodeId);
        }, "node-stop"));
        Thread.currentThread().join();
    }

    @RequiredArgsConstructor
    private static final class Gateway implements FixApplication {

        private final int nodeId;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public FixDictionaryId getDictionaryId() {
            return FixDictionaryId.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            log.info("Node {}: the client logged on", nodeId);
        }

        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logout) {
            log.info("Node {}: logged out, {}", nodeId, message);
        }

        @Override
        public void onSequenceReset(FixSession session, long newSeqNum, boolean gapFill) {
            log.info("Node {}: gap filled up to MsgSeqNum {}", nodeId, newSeqNum);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session, Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.Email);
            EmailEncoder answer = session.newEncoder(EmailEncoder.class).asReusable();
            return List.of(new FixMessageDecoder() {
                private String subject;

                @Override
                public MessageType getMessageType() {
                    return MessageTypes.Email;
                }

                @Override
                public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                    mapper.mapStringField(Subject.get(), value -> subject = value, null);
                }

                @Override
                public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                    session.send(answer.begin().setSubject(subject + " answered by node " + nodeId), null);
                }
            });
        }
    }
}
