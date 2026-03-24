package group.gnometrading.oms.intent;

@FunctionalInterface
public interface ClientOidGenerator {
    long next();
}
