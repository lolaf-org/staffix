package com.example.fixclient;

import com.example.fixclient.environment.StaffixEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;

import java.net.InetSocketAddress;
import java.time.Duration;

@Slf4j
public final class FixClient {

    private final FixEngine engine;
    private final FixInitiator initiator;

    public FixClient(StaffixEnvironment environment, OrderEntryApplication application,
                     InetSocketAddress brokerAddress) {
        FixEngineBuilder.FixEngineBuilderBuilder engineBuilder = FixEngineBuilder.builder()
                .instanceId("fix-client")
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application("order-entry", application)
                        .build());
        environment.addComponents(engineBuilder);
        engine = engineBuilder.build().instance().start();
        initiator = engine.newInitiator(FixInitiatorBuilder.builder()
                .connectionRetry(Duration.ofSeconds(5))
                .mainTarget(FixInitiatorTarget.builder()
                        .fixSessionId(orderEntrySessionId())
                        .connectAddress(brokerAddress)
                        .build())
                .build()).start();
    }

    public void stop() {
        initiator.stop();
        engine.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    private FixSessionId orderEntrySessionId() {
        return engine.getFixSessionsSettingsStores().stream()
                .flatMap(store -> store.getSettings().stream())
                .filter(settings -> settings.getFixSessionType() == FixSession.FixSessionType.INITIATOR)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no initiator session in the settings store"))
                .getFixSessionId();
    }

    public static void main(String[] args) {
        InetSocketAddress brokerAddress = new InetSocketAddress(
                System.getenv().getOrDefault("FIX_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("FIX_PORT", "9876")));
        OrderEntryApplication application = new OrderEntryApplication(executionReport ->
                log.info("ExecutionReport {} for {}: ExecType {} OrdStatus {}", executionReport.getOrderId(),
                        executionReport.getClOrdId(), executionReport.getExecType(), executionReport.getOrdStatus()));
        FixClient client = new FixClient(StaffixEnvironment.fromEnvironmentVariable(), application, brokerAddress);
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop, "fix-client-shutdown"));
    }
}
