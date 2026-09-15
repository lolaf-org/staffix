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

/**
 * A message the engine has parsed but not bound to a typed decoder - the fields as they arrived.
 *
 * <p>Valid only for the callback it is handed to: the engine reuses it for the next message. {@link #copy()} is
 * what makes it safe to keep, and is the allocation the message path exists to avoid, so copy deliberately
 * rather than by habit.
 */
public interface DecodedFixMessage extends FixFieldMap {

    MessageType getMessageType();

    DecodedFixMessage copy();

}