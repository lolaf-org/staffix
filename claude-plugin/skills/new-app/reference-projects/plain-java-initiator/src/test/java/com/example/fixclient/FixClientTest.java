package com.example.fixclient;

import com.example.fixclient.environment.DevEnvironment;
import com.example.fixclient.environment.ProdEnvironment;
import com.example.fixclient.environment.StaffixEnvironment;
import com.example.fixclient.fix.fields.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FixClientTest {

    private final List<String> executionReports = new CopyOnWriteArrayList<>();
    private BrokerSimulator broker;
    private FixClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop();
        }
        if (broker != null) {
            broker.stop();
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
        broker = new BrokerSimulator(address);
        OrderEntryApplication application = new OrderEntryApplication(executionReport ->
                executionReports.add(executionReport.getClOrdId() + ":" + executionReport.getOrdStatus()));
        client = new FixClient(environment, application, address);

        await().atMost(Duration.ofSeconds(10)).until(application::isLoggedOn);
        application.sendNewOrderSingle("order-1", "EUR/USD", Side.SideValues.BUY, 1_000_000d, 1.0825d);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionReports).containsExactly("order-1:0"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
