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
package org.lolaf.staffix.stores.messages.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract test class for {@link FixMessagesStore} implementations.
 * Concrete test classes should extend this class and implement the {@link #createStore()} method
 * to provide their specific store implementation.
 */
public abstract class AbstractMessagesStoreTest {

    protected FixMessagesStore messagesStore;
    protected FixSessionId sessionId;

    /**
     * Create and return a new instance of the store implementation to be tested.
     * This method is called before each test.
     *
     * @return a new store instance
     */
    protected abstract FixMessagesStore createStore();

    /**
     * Optional lifecycle hook called before each test, after the store is created.
     * Override this method to perform additional setup if needed.
     */
    protected void setUp() throws Exception {
        // Override in subclasses if needed
    }

    /**
     * Optional lifecycle hook called after each test, before the store is stopped.
     * Override this method to perform additional cleanup if needed.
     */
    protected void tearDown() throws Exception {
        // Override in subclasses if needed
    }

    @BeforeEach
    final void setUpBase() throws Exception {
        sessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        messagesStore = createStore();
        setUp();
    }

    @AfterEach
    final void tearDownBase() throws Exception {
        tearDown();
        if (messagesStore != null) {
            messagesStore.stop(Deadline.unlimited());
        }
    }

    @Test
    void shouldStartAndStopSuccessfully() {
        // When
        messagesStore.start();

        // Then
        assertThat(messagesStore.isStarted()).isTrue();
        assertThat(messagesStore.getInstanceId()).isNotNull();

        // When
        messagesStore.stop(Deadline.unlimited());

        // Then
        assertThat(messagesStore.isStarted()).isFalse();
    }

    @Test
    void shouldNotReturnSameSessionStoreForSameSessionIdWhenStoreIsStopped() {
        // Given
        messagesStore.start();

        // When
        FixMessagesStore.FixSessionMessagesStore store = messagesStore.getStore(sessionId);
        messagesStore.stop(Deadline.unlimited()).start();

        // Then
        assertThat(store).isNotSameAs(messagesStore.getStore(sessionId));
    }

    @Test
    void shouldCreateSessionStore() {
        // Given
        messagesStore.start();

        // When
        FixMessagesStore.FixSessionMessagesStore sessionStore = messagesStore.getStore(sessionId);

        // Then
        assertThat(sessionStore).isNotNull();
    }

    @Test
    void shouldReturnSameSessionStoreForSameSessionId() {
        // Given
        messagesStore.start();

        // When
        FixMessagesStore.FixSessionMessagesStore store1 = messagesStore.getStore(sessionId);
        FixMessagesStore.FixSessionMessagesStore store2 = messagesStore.getStore(sessionId);

        // Then
        assertThat(store1).isSameAs(store2);
    }

    @Test
    void shouldCreateDifferentSessionStoresForDifferentSessionIds() {
        // Given
        messagesStore.start();

        FixSessionId sessionId2 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER2", "TARGET2");

        // When
        FixMessagesStore.FixSessionMessagesStore store1 = messagesStore.getStore(sessionId);
        FixMessagesStore.FixSessionMessagesStore store2 = messagesStore.getStore(sessionId2);

        // Then
        assertThat(store1).isNotSameAs(store2);
    }
}
