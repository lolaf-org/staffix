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
package org.lolaf.staffix.admin.http.dto;

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * Body of {@code POST .../messages}: messages sent in order, the separator and possDup applying to each.
 */
@Value
@Builder
@Jacksonized
public class SendMessageRequest {
    @NonNull
    @Singular
    List<String> messages;
    /**
     * The field separator used in {@link #getMessages()}, such as SOH or {@code |}.
     */
    char separator;
    boolean possDup;
}
