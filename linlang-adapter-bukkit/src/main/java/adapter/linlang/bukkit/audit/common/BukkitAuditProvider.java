package adapter.linlang.bukkit.audit.common;

// BukkitAuditProvider.java

import api.linlang.called.LinLogs;
import java.util.logging.Logger;

public final class BukkitAuditProvider implements LinLogs.Provider {
    private final Logger jul;
    public BukkitAuditProvider(org.bukkit.plugin.java.JavaPlugin plugin){ this.jul = plugin.getLogger(); }
    public void log(String level, String msg, Object... kv){
        String line = fmt(level, msg, kv);
        switch (level) {
            case "DEBUG" -> jul.fine(line);
            case "INFO"  -> jul.info(line);
            case "WARN"  -> jul.warning(line);
            default      -> jul.severe(line);
        }
    }
    public void audit(String event, Object... kv){
        jul.info(fmt("AUDIT", event, kv));
    }
    private static String fmt(String lvl, String msg, Object...kv){
        StringBuilder sb=new StringBuilder().append("[").append(lvl).append("] ").append(msg);
        for (int i=0;i+1<kv.length;i+=2) sb.append(" ").append(kv[i]).append("=").append(String.valueOf(kv[i+1]));
        if ((kv.length&1)==1) sb.append(" kv_odd=").append(kv[kv.length-1]);
        return sb.toString();
    }
}