package group.gnometrading.oms.order;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;

public record OmsExecutionReport(
        String clientOid,
        ExecType execType,
        OrderStatus orderStatus,
        long filledQty,
        long fillPrice,
        long cumulativeQty,
        long leavesQty,
        double fee,
        int exchangeId,
        long securityId,
        long timestampEvent,
        long timestampRecv
) {}
