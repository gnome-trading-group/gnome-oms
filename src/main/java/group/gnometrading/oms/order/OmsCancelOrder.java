package group.gnometrading.oms.order;

public record OmsCancelOrder(
        int exchangeId,
        long securityId,
        String clientOid
) {}
