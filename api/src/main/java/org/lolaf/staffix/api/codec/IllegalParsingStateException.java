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
package org.lolaf.staffix.api.codec;

/**
 * The bytes on the wire are not a FIX message at all - no BeginString where one must be, or a field running past
 * the length any field may have.
 *
 * <p>Distinct from {@link NotEnoughDataException}, which means the same bytes are merely incomplete. Here waiting
 * for more would not help, so the default disconnect stands.
 */
public class IllegalParsingStateException extends DecodingException {

    public IllegalParsingStateException(String msg) {
        super(msg);
    }

    public IllegalParsingStateException(String msg, Exception cause) {
        super(msg, cause);
    }
}