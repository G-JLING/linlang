package audit.linlang.audit.message;

import api.linlang.file.implement.LocaleProvider;
import api.linlang.file.service.LangService;
import core.linlang.audit.message.LinMsg;
import core.linlang.audit.message.LinlangInternalMessageKeys;

// core.linlang.i18n
public final class InternalMessageBinder {
    public static void bind(
            LangService lang,
            String locale,
            java.util.List<? extends LocaleProvider<LinlangInternalMessageKeys>> providers // ← 这里
    ){
        var pack = lang.bind(LinlangInternalMessageKeys.class, locale, providers);

        LinMsg.installKeys(() -> pack);
        LinMsg.install((key, args) -> {
            String raw = InternalPath.get(pack, key);
            if (raw == null) return key;
            for (var e: args.entrySet())
                raw = raw.replace("{"+e.getKey()+"}", String.valueOf(e.getValue()));
            return raw;
        });
    }
}