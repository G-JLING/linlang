package io.linlang.filesystem.util;

// linlang-core/src/main/java/io/linlang/filesystem/util/IOs.java

import java.io.IOException;
import java.nio.file.*;

public final class IOs {
    private IOs(){}
    public static void ensureDir(Path p){
        try { Files.createDirectories(p); } catch (IOException e) { throw new RuntimeException(e); }
    }
    public static void writeString(Path p, String s){
        ensureDir(p.getParent());
        try { Files.writeString(p, s, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException e){ throw new RuntimeException(e); }
    }
    public static String readString(Path p){
        try { return Files.readString(p); } catch (IOException e){ throw new RuntimeException(e); }
    }
    public static boolean exists(Path p){ return Files.exists(p); }
}