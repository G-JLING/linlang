package adapter.linlang.bukkit.command.interact;

// io/linlang/lincommand/plugin/BukkitInteractiveResolvers.java

import core.linlang.command.signal.Interact;
import api.linlang.command.LinCommand;
import org.bukkit.entity.Player;

import java.util.List;

public final class InteractiveResolvers {

    private static Player requirePlayer(LinCommand.ParseCtx c){
        if (c.sender() instanceof Player p) return p;
        throw new IllegalArgumentException("仅玩家可执行此参数");
    }

    public static final class ClickBlock implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("event:block"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("CLICK_BLOCK", "prompt.event.block", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("点击一个方块以继续"); }
    }

    public static final class BreakBlock implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("break:block"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("BREAK_BLOCK", "prompt.break.block", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("破坏一个方块以继续"); }
    }

    public static final class PlaceBlock implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("place:block"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("PLACE_BLOCK", "prompt.place.block", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("放置一个方块以继续"); }
    }

    public static final class ClickEntity implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("event:entity"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("CLICK_ENTITY", "prompt.event.entity", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("点击一个实体以继续"); }
    }

    public static final class DamageEntity implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("damage:entity"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("DAMAGE_ENTITY", "prompt.damage.entity", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("攻击一个实体以继续"); }
    }

    public static final class KillEntity implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("kill:entity"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("KILL_ENTITY", "prompt.kill.entity", 40_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("击杀一个实体以继续"); }
    }

    public static final class ClickItemStack implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("event:item"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("CLICK_ITEMSTACK", "prompt.event.item", 20_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("点击一个物品以继续"); }
    }

    public static final class ShootBlock implements LinCommand.TypeResolver {
        public boolean supports(String id){ return id.equalsIgnoreCase("shoot:block"); }
        public Object parse(LinCommand.ParseCtx c, String t){
            requirePlayer(c);
            throw new Interact.Signal("SHOOT_BLOCK", "prompt.shoot.block", 30_000);
        }
        public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of("用抛射物命中方块以继续"); }
    }
}
