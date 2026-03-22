package group.gnometrading.oms.order;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.TimeInForce;

public record OmsOrder(
        int exchangeId,
        long securityId,
        String clientOid,
        Side side,
        long price,
        long size,
        OrderType orderType,
        TimeInForce timeInForce
) {}
