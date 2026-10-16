package com.example.gateway;

import com.example.gateway.environment.DevEnvironment;
import com.example.gateway.environment.ProdEnvironment;
import com.example.gateway.environment.StaffixEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OrderGatewayTest {

    private OrderGateway gateway;
    private ClientSimulator client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop();
        }
        if (gateway != null) {
            gateway.stop();
        }
    }

    @Test
    void newOrderSingleIsAcknowledgedInDev() throws IOException {
        newOrderSingleIsAcknowledged(new DevEnvironment());
    }

    /**
     * The prod components (file store, async file logger) are only proven by running them.
     */
    @Test
    void newOrderSingleIsAcknowledgedInProd(@TempDir Path dataDirectory) throws IOException {
        newOrderSingleIsAcknowledged(new ProdEnvironment(dataDirectory));
    }

    private void newOrderSingleIsAcknowledged(StaffixEnvironment environment) throws IOException {
        InetSocketAddress address = new InetSocketAddress("localhost", freePort());
        gateway = new OrderGateway(environment, address);
        client = new ClientSimulator(address);

        await().atMost(Duration.ofSeconds(10)).until(client::isLoggedOn);
        client.sendNewOrderSingle("order-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(client.executionReports()).containsExactly("order-1:0"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
