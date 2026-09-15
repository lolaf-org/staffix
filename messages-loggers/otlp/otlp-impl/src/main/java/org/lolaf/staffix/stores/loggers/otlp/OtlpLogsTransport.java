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
package org.lolaf.staffix.stores.loggers.otlp;

import java.io.IOException;

/**
 * How a batch of marshalled log records leaves this process.
 *
 * <p>Exists so that the logger itself does not branch on the transport at every send: the choice is made
 * once, when the logger is built, and everything after it publishes through one of these. The two
 * implementations differ only in the envelope - the protobuf they carry is byte-for-byte the same, since
 * {@code LogsData} and {@code ExportLogsServiceRequest} are wire-identical.
 */
interface OtlpLogsTransport extends AutoCloseable {

    /**
     * Publishes one batch.
     *
     * @param data   buffer holding the marshalled {@code LogsData}
     * @param length how many bytes of it to send
     * @throws IOException if the collector refused the batch or could not be reached
     */
    void send(byte[] data, int length) throws IOException;

    /**
     * @return where this publishes, for a log line or a health report
     */
    String description();

    @Override
    void close();
}
