package com.example.gateway;

import com.example.gateway.fix.encoders.ExecutionReportEncoder;
import com.example.gateway.fix.fields.ClOrdID;
import com.example.gateway.fix.fields.ExecType;
import com.example.gateway.fix.fields.OrdStatus;
import com.example.gateway.fix.fields.OrderQty;
import com.example.gateway.fix.fields.Side;
import com.example.gateway.fix.fields.Symbol;
import com.example.gateway.fix.msg.MessageTypes;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves every counterparty session: one instance, set up once per session, so per-session state lives in what
 * {@link #setup} creates.
 */
@Slf4j
public class OrderHandlingApplication implements FixApplication {

    private final AtomicLong orderIds = new AtomicLong();

    @Override
    public FixApiVersion getFixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_44);
    }

    @Override
    public FixDictionaryId getDictionaryId() {
        return FixDictionaryId.of(FixRegularVersion.VERSION_44);
    }

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                         Set<MessageType> encodedMessagesTypes) {
        encodedMessagesTypes.add(MessageTypes.ExecutionReport);
        return List.of(new NewOrderSingleDecoder(session, orderIds));
    }

    @Override
    public void onLogon(FixSession session, DecodedFixMessage logon) {
        log.info("{} logged on", session.getFixSessionId());
    }

    @Override
    public void onLogout(FixSession session, String message, DecodedFixMessage logout) {
        log.info("{} logged out: {}", session.getFixSessionId(), message);
    }

    /**
     * Acknowledges every order as new; this is where an order would be handed to the matching engine or router.
     */
    @Setter
    private static final class NewOrderSingleDecoder implements FixMessageDecoder {

        private final ExecutionReportEncoder executionReportEncoder;
        private final AtomicLong orderIds;
        private String clOrdId;
        private String symbol;
        private Side.SideValues side;
        private double orderQty;

        NewOrderSingleDecoder(FixSession session, AtomicLong orderIds) {
            this.executionReportEncoder = session.newEncoder(ExecutionReportEncoder.class).asReusable();
            this.orderIds = orderIds;
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
            mapper.mapStringField(ClOrdID.get(), this::setClOrdId, null)
                    .mapStringField(Symbol.get(), this::setSymbol, null)
                    .mapCharValuesEnumField(Side.get(), this::setSide, null)
                    .mapDoubleField(OrderQty.get(), this::setOrderQty, 0d);
        }

        @Override
        public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
            long orderId = orderIds.incrementAndGet();
            session.send(executionReportEncoder.begin()
                    .setOrderID("order-" + orderId)
                    .setExecID("exec-" + orderId)
                    .setClOrdID(clOrdId)
                    .setExecType(ExecType.ExecTypeValues.NEW)
                    .setOrdStatus(OrdStatus.OrdStatusValues.NEW)
                    .setSymbol(symbol)
                    .setSide(side)
                    .setLeavesQty(orderQty)
                    .setCumQty(0d)
                    .setAvgPx(0d), null);
        }
    }
}
