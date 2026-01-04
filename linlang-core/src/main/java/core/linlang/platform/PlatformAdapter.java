// core/linlang/platform/PlatformAdapter.java
package core.linlang.platform;

import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.file.path.PathResolver;
import api.linlang.messenger.LinMessenger;
import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import core.linlang.event.dispatcher.EventDispatcher;
import core.linlang.file.impl.LangServiceImpl;

import java.util.function.Supplier;

/**
 * 平台适配器：core 只依赖该接口，不直接依赖 Bukkit/Fabric/Velocity 等平台 API。
 *
 * @param <P> 平台上下文类型（Bukkit=JavaPlugin，Velocity=PluginContainer 或 ProxyServer 等）
 */
public interface PlatformAdapter<P> {

    /** 获取平台事件调度器（用于 DefaultEventBus 确保主线程/异步执行语义） */
    EventDispatcher dispatcher(P runtimeHost);

    /** 为指定 owner 创建路径解析器（用于配置/语言/数据库文件落到各插件自己的目录） */
    PathResolver pathResolver(P owner);

    /** 创建平台命令服务 */
    LinCommand createCommands(P owner, String locale, Supplier<String> totalPrefix, CommandMessages messages);

    /** 创建消息服务（通常与平台无关，但可注入平台特性） */
    LinMessenger createMessenger(LangServiceImpl lang);

    /** 创建运行时全局审计/日志 provider */
    AbstractAuditProvider createGlobalAudit(P runtimeHost, AuditConfig cfg, boolean usePluginLogger);

    /** 给某个插件 owner 注册“租户审计配置” */
    void registerAuditTenant(AbstractAuditProvider globalAudit, P owner, AuditConfig cfg, boolean usePluginLogger);

    /** 校验 facade 传入的 platformContext（默认不校验） */
    default void validatePlatformContext(P owner, Object platformContext) {}

    /** 默认的 totalPrefix（当插件没设置 totalPrefix/totalPrefixProvider 时） */
    String defaultTotalPrefix(P owner);

    /** 运行时版本（用于日志输出） */
    String runtimeVersion(P runtimeHost);
}