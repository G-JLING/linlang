package adapter.linlang.bukkit.command.resolvers;

// linlang-adapter-plugin/src/main/java/io/linlang/lincommand/plugin/BukkitResolvers.java

import api.linlang.command.LinCommand;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;

public final class BukkitResolvers {

    // minecraft:item{namespace:id|tag}
    public static final class ItemResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("minecraft:item"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            Material m = Material.matchMaterial(t);
            if (m == null) throw new IllegalArgumentException("unknown item: "+t);
            return m;
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){
            var out = new ArrayList<String>();
            for (Material m : Material.values()){
                String k = m.getKey().toString();
                if (k.toLowerCase().startsWith(p.toLowerCase())) out.add(k);
                if (out.size() > 50) break;
            }
            return out;
        }
    }

    // minecraft:player{online}
    public static final class PlayerResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("minecraft:player"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            Player p = Bukkit.getPlayerExact(t);
            if (p == null) throw new IllegalArgumentException("player offline: "+t);
            return p;
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){
            var out = new ArrayList<String>();
            for (Player pl : Bukkit.getOnlinePlayers()){
                String n = pl.getName();
                if (n.toLowerCase().startsWith(p.toLowerCase())) out.add(n);
            }
            return out;
        }
    }
}
