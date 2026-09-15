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

import lombok.experimental.UtilityClass;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Parses the {@code host:port} forms a configuration may use into socket addresses.
 */
@UtilityClass
public class AddressUtils {

    public static InetSocketAddress parse(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            throw new IllegalArgumentException("address is null or blank");
        }
        int sep = hostPort.lastIndexOf(':');
        if (sep <= 0 || sep == hostPort.length() - 1) {
            throw new IllegalArgumentException("Invalid address '" + hostPort + "', expected host:port");
        }
        String host = hostPort.substring(0, sep);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in address '" + hostPort + "'", e);
        }
        return new InetSocketAddress(host, port);
    }

    public static List<InetSocketAddress> parseAll(List<String> hostPorts) {
        return hostPorts.stream().map(AddressUtils::parse).toList();
    }
}
