package com.example.fixclient;

import com.example.fixclient.fix.encoders.NewOrderSingleEncoder;
import com.example.fixclient.fix.fields.ClOrdID;
import com.example.fixclient.fix.fields.ExecType;
import com.example.fixclient.fix.fields.OrdStatus;
import com.example.fixclient.fix.fields.OrdType;
import com.example.fixclient.fix.fields.OrderID;
import com.example.fixclient.fix.fields.Side;
import com.example.fixclient.fix.msg.MessageTypes;
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
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class OrderEntryApplication implements FixApplication {

    private final Consumer<ExecutionReportDecoder> executionReportListener;
    private volatile FixSession session;
    private NewOrderSingleEncoder newOrderSingleEncoder;

    public OrderEntryApplication(Consumer<ExecutionReportDecoder> executionReportListener) {
        this.executionReportListener = executionReportListener;
    }

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
        encodedMessagesTypes.add(MessageTypes.NewOrderSingle);
        newOrderSingleEncoder = session.newEncoder(NewOrderSingleEncoder.class).asReusable();
        return List.of(new ExecutionReportDecoder(executionReportListener));
    }

    @Override
    public void onLogon(FixSession session, DecodedFixMessage logon) {
        log.info("{} logged on", session.getFixSessionId());
        this.session = session;
    }

    @Override
    public void onLogout(FixSession session, String message, DecodedFixMessage logout) {
        log.info("{} logged out: {}", session.getFixSessionId(), message);
        this.session = null;
    }

    public boolean isLoggedOn() {
        return session != null;
    }

    /**
     * Sends from the caller's thread; the encoder is reused, so calls must not be concurrent.
     */
    public void sendNewOrderSingle(String clOrdId, String symbol, Side.SideValues side, double quantity, double price) {
        FixSession loggedOnSession = session;
        if (loggedOnSession == null) {
            throw new IllegalStateException("not logged on");
        }
        loggedOnSession.send(newOrderSingleEncoder.begin()
                .setClOrdID(clOrdId)
                .setSymbol(symbol)
                .setSide(side)
                .setOrderQty(quantity)
                .setOrdType(OrdType.OrdTypeValues.LIMIT)
                .setPrice(price)
                .setTransactTime(UTCTime.of(Instant.now()), TimeUnit.MILLISECONDS), null);
    }

    @Setter
    public static final class ExecutionReportDecoder implements FixMessageDecoder {

        private final Consumer<ExecutionReportDecoder> listener;
        private String orderId;
        private String clOrdId;
        private char execType;
        private char ordStatus;

        ExecutionReportDecoder(Consumer<ExecutionReportDecoder> listener) {
            this.listener = listener;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getClOrdId() {
            return clOrdId;
        }

        public char getExecType() {
            return execType;
        }

        public char getOrdStatus() {
            return ordStatus;
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.ExecutionReport;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
            mapper.mapStringField(OrderID.get(), this::setOrderId, null)
                    .mapStringField(ClOrdID.get(), this::setClOrdId, null)
                    .mapCharField(ExecType.get(), this::setExecType, (char) 0)
                    .mapCharField(OrdStatus.get(), this::setOrdStatus, (char) 0);
        }

        @Override
        public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
            listener.accept(this);
        }
    }
}
