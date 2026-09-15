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
package org.lolaf.staffix.impl;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Wraps an application so an exception it throws cannot take the session down with it.
 *
 * <p>Application callbacks run on the session thread, so an unhandled exception there would otherwise reach the
 * session layer and end a connection over a bug in one message's handling.
 */
@Value
@Slf4j
public class FailSafeFixApplication implements FixApplication {

    FixApplication fixApplication;

    @Override
    public Collection<FixApplicationSessionSettingDescriptor> getRequiredFixSessionSettings() {
        return fixApplication.getRequiredFixSessionSettings();
    }

    @Override
    public FixApiVersion getFixApiVersion() {
        return fixApplication.getFixApiVersion();
    }

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
        return fixApplication.setup(fixSessionSettings, fixSession, encodedMessagesTypes);
    }

    @Override
    public void onCancelOnDisconnectTriggered(FixSession fixSession, CancelOnDisconnectType cancelOnDisconnectType) {
        try {
            fixApplication.onCancelOnDisconnectTriggered(fixSession, cancelOnDisconnectType);
        } catch (Exception ex) {
            log.error("Failed to call onCancelOnDisconnectTriggered on FIX session {}", fixSession);
        }
    }

    @Override
    public void onSessionCreated(FixSession fixSession, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry, List<FixMessageDecoder> decoders) {
        try {
            fixApplication.onSessionCreated(fixSession, fieldsRegistry, messageTypeRegistry, decoders);
        } catch (Exception ex) {
            log.error("Failed to call onSessionCreated on FIX session {}", fixSession);
        }
    }

    @Override
    public void onSessionPreDestroy(FixSession fixSession) {
        try {
            fixApplication.onSessionPreDestroy(fixSession);
        } catch (Exception ex) {
            log.error("Failed to call onSessionPreDestroy on FIX session {}", fixSession);
        }
    }

    @Override
    public void onSessionDestroyed(FixSession fixSession) {
        try {
            fixApplication.onSessionDestroyed(fixSession);
        } catch (Exception ex) {
            log.error("Failed to call onSessionDestroyed on FIX session {}", fixSession);
        }
    }

    @Override
    public void onInsideSessionTime(FixSession fixSession) {
        try {
            fixApplication.onInsideSessionTime(fixSession);
        } catch (Exception ex) {
            log.error("Failed to call onInsideSessionTime on FIX session {}", fixSession);
        }
    }

    @Override
    public void onOutsideSessionTime(FixSession fixSession) {
        try {
            fixApplication.onOutsideSessionTime(fixSession);
        } catch (Exception ex) {
            log.error("Failed to call onOutsideSessionTime on FIX session {}", fixSession);
        }
    }

    @Override
    public void onPreOutsideSessionTime(FixSession fixSession, Duration outsideSessionTimeDelay) {
        try {
            fixApplication.onPreOutsideSessionTime(fixSession, outsideSessionTimeDelay);
        } catch (Exception ex) {
            log.error("Failed to call onPreOutsideSessionTime on FIX session {}", fixSession);
        }
    }

    @Override
    public boolean onNoDecoderSetupForMessage(FixSession fixSession, MessageType messageType) {
        try {
            return fixApplication.onNoDecoderSetupForMessage(fixSession, messageType);
        } catch (Exception ex) {
            log.error("Failed to call onNoDecoderSetupForMessage on FIX session {}", fixSession);
        }
        return false;
    }

    @Override
    public void onMessageSendingFailure(FixSession fixSession, MessageType messageType, ByteBuffer message, Exception sendingError) {
        try {
            fixApplication.onMessageSendingFailure(fixSession, messageType, message, sendingError);
        } catch (Exception ex) {
            log.error("Failed to call onMessageSendingFailure on FIX session {}", fixSession);
        }
    }

    @Override
    public boolean onResendRequest(FixSession fixSession, MessageType messageType, DecodedFixMessage toResend) {
        try {
            return fixApplication.onResendRequest(fixSession, messageType, toResend);
        } catch (Exception ex) {
            log.error("Failed to call onResendRequest on FIX session {}", fixSession);
            return false;
        }
    }

    @Override
    public void onSequenceReset(FixSession fixSession, long newSeqNum, boolean gapFill) {
        try {
            fixApplication.onSequenceReset(fixSession, newSeqNum, gapFill);
        } catch (Exception ex) {
            log.error("Failed to call onSequenceReset on FIX session {}", fixSession);
        }
    }

    @Override
    public void onResendRequestInitiated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
        try {
            fixApplication.onResendRequestInitiated(fixSession, fromSeqNum, toSeqNum);
        } catch (Exception ex) {
            log.error("Failed to call onResendRequestInitiated on FIX session {}", fixSession);
        }
    }

    @Override
    public void onResendRequestTerminated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
        try {
            fixApplication.onResendRequestTerminated(fixSession, fromSeqNum, toSeqNum);
        } catch (Exception ex) {
            log.error("Failed to call onResendRequestTerminated on FIX session {}", fixSession);
        }
    }

    @Override
    public void onAdminMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender, Supplier<FixFieldsEncoder> bodyAppender, Supplier<FixFieldsEncoder> trailerAppender) {
        try {
            fixApplication.onAdminMessageEncoding(fixSession, messageType, headersAppender, bodyAppender, trailerAppender);
        } catch (Exception ex) {
            log.error("Failed to call onAdminMessageEncoding on FIX session {}", fixSession);
        }
    }

    @Override
    public void onMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender, Supplier<FixFieldsEncoder> trailerAppender) {
        try {
            fixApplication.onMessageEncoding(fixSession, messageType, headersAppender, trailerAppender);
        } catch (Exception ex) {
            log.error("Failed to call onMessageEncoding on FIX session {}", fixSession);
        }
    }

    @Override
    public CompletableFuture<Optional<String>> validateLogon(FixSession fixSession, DecodedFixMessage logonMessage, Executor executor) {
        try {
            return fixApplication.validateLogon(fixSession, logonMessage, executor);
        } catch (Exception ex) {
            log.error("Failed to call validateLogon on FIX session {}", fixSession);
        }
        return CompletableFuture.completedFuture(Optional.of("Failure to validate logon message"));
    }

    @Override
    public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
        try {
            fixApplication.onLogon(fixSession, logonMessage);
        } catch (Exception ex) {
            log.error("Failed to call onLogon on FIX session {}", fixSession);
        }
    }

    @Override
    public void onLogoutInitiated(FixSession fixSession, String message) {
        try {
            fixApplication.onLogoutInitiated(fixSession, message);
        } catch (Exception ex) {
            log.error("Failed to call onLogoutInitiated on FIX session {}", fixSession);
        }
    }

    @Override
    public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
        try {
            fixApplication.onLogout(fixSession, message, logoutMessage);
        } catch (Exception ex) {
            log.error("Failed to call onLogout on FIX session {}", fixSession);
        }
    }

    @Override
    public void onDisconnected(FixSession fixSession) {
        try {
            fixApplication.onDisconnected(fixSession);
        } catch (Exception ex) {
            log.error("Failed to call onDisconnected on FIX session {}", fixSession);
        }
    }

    @Override
    public void onHeartbeat(FixSession fixSession, UTCTime remoteTimestamp) {
        try {
            fixApplication.onHeartbeat(fixSession, remoteTimestamp);
        } catch (Exception ex) {
            log.error("Failed to call onHeartbeat on FIX session {}", fixSession);
        }
    }

    @Override
    public void onTestRequest(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
        try {
            fixApplication.onTestRequest(fixSession, testReqID, remoteTimestamp);
        } catch (Exception ex) {
            log.error("Failed to call onTestRequest on FIX session {}", fixSession);
        }
    }

    @Override
    public void onTestRequestResponse(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
        try {
            fixApplication.onTestRequestResponse(fixSession, testReqID, remoteTimestamp);
        } catch (Exception ex) {
            log.error("Failed to call onTestRequestResponse on FIX session {}", fixSession);
        }
    }

    @Override
    public void onMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum, int refTagId, String refMsgType) {
        try {
            fixApplication.onMessageReject(fixSession, rejectText, rejectReason, refSeqNum, refTagId, refMsgType);
        } catch (Exception ex) {
            log.error("Failed to call onMessageReject on FIX session {}", fixSession);
        }
    }

    @Override
    public void onBusinessMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum, String businessRejectRefId, String refMsgType) {
        try {
            fixApplication.onBusinessMessageReject(fixSession, rejectText, rejectReason, refSeqNum, businessRejectRefId, refMsgType);
        } catch (Exception ex) {
            log.error("Failed to call onBusinessMessageReject on FIX session {}", fixSession);
        }
    }

    @Override
    public void onNetworkWatermarkEvent(FixSession fixSession, boolean highWatermarkReached, long bytesLeftToWrite) {
        try {
            fixApplication.onNetworkWatermarkEvent(fixSession, highWatermarkReached, bytesLeftToWrite);
        } catch (Exception ex) {
            log.error("Failed to call onNetworkWatermarkEvent on FIX session {}", fixSession);
        }
    }
}
