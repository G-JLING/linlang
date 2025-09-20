package io.linlang.filesystem.runtime;

// linlang-core/src/main/java/io/linlang/filesystem/runtime/Binder.java
import io.linlang.filesystem.annotations.*;
import io.linlang.filesystem.types.FileFormat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.*;

public final class Binder {
    public static Optional<BoundConfig> configOf(Class<?> type){
        ConfigFile cf = type.getAnnotation(ConfigFile.class);
        if (cf == null) return Optional.empty();
        return Optional.of(new BoundConfig(cf.path(), cf.name(), cf.format(), comments(type), fieldKeys(type)));
    }
    public static Optional<BoundAddon> addonOf(Class<?> type){
        AddonFile af = type.getAnnotation(AddonFile.class);
        if (af == null) return Optional.empty();
        return Optional.of(new BoundAddon(af.path(), af.name(), af.format(), comments(type), fieldKeys(type)));
    }
    public static Optional<BoundLang> langOf(Class<?> type){
        LangPack lp = type.getAnnotation(LangPack.class);
        if (lp == null) return Optional.empty();
        return Optional.of(new BoundLang(lp.name().isEmpty()? lp.locale(): lp.name(), lp.path(), lp.format(), lp.locale(), fieldMessages(type)));
    }
    public static Optional<BoundTable> tableOf(Class<?> type){
        Table t = type.getAnnotation(Table.class);
        if (t == null) return Optional.empty();
        return Optional.of(new BoundTable(t.name(), t.comment(), type));
    }

    // —— 映射产物 —— //
    public record BoundConfig(String path, String name, FileFormat fmt, List<String> comments, Map<Field,String> keyMap){}
    public record BoundAddon (String path, String name, FileFormat fmt, List<String> comments, Map<Field,String> keyMap){}
    public record BoundLang  (String name, String path, FileFormat fmt, String locale, Map<Field,Msg> fieldMessages) {}
    public record BoundTable (String name, String comment, Class<?> type){}

    public record Msg(String key, String def, boolean plural){}

    private static List<String> comments(Class<?> type){
        Comment c = type.getAnnotation(Comment.class);
        return c == null ? List.of() : List.of(c.value());
    }

    private static Map<Field,String> fieldKeys(Class<?> type){
        Map<Field,String> m = new LinkedHashMap<>();
        for (Field f: type.getFields()){
            if (!java.lang.reflect.Modifier.isPublic(f.getModifiers())) continue;
            Key k = f.getAnnotation(Key.class);
            m.put(f, k==null? f.getName() : k.value());
        }
        return m;
    }

    private static Map<Field,Msg> fieldMessages(Class<?> type){
        Map<Field,Msg> m = new LinkedHashMap<>();
        for (Field f: type.getFields()){
            Message msg = f.getAnnotation(Message.class);
            if (msg != null) { m.put(f, new Msg(msg.key(), msg.def(), false)); continue; }
            Plurals pl = f.getAnnotation(Plurals.class);
            if (pl != null) { m.put(f, new Msg(pl.key(), String.join("\n", pl.forms()), true)); }
        }
        return m;
    }
}