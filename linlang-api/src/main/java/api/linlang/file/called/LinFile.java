package api.linlang.file.called;

import api.linlang.file.service.ConfigService;
import api.linlang.database.services.DataService;
import api.linlang.file.service.LangService;
import api.linlang.file.service.Services;

public final class LinFile {
    private static volatile Services S = new Noop();

    public static void install(Services s) {
        if (s == null) throw new IllegalArgumentException("services null");
        S = s;
    }
    public static Services services() { return S; }

    private static final class Noop implements Services {
        private static final RuntimeException E = new IllegalStateException(
                "Linlang services not installed. Call linlang-adapter bootstrap first.");
        public ConfigService config() { throw E; }
        public LangService   lang()   { throw E; }
        public DataService data()   { throw E; }
    }
    private LinFile() {}
}