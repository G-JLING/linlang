package adapter.linlang.bukkit.runtime;

import adapter.linlang.bukkit.LinlangBukkitBootstrap;
import adapter.linlang.bukkit.audit.common.BukkitAuditProvider;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;
import api.linlang.audit.common.LinMsg;
import api.linlang.audit.common.LinlangInternalMessageKeys;
import core.linlang.command.message.CommandMessageKeys;
import core.linlang.command.message.CommandMessageRouter;
import core.linlang.command.message.i18n.EnGB;
import core.linlang.command.message.i18n.ZhCN;
import core.linlang.file.impl.LangServiceImpl;
import api.linlang.audit.called.LinLog;
import api.linlang.command.CommandMessages;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Function;

/**
 * 运行期辅助：审计安装/切换、命令初始化与重建、语言切换、前缀治理、重载。
 * 让 {@link LinlangBukkitBootstrap} 专注于“装配”，将可变行为下放到本类。
 */
public final class LinlangBootstrapRuntime implements AutoCloseable {

    private final JavaPlugin plugin;
    private final LangServiceImpl lang;

    // 命令 & 文案
    private LinlangBukkitCommand commands;
    private CommandMessages messages = CommandMessages.defaults();
    private String preferredLocaleTag = "zh_CN";

    // 日志审计
    private BukkitAuditProvider audit;

    // 命令前缀提供者（可运行期切换）
    private Function<JavaPlugin, String> prefixFn =
            p -> "§f[§d" + p.getDescription().getName() + "§f]";

    public LinlangBootstrapRuntime(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.plugin = plugin;
        this.lang = bootstrap.getLang();
        this.preferredLocaleTag = String.valueOf(lang.getCurrent());
    }

    public void installLinMsg() {
        var keysInstance = lang.bind(LinlangInternalMessageKeys.class,
                lang.getCurrent().toString(),
                List.of(new api.linlang.audit.common.i18n.ZhCN(), new api.linlang.audit.common.i18n.EnGB()));

        LinMsg.installKeys(() -> keysInstance);
        LinMsg.install(lang::tr);
    }

    // 安装/替换 Bukkit 审计（可选：使用 Plugin.getLogger()）
    public LinlangBootstrapRuntime installAudit(boolean usePluginLogger) {
        this.audit = new BukkitAuditProvider(this.plugin, usePluginLogger);
        LinLog.install(this.audit);
        LinLog.init("Audit Init");
        try { this.audit.bindConfigFromLinFile(); } catch (Throwable ignore) {}
        return this;
    }

    // 初始化命令系统（根据当前语言与前缀）
    public LinlangBootstrapRuntime initCommands() {
        bindCommandMessages(this.preferredLocaleTag);
        String prefix = this.prefixFn.apply(plugin);
        this.commands = new LinlangBukkitCommand()
                .install(prefix, plugin, this.messages)
                .withDefaultResolvers()
                .withInteractiveResolvers()
                .withPreferredLocaleTag(this.preferredLocaleTag);
        LinLog.init("LinCommand Init");
        return this;
    }

    // 设置固定前缀
    public LinlangBootstrapRuntime withCommandPrefix(String prefix) {
        this.prefixFn = p -> prefix;
        rebuildCommands();
        return this;
    }

    // 自定义前缀函数
    public LinlangBootstrapRuntime withCommandPrefix(Function<JavaPlugin, String> provider) {
        if (provider == null) throw new IllegalArgumentException("prefix provider null");
        this.prefixFn = provider;
        rebuildCommands();
        return this;
    }

    // 切换 Plugin.getLogger() 输出与否
    public LinlangBootstrapRuntime withPluginLogger(boolean usePluginLogger) {
        installAudit(usePluginLogger);
        return this;
    }

    // 启动/运行期设定初始语言（并重建命令）
    public LinlangBootstrapRuntime withInitialLanguage(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.preferredLocaleTag = locale;
        try { this.lang.setLocale(locale); } catch (Throwable ignore) {}
        bindCommandMessages(locale);
        rebuildCommands();
        return this;
    }

    // 仅重载 I18N（命令消息 + 审计配置）
    public LinlangBootstrapRuntime reloadI18n() {
        final String locale = (this.preferredLocaleTag == null || this.preferredLocaleTag.isBlank())
                ? String.valueOf(this.lang.getCurrent())
                : this.preferredLocaleTag;
        try { this.lang.setLocale(locale); } catch (Throwable ignore) {}
        bindCommandMessages(locale);
        rebuildCommands();
        try { if (this.audit != null) this.audit.bindConfigFromLinFile(); } catch (Throwable ignore) {}
        return this;
    }

    public LinlangBukkitCommand getCommands() { return commands; }

    @Override
    public void close() {
        try { if (commands != null) commands.close(); } catch (Exception ignore) {}
    }

    // —— 私有 —— //

    private void rebuildCommands() {
        String newPrefix = this.prefixFn.apply(this.plugin);
        try { if (this.commands != null) this.commands.close(); } catch (Exception ignore) {}
        this.commands = new LinlangBukkitCommand()
                .install(newPrefix, this.plugin, this.messages)
                .withDefaultResolvers()
                .withInteractiveResolvers()
                .withPreferredLocaleTag(this.preferredLocaleTag);
    }

    private void bindCommandMessages(String locale) {
        try {
            CommandMessageKeys keys = this.lang.bind(CommandMessageKeys.class, locale, List.of(new ZhCN(), new EnGB()));
            this.messages = new CommandMessageRouter(keys);
        } catch (Throwable t) {
            this.messages = CommandMessages.defaults();
        }
    }
}