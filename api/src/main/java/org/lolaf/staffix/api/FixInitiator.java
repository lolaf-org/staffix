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

import org.lolaf.staffix.api.session.FixSession;

/**
 * The outbound half of a FIX connection: one session, dialled out to a configured address and re-dialled on its
 * own after a drop.
 *
 * <p>One initiator is one session, which is why {@link #getSession()} takes no argument - a process talking to
 * several counterparties builds an initiator for each. {@link FixAcceptor} is the other side, and holds many.
 */
public interface FixInitiator extends Startable<FixInitiator> {

    FixInitiator stop();

    boolean isConnected();

    FixSession getSession();

}