package com.example.fixclient;

import com.example.fixclient.fix.fields.Side;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The round trip, run once per profile. Each class has its own broker and its context is closed after it, so two
 * cached contexts never log on to the same broker at once.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractFixClientApplicationTest {

    private static int brokerPort;
    private static BrokerSimulator broker;

    @Autowired
    private OrderEntryApplication orderEntryApplication;

    @MockitoSpyBean
    private ExecutionReportHandler executionReportHandler;

    @BeforeAll
    static void startTheBroker() throws IOException {
        brokerPort = freePort();
        broker = new BrokerSimulator(new InetSocketAddress("localhost", brokerPort));
    }

    @AfterAll
    static void stopTheBroker() {
        broker.stop();
    }

    @DynamicPropertySource
    static void connectToTheBroker(DynamicPropertyRegistry registry) {
        registry.add("staffix.initiators.order-entry.main-target.connect-addresses[0]", () -> "localhost:" + brokerPort);
    }

    @Test
    void newOrderSingleIsAcknowledged() {
        List<String> executionReports = new CopyOnWriteArrayList<>();
        // the decoder is reused for the next message, so its values are copied while the call lasts
        doAnswer(invocation -> {
            OrderEntryApplication.ExecutionReportDecoder executionReport = invocation.getArgument(0);
            executionReports.add(executionReport.getClOrdId() + ":" + executionReport.getOrdStatus());
            return invocation.callRealMethod();
        }).when(executionReportHandler).accept(any());
        await().atMost(Duration.ofSeconds(10)).until(orderEntryApplication::isLoggedOn);

        orderEntryApplication.sendNewOrderSingle("order-1", "EUR/USD", Side.SideValues.BUY, 1_000_000d, 1.0825d);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionReports).containsExactly("order-1:0"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
