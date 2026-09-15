package me.jling.plugin.command;

import api.linlang.file.file.FileType;
import api.linlang.file.file.LangText;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NamingStyle;

/**
 * 运行时管理命令使用的语言字段。
 */
@NamingStyle(NamingStyle.Style.KEBAB)
@LangPack(filePath = "linlang/runtime/command", format = FileType.YAML, normalizeLocale = true)
public final class RuntimeCommandKeys {

    public LangText prefix = LangText.of("&f[&dLinlang&f] ");
    public Common common = new Common();
    public Info info = new Info();
    public Plugins plugins = new Plugins();
    public Restart restart = new Restart();
    @api.linlang.file.file.annotations.Key("reload-plugin")
    public Reload reload = new Reload();
    public ReloadAll reloadAll = new ReloadAll();
    public RestartAll restartAll = new RestartAll();
    public Problems problems = new Problems();
    public Problem problem = new Problem();

    public static final class Common {
        public LangText unknown = LangText.of("unknown");
        public LangText enabled = LangText.of("enabled");
        public LangText disabled = LangText.of("disabled");
    }

    public static final class Info {
        public LangText description = LangText.of("Show Linlang runtime version and build info");
        public LangText title = LangText.of(" &7Runtime information:");
        public LangText apiVersion = LangText.of("&7  API Version: &f{version}");
        public LangText runtimeVersion = LangText.of("&7  Runtime Version: &f{version}");
        public LangText pluginVersion = LangText.of("&7  Plugin Version: &f{version}");
    }

    public static final class Plugins {
        public LangText description = LangText.of("List all plugins registered to this runtime");
        public LangText empty = LangText.of(" &7No plugin facades are currently registered.");
        public LangText title = LangText.of(" &7Registered plugins ({count}):");
        public LangText entry = LangText.of("&7  - &f{name} &8v{version} &7({status})");
    }

    public static final class Restart {
        public LangText description = LangText.of("Rebuild a plugin facade");
        public LangText pluginLabel = LangText.of("plugin name");
        public LangText emptyName = LangText.of(" &cPlugin name cannot be empty.");
        public LangText ambiguous = LangText.of(" &cMultiple plugins matched. Enter a more specific name:");
        public LangText candidate = LangText.of("&7  - &f{name}");
        public LangText notFound = LangText.of(" &cNo registered plugin named &f{name} &cwas found.");
        public LangText success = LangText.of(" &aRebuilt the Linlang services of &f{name}&a.");
        public LangText failed = LangText.of(" &cRebuild failed. Problem code: &f{code}");
    }

    public static final class Reload {
        public LangText description = LangText.of("Soft-reload a plugin facade");
        public LangText success = LangText.of(" &aSoft-reloaded the Linlang facade of &f{name}&a.");
        public LangText failed = LangText.of(" &cReload failed. Problem code: &f{code}");
    }

    public static final class ReloadAll {
        public LangText description = LangText.of("Soft-reload the runtime and all registered plugins");
        public LangText success = LangText.of(" &aSoft-reloaded the runtime and all registered plugins.");
        public LangText partial = LangText.of(" &eReload completed with {count} failure(s). Check the problem codes.");
        public LangText failed = LangText.of(" &cReload failed. Problem code: &f{code}");
    }

    public static final class RestartAll {
        public LangText description = LangText.of("Rebuild all registered plugin facades");
        public LangText result = LangText.of(" &7Rebuilt plugin facades: &f{success} &7/ &f{total}&7.");
    }

    public static final class Problems {
        public LangText description = LangText.of("List all built-in Linlang problem codes");
        public LangText title = LangText.of(" &7Built-in problem codes ({count}):");
        public LangText entry = LangText.of("&f{code} &8[{component}] &7{description}");
    }

    public static final class Problem {
        public LangText description = LangText.of("Look up a Linlang problem code");
        public LangText codeLabel = LangText.of("problem code");
        public LangText notFound = LangText.of(" &cProblem code not found: &f{code}");
        public LangText hint = LangText.of("&7Use &f/linlang problems &7to list all built-in codes.");
        public LangText title = LangText.of(" &f{code}");
        public LangText component = LangText.of("&7Component: &f{value}");
        public LangText meaning = LangText.of("&7Meaning: &f{value}");
        public LangText resolution = LangText.of("&7Resolution: &f{value}");
        public LangText documentation = LangText.of("&7Documentation: &f{value}");
    }
}
