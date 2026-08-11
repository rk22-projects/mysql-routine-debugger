package be.rk22.dbgplugin;

public class RoutineInfo {
    public final String name;
    public final String type;   // "PROCEDURE" or "FUNCTION"

    public RoutineInfo(String name, String type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String toString() {
        return ("PROCEDURE".equals(type) ? "P  " : "F  ") + name;
    }
}
