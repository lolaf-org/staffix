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

import lombok.Getter;
import org.lolaf.staffix.api.fields.FixField;

/**
 * A required field is absent from an entry of a repeating group.
 *
 * <p>Separate from {@link RequiredFieldNotFoundException} because the reject has to name the group entry, not
 * just the tag - the same tag may be present elsewhere in the message and the peer needs to know which one.
 */
@Getter
public class RequiredGroupFieldNotFoundException extends ValidationException {

    public RequiredGroupFieldNotFoundException(FixField field) {
        super(field, "Group field not found in message", SessionRejectReasonCodes.REQUIRED_TAG_MISSING, BusinessRejectReasonCodes.CONDITIONALLY_REQUIRED_FIELD_MISSING);
    }

    public RequiredGroupFieldNotFoundException(FixField field, Exception cause) {
        super(field, "Group field not found in message", cause, SessionRejectReasonCodes.REQUIRED_TAG_MISSING, BusinessRejectReasonCodes.CONDITIONALLY_REQUIRED_FIELD_MISSING);
    }

}