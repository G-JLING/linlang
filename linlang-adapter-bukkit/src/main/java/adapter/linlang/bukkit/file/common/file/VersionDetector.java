package adapter.linlang.bukkit.file.common.file;

import org.bukkit.Bukkit;

/* 取 org.bukkit.craftbukkit 包名中的版本号，如 v1_12_R1。 */
public final class VersionDetector {
    private VersionDetector(){}
    public static String nmsSuffix(){
        String name = Bukkit.getServer().getClass().getPackage().getName();
        int i = name.lastIndexOf('.');
        return i>0 ? name.substring(i+1) : "v_unk";
    }
    public static boolean isAtLeast112(){
        String v = nmsSuffix();
        if (!v.startsWith("v1_")) return true;
        try {
            int minor = Integer.parseInt(v.substring(3, v.indexOf('_', 3)));
            return minor >= 12;
        } catch (Exception ignore){ return true; }
    }
}