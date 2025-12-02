package audit.linlang.audit.message;

import api.linlang.file.file.LangService;
import api.linlang.file.file.implement.LocaleProvider;
import core.linlang.audit.message.LinMsg;
import core.linlang.audit.message.LinlangInternalMessageKeys;

// core.linlang.i18n
public final class InternalMessageBinder {
    public static void bind(
            LangService lang,
            String locale,
            java.util.List<? extends LocaleProvider<LinlangInternalMessageKeys>> providers
    ){
        var pack = lang.bind(LinlangInternalMessageKeys.class, locale, providers);

        LinMsg.installKeys(() -> pack);
        LinMsg.install((key, args) -> {
            String raw = InternalPath.get(pack, key);
            if (raw == null) return key;
            for (var e : args.entrySet()) {
                if (e == null) continue;
                String k = String.valueOf(e.getKey());
                String v = String.valueOf(e.getValue());
                if (k == null) continue;

                try {
                    raw = raw.replace("{" + k + "}", v);
                    raw = raw.replace(k, v);
                    if (k.startsWith("{") && k.endsWith("}")) {
                        String inner = k.substring(1, k.length() - 1).trim();
                        raw = raw.replace("{" + inner + "}", v);
                        raw = raw.replace(inner, v);
                    }
                    String t = k.trim();
                    raw = raw.replace("{" + t + "}", v);
                    raw = raw.replace(t, v);
                } catch (Throwable ignore) { }
            }
            return raw;
        });
    }
}