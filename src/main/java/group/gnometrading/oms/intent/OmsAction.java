package group.gnometrading.oms.intent;

import group.gnometrading.oms.order.OmsCancelOrder;
import group.gnometrading.oms.order.OmsOrder;
import group.gnometrading.oms.order.OmsReplaceOrder;

public final class OmsAction {

    public enum Type {
        NEW_ORDER,
        CANCEL,
        REPLACE
    }

    private Type type;
    private final OmsOrder order = new OmsOrder();
    private final OmsCancelOrder cancel = new OmsCancelOrder();
    private final OmsReplaceOrder replace = new OmsReplaceOrder();

    public OmsAction() {}

    public Type type() {
        return type;
    }

    public OmsOrder order() {
        return order;
    }

    public OmsCancelOrder cancel() {
        return cancel;
    }

    public OmsReplaceOrder replace() {
        return replace;
    }

    public OmsOrder asNewOrder() {
        this.type = Type.NEW_ORDER;
        return order;
    }

    public OmsCancelOrder asCancel() {
        this.type = Type.CANCEL;
        return cancel;
    }

    public OmsReplaceOrder asReplace() {
        this.type = Type.REPLACE;
        return replace;
    }
}
