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
package org.lolaf.staffix.spring.boot.mapper;

import org.junit.jupiter.api.Test;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.staffix.spring.boot.props.AcceptorProps;
import org.lolaf.staffix.spring.boot.props.InitiatorProps;
import org.lolaf.staffix.spring.boot.props.InitiatorTargetProps;
import org.lolaf.staffix.spring.boot.props.IoSettingsProps;
import org.lolaf.staffix.spring.boot.spi.FixSessionIdProps;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IoSettingsPropsMapperTest {

    private static IoSettingsProps allSet() {
        IoSettingsProps props = new IoSettingsProps();
        props.setReadBufferSize(16 * 1024);
        props.setReadDirectBuffer(false);
        props.setTasksRingBufferSize(128);
        props.setMultiThreadedWriteApiCalls(false);
        props.setMaxBytesCountPerWriteCycle(64 * 1024);
        props.setWriteHighWatermark(1024 * 1024);
        props.setWriteLowWatermark(1024);
        props.setTrackReceiveTime(true);
        return props;
    }

    private static void assertAllSet(IOSettings settings) {
        assertThat(settings.getReadBufferSize()).isEqualTo(16 * 1024);
        assertThat(settings.isReadDirectBuffer()).isFalse();
        assertThat(settings.getTasksRingBufferSize()).isEqualTo(128);
        assertThat(settings.isMultiThreadedWriteAPICalls()).isFalse();
        assertThat(settings.getMaxBytesCountPerWriteCycle()).isEqualTo(64 * 1024);
        assertThat(settings.getWriteHighWatermark()).isEqualTo(1024 * 1024);
        assertThat(settings.getWriteLowWatermark()).isEqualTo(1024);
        assertThat(settings.isTrackReceiveTime()).isTrue();
    }

    @Test
    void everyPropertyIsMapped() {
        assertAllSet(IoSettingsPropsMapper.toSettings(allSet()));
    }

    @Test
    void unsetPropertiesKeepTheDefaults() {
        IoSettingsProps props = new IoSettingsProps();
        props.setReadBufferSize(16 * 1024);

        IOSettings settings = IoSettingsPropsMapper.toSettings(props);

        assertThat(settings).usingRecursiveComparison()
                .isEqualTo(IOSettings.builder().readBufferSize(16 * 1024).build());
    }

    @Test
    void aMissingSectionKeepsTheDefaults() {
        assertThat(IoSettingsPropsMapper.toSettings(null)).usingRecursiveComparison()
                .isEqualTo(IOSettings.builder().build());
    }

    @Test
    void theAcceptorGetsItsIoSettings() {
        AcceptorProps props = new AcceptorProps();
        props.setBindAddress("localhost:0");
        props.setIoSettings(allSet());

        assertAllSet(AcceptorPropsMapper.build("acceptor", props, null, null).getIoSettings());
    }

    @Test
    void theInitiatorGetsItsIoSettings() {
        FixSessionIdProps sessionId = new FixSessionIdProps();
        sessionId.setId("test");
        sessionId.setFixVersion("VERSION_44");
        sessionId.setSenderCompId("INITIATOR");
        sessionId.setTargetCompId("ACCEPTOR");
        InitiatorTargetProps target = new InitiatorTargetProps();
        target.setFixSessionId(sessionId);
        target.setConnectAddresses(List.of("localhost:17001"));
        InitiatorProps props = new InitiatorProps();
        props.setMainTarget(target);
        props.setIoSettings(allSet());

        assertAllSet(InitiatorPropsMapper.build("initiator", props, null, null).getIoSettings());
    }
}
