package adapter.linlang.bukkit.command.resolvers;

// linlang-adapter-plugin/src/main/java/io/linlang/lincommand/plugin/BukkitResolvers.java

import api.linlang.command.LinCommand;
import core.linlang.command.parser.CommandArgumentException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public final class BukkitResolvers {

    private static final int MAX_COMPLETIONS = 50;

    // minecraft:item{namespace:id|tag}
    public static final class ItemResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("minecraft:item"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            Material m = Material.matchMaterial(t);
            if (!isItem(m)) throw new CommandArgumentException("unknown item: " + t);
            return m;
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){
            String prefix = p == null ? "" : p.toLowerCase(Locale.ROOT);
            var out = new ArrayList<String>();
            for (Material m : Material.values()){
                if (!isItem(m)) continue;
                String k = m.getKey().toString();
                if (k.startsWith(prefix) || (!prefix.contains(":") && m.getKey().getKey().startsWith(prefix))) out.add(k);
                if (out.size() >= MAX_COMPLETIONS) break;
            }
            return out;
        }
        private static boolean isItem(Material material) {
            return material != null && !material.isLegacy() && !material.isAir() && material.isItem();
        }
    }

    // minecraft:player{online}
    public static final class PlayerResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("minecraft:player"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            Player p = Bukkit.getPlayerExact(t);
            if (p == null) throw new CommandArgumentException("player offline: " + t);
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

    public static final class OfflinePlayerResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){
            return id.equalsIgnoreCase("minecraft:offline-player")
                    || id.equalsIgnoreCase("offline-player");
        }
        public Object parse(LinCommand.ParseCtx c, String t){
            UUID uniqueId = parseUuid(t);
            if (uniqueId != null) return Bukkit.getOfflinePlayer(uniqueId);

            Player online = Bukkit.getPlayerExact(t);
            if (online != null) return online;
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                String name = player.getName();
                if (name != null && name.equalsIgnoreCase(t)) return player;
            }
            throw new CommandArgumentException("unknown offline player: " + t);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){
            String prefix = p == null ? "" : p.toLowerCase(Locale.ROOT);
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                addName(values, player.getName(), prefix);
                if (values.size() >= MAX_COMPLETIONS) return new ArrayList<>(values);
            }
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                addName(values, player.getName(), prefix);
                if (values.size() >= MAX_COMPLETIONS) break;
            }
            return new ArrayList<>(values);
        }
    }

    public static final class LocationResolver implements LinCommand.TypeResolver {
        public boolean supports(String id){
            return id.equalsIgnoreCase("minecraft:location")
                    || id.equalsIgnoreCase("location");
        }
        public Object parse(LinCommand.ParseCtx c, String t){
            ParsedLocation parsed = parseLocation(t);
            World world = parsed.worldName == null
                    ? senderWorld(c)
                    : findWorld(parsed.worldName);
            return new Location(
                    world, parsed.x, parsed.y, parsed.z, parsed.yaw, parsed.pitch
            );
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){
            String prefix = p == null ? "" : p;
            if (prefix.contains(",")) return List.of();

            List<String> values = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                String candidate = world.getName() + ",";
                if (candidate.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    values.add(candidate);
                }
                if (values.size() >= MAX_COMPLETIONS) break;
            }
            return values;
        }
    }

    static ParsedLocation parseLocation(String token) {
        String[] parts = token == null ? new String[0] : token.split(",", -1);
        boolean explicitWorld = parts.length == 4 || parts.length == 6;
        if (!explicitWorld && parts.length != 3 && parts.length != 5) {
            throw new CommandArgumentException("invalid location: " + token);
        }

        int offset = explicitWorld ? 1 : 0;
        String worldName = explicitWorld ? parts[0].trim() : null;
        if (explicitWorld && worldName.isEmpty()) {
            throw new CommandArgumentException("location world is empty");
        }

        double x = finiteDouble(parts[offset], "x");
        double y = finiteDouble(parts[offset + 1], "y");
        double z = finiteDouble(parts[offset + 2], "z");
        float yaw = 0.0F;
        float pitch = 0.0F;
        if (parts.length - offset == 5) {
            yaw = finiteFloat(parts[offset + 3], "yaw");
            pitch = finiteFloat(parts[offset + 4], "pitch");
        }
        return new ParsedLocation(worldName, x, y, z, yaw, pitch);
    }

    private static World senderWorld(LinCommand.ParseCtx context) {
        if (context.sender() instanceof Player player) return player.getWorld();
        throw new CommandArgumentException("location requires a world for non-player senders");
    }

    private static World findWorld(String name) {
        World exact = Bukkit.getWorld(name);
        if (exact != null) return exact;
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) return world;
        }
        throw new CommandArgumentException("unknown world: " + name);
    }

    private static double finiteDouble(String text, String name) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value)) throw new CommandArgumentException("invalid " + name);
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandArgumentException("invalid " + name, exception);
        }
    }

    private static float finiteFloat(String text, String name) {
        try {
            float value = Float.parseFloat(text.trim());
            if (!Float.isFinite(value)) throw new CommandArgumentException("invalid " + name);
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandArgumentException("invalid " + name, exception);
        }
    }

    private static UUID parseUuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            return value.toString().equalsIgnoreCase(text) ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void addName(Set<String> values, String name, String prefix) {
        if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) values.add(name);
    }

    record ParsedLocation(String worldName, double x, double y, double z, float yaw, float pitch) {}
}
