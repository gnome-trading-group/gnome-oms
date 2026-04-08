package group.gnometrading.oms.risk;

import group.gnometrading.strings.GnomeString;

public interface Configurable {
    void reconfigure(GnomeString parametersJson);
}
