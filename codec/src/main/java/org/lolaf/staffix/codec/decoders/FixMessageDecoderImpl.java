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
package org.lolaf.staffix.codec.decoders;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableList;
import org.lolaf.staffix.codec.decoders.mappers.FixFieldsDecoderMapperImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The base every generated decoder extends: binds a message's fields to the decoder's own variables once, then
 * lets the parser write them directly as it reads.
 *
 * <p>That binding is why an application overrides almost nothing - by the time
 * {@code onDecoded} runs, the fields already hold this message's values.
 */
@Slf4j
public final class FixMessageDecoderImpl implements FixMessageDecoder {

    private final MessageFieldsRegistry.FixMessageFields fixMessageFields;
    private final boolean validateRequiredFields;
    private final boolean rejectUndefinedTagsForMessage;
    private final boolean validateDuplicateTags;
    private final boolean trackProcessedFields;
    private final FixFieldsDecoderMapperImpl fixFieldsDecoderMapper;
    private final FixMessageDecoder fixMessageDecoder;
    private final Collection<FixField> receivedBodyFields;
    private final Collection<FixField> receivedGroupFields;
    private final Collection<ValidationException> validationsExceptions;
    private Collection<FixField> receivedFields;
    private Collection<FixField> currentGroupFields;
    private FixField currentGroupField;

    public FixMessageDecoderImpl(FieldsRegistry fieldsRegistry, FixMessageDecoder fixMessageDecoder, FixDictionaryId validationFixDictionaryId,
                                 FixSessionSettings.ValidationSettings validationSettings, FixSessionId fixSessionId) {
        this(fieldsRegistry, fixMessageDecoder, MessageFieldsRegistry.Registry.getInstance(validationFixDictionaryId), validationSettings, fixSessionId);
    }

    public FixMessageDecoderImpl(FieldsRegistry fieldsRegistry, FixMessageDecoder fixMessageDecoder, MessageFieldsRegistry messageFieldsRegistry,
                                 FixSessionSettings.ValidationSettings validationSettings, FixSessionId fixSessionId) {
        this.fixMessageDecoder = fixMessageDecoder;
        this.fixMessageFields = messageFieldsRegistry.getFixMessageFields(fixMessageDecoder.getMessageType());
        if (fixMessageFields == null) {
            throw new IllegalStateException("Unable to find any FIX fields in registry for message type "
                    + fixMessageDecoder.getMessageType() + " and dictionary for " + messageFieldsRegistry.getTargetDictionary());
        }
        this.fixFieldsDecoderMapper = new FixFieldsDecoderMapperImpl(fixMessageFields, fixSessionId, this::onUnknownField);
        this.validateRequiredFields = validationSettings.isValidateRequiredFields();
        this.rejectUndefinedTagsForMessage = !validationSettings.isAllowUndefinedTagsForMessage();
        this.validateDuplicateTags = validationSettings.isValidateDuplicateTags();
        this.trackProcessedFields = validateRequiredFields || rejectUndefinedTagsForMessage || validateDuplicateTags;
        if (trackProcessedFields) {
            this.receivedBodyFields = new IndexableList<>(FixField.class, 16);
            this.receivedGroupFields = new IndexableList<>(FixField.class, 16);
        } else {
            this.receivedBodyFields = null;
            this.receivedGroupFields = null;
        }
        this.receivedFields = receivedBodyFields;
        this.validationsExceptions = new ArrayList<>();
        fixMessageDecoder.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    public void onPluginsSetup(FixSessionPlugin<?, ?>[] fixSessionPlugins) {
        for (FixSessionPlugin<?, ?> plugin : fixSessionPlugins) {
            plugin.onDecoderSetup(this, fixFieldsDecoderMapper);
        }
    }

    @Override
    public MessageType getMessageType() {
        return fixMessageDecoder.getMessageType();
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        throw new IllegalStateException("Should not have been called");
    }

    @Override
    public void validate() throws ValidationException {
        if (trackProcessedFields) {
            if (validateRequiredFields) {
                Collection<FixField> missingBodyFields = fixMessageFields.getMissingRequiredFields(receivedBodyFields);
                if (!missingBodyFields.isEmpty()) {
                    missingBodyFields.forEach(this::addMissingFieldException);
                }
            }
            if (!validationsExceptions.isEmpty()) {
                List<ValidationException> validationsExceptionsCopy = new ArrayList<>(validationsExceptions);
                validationsExceptions.clear();
                receivedBodyFields.clear();
                throw new ValidationException(validationsExceptionsCopy);
            }
            receivedBodyFields.clear();
        }
        fixMessageDecoder.validate();
    }

    @Override
    public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        fixFieldsDecoderMapper.onField(fixField, deserializationContext);
        fixMessageDecoder.onField(fixField, deserializationContext);
        if (trackProcessedFields) {
            if (validateDuplicateTags && receivedFields.contains(fixField)) {
                validationsExceptions.add(new ValidationException(fixField, "Tag appears more than once",
                        SessionRejectReasonCodes.TAG_APPEARS_MORE_THAN_ONCE, null));
            }
            receivedFields.add(fixField);
        }
        if (rejectUndefinedTagsForMessage && !fixField.equals(currentGroupField)) {
            validateFieldDeclaredInCurrentScope(fixField);
        }
    }

    private void validateFieldDeclaredInCurrentScope(FixField fixField) {
        if (currentGroupFields == null) {
            if (fixField.getLocation() == FieldLocation.BODY && !fixMessageFields.getFields().contains(fixField)) {
                validationsExceptions.add(new ValidationException(fixField, "Received tag not defined for message",
                        SessionRejectReasonCodes.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, null));
            }
        } else if (!currentGroupFields.contains(fixField)) {
            validationsExceptions.add(new ValidationException(fixField, "Received group tag not defined for message",
                    SessionRejectReasonCodes.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, null));
        }
    }

    @Override
    public void onUnknownField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        fixMessageDecoder.onUnknownField(fixField, deserializationContext);
    }

    @Override
    public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        fixMessageDecoder.onDecoded(fixSession, possDupFlag, possResend);
        fixFieldsDecoderMapper.onDecoded();
    }

    @Override
    public boolean ignoresIncomingSequenceNumber() {
        return fixMessageDecoder.ignoresIncomingSequenceNumber();
    }

    @Override
    public boolean managesIncomingSequenceNumber() {
        return fixMessageDecoder.managesIncomingSequenceNumber();
    }

    @Override
    public void onDecodingFailed(FixSession fixSession, DecodingException decodingException) {
        fixMessageDecoder.onDecodingFailed(fixSession, decodingException);
        fixFieldsDecoderMapper.onDecodingFailed();
    }

    @Override
    public void onGroupStart(FixField parentGroup, FixField groupField, int numInGroup) {
        fixMessageDecoder.onGroupStart(parentGroup, groupField, numInGroup);
        fixFieldsDecoderMapper.onGroupStart(groupField);
        if (trackProcessedFields) {
            receivedBodyFields.add(groupField);
            receivedFields = receivedGroupFields;
            if (rejectUndefinedTagsForMessage) {
                validateFieldDeclaredInCurrentScope(groupField);
                currentGroupField = groupField;
                currentGroupFields = fixMessageFields.getGroupFields(groupField);
            }
        }
    }

    @Override
    public void onGroupEnd(FixField parentGroup, FixField groupField) {
        fixMessageDecoder.onGroupEnd(parentGroup, groupField);
        fixFieldsDecoderMapper.onGroupEnd(parentGroup);
        if (trackProcessedFields) {
            receivedFields.clear();
            receivedFields = receivedBodyFields;
            if (rejectUndefinedTagsForMessage) {
                // back to the scope around this group, which for a nested one is the group that declared it
                currentGroupField = parentGroup;
                currentGroupFields = parentGroup == null ? null : fixMessageFields.getGroupFields(parentGroup);
            }
        }
    }

    @Override
    public void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
        fixMessageDecoder.onGroupEntryEnd(parentGroup, groupField, fixField);
        fixFieldsDecoderMapper.onGroupEntryEnd();
        if (validateRequiredFields) {
            Collection<FixField> missingGroupsFields = fixMessageFields.getGroupMissingRequiredFields(groupField, receivedGroupFields);
            if (!missingGroupsFields.isEmpty()) {
                missingGroupsFields.forEach(this::addMissingGroupFieldException);
            }
        }
        if (validateRequiredFields || validateDuplicateTags) {
            // reset per-entry tracking so repeated tags across entries are not flagged as duplicates
            receivedGroupFields.clear();
        }
    }

    private void addMissingFieldException(FixField field) {
        validationsExceptions.add(new RequiredFieldNotFoundException(field));
    }

    private void addMissingGroupFieldException(FixField field) {
        validationsExceptions.add(new RequiredGroupFieldNotFoundException(field));
    }

    @Override
    public void onGroupEntryStart(FixField parentGroup, FixField groupField, FixField fixField) {
        fixMessageDecoder.onGroupEntryStart(parentGroup, groupField, fixField);
    }

    @Override
    public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        fixMessageDecoder.onBegin(localReceiveTimeInNanos, localReceiveTime);
        fixFieldsDecoderMapper.onBegin();
        if (trackProcessedFields) {
            // the tracking is per message, and a message that fails to decode never reaches
            // validate(), which is where it is otherwise cleared: a garbled or rejected message would then have the
            // fields it did receive counted against the next message of the same type, which shows up as the standard
            // header tags being reported as appearing more than once
            receivedBodyFields.clear();
            receivedGroupFields.clear();
            validationsExceptions.clear();
            currentGroupFields = null;
            receivedFields = receivedBodyFields;
        }
    }
}