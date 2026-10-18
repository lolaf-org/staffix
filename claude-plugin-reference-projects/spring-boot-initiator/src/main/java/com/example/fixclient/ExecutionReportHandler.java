package com.example.fixclient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Called on the session's thread with a decoder that is reused for the next message: copy what must outlive the call.
 */
@Slf4j
@Component
public class ExecutionReportHandler implements Consumer<OrderEntryApplication.ExecutionReportDecoder> {

    @Override
    public void accept(OrderEntryApplication.ExecutionReportDecoder executionReport) {
        log.info("ExecutionReport {} for {}: ExecType {} OrdStatus {}", executionReport.getOrderId(),
                executionReport.getClOrdId(), executionReport.getExecType(), executionReport.getOrdStatus());
    }
}
