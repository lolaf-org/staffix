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
package org.lolaf.staffix.api;

import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The inbound half: one listening socket, and every session a counterparty has logged on to it.
 *
 * <p>An acceptor holds many sessions where a {@link FixInitiator} holds one, which is why the session accessors
 * are plural and why {@link #broadcast} exists - sending the same message to a filtered set of them is the case
 * worth not writing out by hand.
 *
 * <p>{@link #getSessions()} is every session configured on this acceptor, {@link #getConnectedSessions()} only
 * those with a connection whose Logon was matched to them. A counterparty that has never connected still has a
 * session; the distinction is what most callers actually want.
 */
public interface FixAcceptor extends Startable<FixAcceptor> {

    /**
     * Stops, giving sessions up to {@link FixAcceptorBuilder#getShutdownMaxDelay()} to log out.
     */
    FixAcceptor stop();

    /**
     * The sessions with a connection up, logged on or still completing the Logon exchange.
     */
    Set<FixSession> getConnectedSessions();

    /**
     * Every session configured on this acceptor, connected or not.
     */
    Set<FixSession> getSessions();

    /**
     * Broadcast a message to connected sessions
     *
     * @param encoder               the message to send
     * @param sendingTime           the sending time or null for current time
     * @param fixSessionPredicate   target sessions predicate to send the message or null for all connected sessions
     * @param connectedSessionsOnly flag to indicate if the message should be sent to connected sessions only
     */
    void broadcast(FixMessageEncoder<?> encoder, UTCTime sendingTime, Predicate<FixSession> fixSessionPredicate, boolean connectedSessionsOnly);

    /**
     * A snapshot of the settings of every session configured on this acceptor: a later change in its settings store
     * shows in the next call, not in a set already returned.
     */
    Set<FixSessionSettings> getConfiguredSessionsSettings();

    /**
     * Told what happens to incoming connections, for auditing who connects. Every method logs by default. Runs on the
     * IO thread, so it must not block.
     */
    interface FixSessionEventsListener {

        /**
         * Called when a connection's first message has been matched to a configured session, before its Logon is
         * processed.
         */
        default void onFixSessionAccepted(FixSessionId fixSession) {
            LoggerFactory.getLogger(FixSessionEventsListener.class).info("Fix session {} accepted", fixSession);
        }

        /**
         * Called when a session is rejected
         *
         * @param fixSession         the matched FIX session or null if we received an unknown fix session
         * @param rejectionException the rejection exception
         */
        default void onFixSessionRejected(FixSessionId fixSession, Exception rejectionException) {
            LoggerFactory.getLogger(FixSessionEventsListener.class).info("Fix session {} rejected: {}", fixSession, rejectionException.getMessage());
        }

        /**
         * Called when a connection fails its TLS handshake, so before any session can be named.
         */
        default void onFailedSSLHandshake(InetSocketAddress remoteAddress, SSLHandshakeException exception) {
            LoggerFactory.getLogger(FixSessionEventsListener.class).info("Failed SSL handshake from remote ip {}:{}", remoteAddress, exception);
        }
    }

    /**
     * Why an incoming connection was refused. The subclasses name the reasons.
     */
    class RejectedSessionException extends Exception {

        public RejectedSessionException(String s) {
            super(s);
        }

        /**
         * Whether the peer is told why with a Logout before the connection closes. False where answering would tell
         * an unauthorised peer more than it should know.
         */
        public boolean shouldSendLogout() {
            return true;
        }
    }

    /**
     * The Logon's BeginString(8) names a FIX version this acceptor has no dictionary for.
     */
    class UnsupportedFixVersionException extends RejectedSessionException {

        public UnsupportedFixVersionException(String version) {
            super("Unsupported fix version " + version);
        }
    }

    /**
     * The Logon's CompIDs match no session configured on this acceptor, or could not be read at all.
     */
    class UnknownFixSessionException extends RejectedSessionException {

        public UnknownFixSessionException(String fixSessionId) {
            super("Unknown fix session " + fixSessionId);
        }

        public UnknownFixSessionException() {
            super("Unable to define FIX session from incoming bytes");
        }
    }

    /**
     * The connection comes from an address outside the session's allowed addresses. No Logout is sent.
     */
    class RejectedIpException extends RejectedSessionException {

        public RejectedIpException(InetAddress provided, Collection<InetAddress> allowed) {
            super("Session is only allowed to connect using ip address(es) " + allowed + " but got " + provided);
        }

        @Override
        public boolean shouldSendLogout() {
            return false;
        }
    }

    /**
     * The peer's TLS certificate is not one the session allows. No Logout is sent.
     */
    class RejectedCertificateException extends RejectedSessionException {

        public RejectedCertificateException(Collection<Certificate> provided, Collection<Certificate> allowed) {
            super("Session is only allowed to connect using certificate(s): " + certsDnToString(allowed) + " but got: " + certsDnToString(provided));
        }

        private static String certsDnToString(Collection<Certificate> certificates) {
            if (certificates == null || certificates.isEmpty()) {
                return "none";
            }
            if (certificates.iterator().next() instanceof X509Certificate) {
                return certificates.stream().map(c -> ((X509Certificate) c).getSubjectX500Principal().toString()).collect(Collectors.joining("."));
            }
            return certificates.stream().map(Certificate::toString).collect(Collectors.joining(","));
        }

        @Override
        public boolean shouldSendLogout() {
            return false;
        }
    }

    /**
     * The session already has a connection, and a second Logon for it is refused.
     */
    class MultipleLogonException extends RejectedSessionException {

        public MultipleLogonException() {
            super("Multiple login for session are not allowed");
        }
    }

    /**
     * The session's desired state is DISCONNECTED: it must not be up at all, so the connection is refused before
     * anything of the session layer runs on it.
     */
    class SessionNotAcceptingConnectionsException extends RejectedSessionException {

        public SessionNotAcceptingConnectionsException() {
            super("Session is not setup to allow connections for now");
        }

        @Override
        public boolean shouldSendLogout() {
            // a session refusing to be up cannot answer: sending a Logout is the session layer this connection is
            // being refused, and the peer is meant to see nothing but the connection going
            return false;
        }
    }
}