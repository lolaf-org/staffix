package com.example.gateway;

import com.example.gateway.environment.StaffixEnvironment;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;

import java.net.InetSocketAddress;
import java.time.Duration;

public final class OrderGateway {

    private final FixEngine engine;
    private final FixAcceptor acceptor;

    public OrderGateway(StaffixEnvironment environment, InetSocketAddress bindAddress) {
        FixEngineBuilder.FixEngineBuilderBuilder engineBuilder = FixEngineBuilder.builder()
                .instanceId("order-gateway")
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application("order-handling", new OrderHandlingApplication())
                        .build());
        environment.addComponents(engineBuilder);
        engine = engineBuilder.build().instance().start();
        acceptor = engine.newAcceptor(FixAcceptorBuilder.builder()
                .bindAddress(bindAddress)
                .build()).start();
    }

    public void stop() {
        acceptor.stop();
        engine.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    public static void main(String[] args) {
        InetSocketAddress bindAddress = new InetSocketAddress(
                System.getenv().getOrDefault("FIX_BIND_HOST", "0.0.0.0"),
                Integer.parseInt(System.getenv().getOrDefault("FIX_PORT", "9876")));
        OrderGateway gateway = new OrderGateway(StaffixEnvironment.fromEnvironmentVariable(), bindAddress);
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "order-gateway-shutdown"));
    }
}
