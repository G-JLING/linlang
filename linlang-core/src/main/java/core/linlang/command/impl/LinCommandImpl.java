package core.linlang.command.impl;

/*
 * 命令注册器实现，即 LinCommand 核心实现
 * 接受注册的 LinCommand 实例，解析并装载
 * 又接受执行的命令，处理后回复
 * */

import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import core.linlang.file.runtime.LocaleTag;
import core.linlang.command.model.Model;
import core.linlang.command.model.Registration;
import core.linlang.command.parser.ArgEngine;
import core.linlang.command.parser.SpecParser;
import core.linlang.command.signal.Interact;

import java.util.*;

public final class LinCommandImpl implements LinCommand {
    // 用于控制 /root help 单页打印的条目刷数量
    private int help_page_size = 8;
    // 命令
    private final List<LinCommand.TypeResolver> resolvers = new ArrayList<>();
    private final List<Model.Node> nodes = new ArrayList<>();
    // 为每个已注册节点保存参数 i18n 标签映射：paramName -> ( "zh_CN" -> "行号", "en_GB" -> "line number" )
    public final Map<Model.Node, Map<String, Map<String, String>>> paramI18n = new IdentityHashMap<>();
    // 实例名字，或者叫命令前缀
    private String prefix = "";
    // 实例根命令
    private String root = "";
    // 实例
    private Object platform;
    // 命令消息
    private CommandMessages messages = CommandMessages.defaults();

    // 首选语言：用于 usage/描述 的渲染（外部可在读取 cfg.language 后设置）
    private LocaleTag locale = LocaleTag.parse("zh_CN");

    public LinCommand install(String pluginPrefix, Object platform, CommandMessages msgs) {
        this.prefix = pluginPrefix;
        this.platform = platform;
        this.messages = msgs == null ? CommandMessages.defaults() : msgs;
        return this;
    }

    public LinCommandImpl withMessages(CommandMessages msgs) {
        this.messages = (msgs == null ? CommandMessages.defaults() : msgs);
        return this;
    }

    public LinCommandImpl withPreferredLocaleTag(String tag) {
        this.locale = LocaleTag.parse(tag);
        return this;
    }

    public LinCommandImpl withCustomHelpPageSize(int i) {
        this.help_page_size = i;
        return this;
    }

    public LinCommandImpl withPreferredLocale(LocaleTag loc) {
        this.locale = (loc == null ? LocaleTag.parse("zh_CN") : loc);
        return this;
    }

    public LinCommand register(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc, Map<String, Map<String, String>> labelsI18n) {
        Model.Node n = doRegister(spec, exec, perm, target, desc).node;
        if (labelsI18n != null) {
            paramI18n.put(n, labelsI18n);
        }
        n.usage = buildUsage(n);
        return this;
    }


    // 实际注册实现，返回 Registration 以便后续 .labels() 注入 i18n
    private Registration doRegister(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc) {
        var n = SpecParser.parse(spec);
        n.exec = new Model.Exec();
        n.exec.fn = exec;
        n.exec.perm = (perm == null ? null : perm.node());
        n.exec.target = (target == null ? ExecTarget.ALL : target);

        n.descI18n = (desc == null ? java.util.Map.of() : desc.i18n());

        // 初次构建 usage；若后续调用 .labels()，会重建
        n.usage = buildUsage(n);

        nodes.add(n);
        return new Registration(this, n);
    }

    // 命令调度入口
    public boolean dispatch(Object sender, String label, String[] args, PlatformBridge bridge) {

        // 内置 help 调度
        if (args.length >= 1 && ("help".equalsIgnoreCase(args[0]) || "?".equals(args[0]))) {
            int page = 1;
            if (args.length >= 2) {
                try {
                    page = Math.max(1, Integer.parseInt(args[1]));
                } catch (NumberFormatException ignore) {
                }
            }
            renderHelp(sender, bridge, page);
            return true;
        }

        // 内置 info 调度
        if (args.length == 1 && "info".equalsIgnoreCase(args[0])) {
            try {
                Object plugin = this.platform;
                Object description = plugin.getClass().getMethod("getDescription").invoke(plugin);

                String name = (String) description.getClass().getMethod("getName").invoke(description);
                @SuppressWarnings("unchecked")
                java.util.List<String> authors = (java.util.List<String>) description.getClass().getMethod("getAuthors").invoke(description);
                String version = (String) description.getClass().getMethod("getVersion").invoke(description);
                String authorsStr = String.join(", ", authors);
                String libVersion = libVersion();

                sendTo(sender, prefix + " §f信息:");
                sendTo(sender, "§7   |- §f插件: " + name);
                sendTo(sender, "§7   |- §f作者: " + authorsStr);
                sendTo(sender, "§7   |- §f构建版本: " + version);
                sendTo(sender, "§7   |- §f琳琅版本: " + libVersion);
                sendTo(sender, "§7   |- §f访问: jling.me | magicpowered.cn");
                return true;
            } catch (Exception e) {
                LinLog.warn("info.cmd.error", e);
            }
        }

        // 路由，匹配 literals
        boolean anyLiteralMatched = false;
        boolean anyTargetDenied = false;
        boolean anyPermDenied = false;
        boolean anySecondLiteralExact = false;

        // 若首个参数恰好是某个「二级字面量」，优先只在这些分支中路由，以避免落到 root 分支
        List<Model.Node> exactSecond = new ArrayList<>();
        if (args.length >= 1) {
            String a0 = args[0];
            for (var n0 : nodes) {
                if (n0.literals.size() > 1 && n0.literals.get(1).equalsIgnoreCase(a0)) {
                    exactSecond.add(n0);
                }
            }
        }

        // 先按字面量长度降序，优先尝试更具体的分支
        // 例如 [re, reload] 在 [re] 的更高优先级
        List<Model.Node> candidates = exactSecond.isEmpty() ? new ArrayList<>(nodes) : exactSecond;
        candidates.sort((a, b) -> Integer.compare(b.literals.size(), a.literals.size()));

        // 在解析命令失败时，尝试给出最可能的用法
        String bestUsage = null;

        // 遍历参数
        for (var n : candidates) {

            boolean ok = matchLiterals(n.literals, label, args);
            if (!ok) continue;
            anyLiteralMatched = true;
            if (n.literals.size() > 1 && args.length >= 1 && n.literals.get(1).equalsIgnoreCase(args[0])) {
                anySecondLiteralExact = true;
            }
            int consumed = n.literals.size() - 1;
            if (consumed < 0) consumed = 0;
            String[] rest = Arrays.copyOfRange(args, consumed, args.length);
            // 解析
            var engine = new ArgEngine(resolvers);
            var vars = new LinkedHashMap<String, Object>();
            var pctx = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
            int i = 0;
            try {
                for (int pIdx = 0; pIdx < n.params.size(); pIdx++) {
                    var p = n.params.get(pIdx);

                    // 去掉注释(@...)与类型(:type...)以及其后的残留空格
                    String varKey = (p.name == null ? "" : p.name.trim());
                    int spKey = varKey.indexOf(' ');
                    if (spKey >= 0) varKey = varKey.substring(0, spKey);
                    int colonKey = varKey.indexOf(':');
                    if (colonKey >= 0) varKey = varKey.substring(0, colonKey);

                    // 若没有更多 token
                    if (i >= rest.length) {
                        if (p.optional) {
                            // 可选参数：若存在默认值，按首个类型（默认做 String）进行简单转换
                            if (p.defVal != null) {
                                String t0 = (p.types == null || p.types.isEmpty()) ? typeHint(p.name) : p.types.get(0).id;
                                if (t0 == null) t0 = "string";
                                vars.put(varKey, coerceDefault(p.defVal, t0));
                            }
                            continue;
                        } else {
                            throw new IllegalArgumentException("missing <" + varKey + ">");
                        }
                    }

                    // 是最后一个参数吗？
                    boolean isLastParam = (pIdx == n.params.size() - 1);

                    // 从原始 token 提取类型提示，如 int/double/string
                    String hinted = typeHint(p.name);
                    boolean anyStringType = (p.types == null || p.types.isEmpty())
                            ? (hinted == null || "string".equalsIgnoreCase(hinted))
                            : p.types.stream().anyMatch(ts -> "string".equalsIgnoreCase(ts.id));

                    // 最后一个且可按字符串处理，即贪婪吞并余下 token（支持带空格的新名称等）
                    if (isLastParam && anyStringType) {
                        String joined = String.join(" ", java.util.Arrays.copyOfRange(rest, i, rest.length));
                        vars.put(varKey, joined);
                        // 消费剩余的
                        i = rest.length;
                        continue;
                    }
                    // 非字符串类型的最后一个参数，不得贪婪！若余下 token 大于 1 则该分支不匹配
                    if (isLastParam && !anyStringType) {
                        if ((rest.length - i) > 1) {
                            // 回溯到外层
                            throw new IllegalArgumentException("too many arguments for non-string tail param");
                        }
                    }

                    String tok = rest[i];
                    Object val = null;
                    Exception last = null;

                    // 先走解析器
                    if (p.types != null && !p.types.isEmpty()) {
                        for (var ts : p.types) {
                            try {
                                val = engine.parseOne(pctx, ts, tok);
                                last = null;
                                break;
                            } catch (Exception ex) {
                                last = ex;
                            }
                        }
                    }

                    // 若没有注册的类型解析器，但存在类型提示，则做基础转换（并尝试读取范围约束）
                    if (val == null) {
                        if (hinted != null && hinted.equalsIgnoreCase("int")) {
                            int v = Integer.parseInt(tok);
                            int[] range = intRange(p.name);
                            if (range != null) {
                                if (v < range[0] || v > range[1])
                                    throw new IllegalArgumentException("int out of range");
                            }
                            val = v;
                        } else if (hinted != null && hinted.equalsIgnoreCase("double")) {
                            double v = Double.parseDouble(tok);
                            val = v;
                        } else if (anyStringType) {
                            val = tok;
                        }
                    }

                    if (last != null && val == null) throw last;
                    if (val == null) throw new IllegalArgumentException("bad argument for param " + varKey);

                    vars.put(varKey, val);
                    i++;
                }

                // 若还有多余的 token 未被消费，则该分支不匹配
                if (i < rest.length) {
                    continue;
                }

                // 解析成功后再校验执行者与权限
                if (n.exec.target != ExecTarget.ALL && !bridge.checkTarget(sender, n.exec.target)) {
                    anyTargetDenied = true; // 记下被拦截
                    continue;               // 尝试其他分支
                }
                if (n.exec.perm != null && !bridge.hasPermission(sender, n.exec.perm)) {
                    anyPermDenied = true;   // 记下无权限
                    continue;               // 尝试其他分支
                }

                // 执行
                var ctx = new CtxImpl(sender, vars);
                try {
                    n.exec.fn.run(ctx);
                } catch (Exception ex) {
                    LinLog.warn("cmd.exec.exception", ex);
                    bridge.msg(sender, prefix + messages.get("error.exception"));
                    return true;
                }
                return true;
            } catch (Interact.Signal sig) {
                throw new Interact.Suspend(
                        sig.kind, sig.prompt, sig.ttlMs, n, i, new java.util.LinkedHashMap<>(vars), rest);
            } catch (Interact.Suspend s) {
                throw s;
            } catch (Exception e) {
                // 参数阶段出错
                if (!(e instanceof IllegalArgumentException)) {
                    // 明确的运行，类型异常：直接提示异常消息，并在控制台打印
                    bridge.msg(sender, prefix + messages.get("error.exception"));
                    LinLog.warn("LinCommand IllegalArgumentException", e);
                    return true;
                }
                if (bestUsage == null) bestUsage = buildUsage(n);
                continue;
            }
        }

        // 匹配失败原因
        if (!anyLiteralMatched || (!anySecondLiteralExact && args.length >= 1)) {
            bridge.msg(sender, prefix + messages.get("error.unknown-command"));
            return false;
        }
        if (anyPermDenied) {
            bridge.msg(sender, prefix + messages.get("error.no-perm"));
            return true;
        }
        if (anyTargetDenied) {
            bridge.msg(sender, prefix + messages.get("error.exec-target"));
            return true;
        }
        if (bestUsage != null) {
            bridge.msg(sender, prefix + messages.get("error.bad-arg"));
            bridge.msg(sender, messages.get("help.usage") + "§f" + bestUsage);
            return true;
        }
        // 理论上不会走到这里，但如果真的到了，这个兜底
        bridge.msg(sender, prefix + messages.get("error.unknown-command"));
        return false;
    }

    // 提取类型提示（如 :int, :double, :string）
    private static String typeHint(String rawName) {
        if (rawName == null) return null;
        String s = rawName.trim();
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        int c = s.indexOf(':');
        if (c < 0) return null;
        s = s.substring(c + 1);
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        int at = s.indexOf('@');
        int end = s.length();
        if (brace >= 0) end = Math.min(end, brace);
        if (bracket >= 0) end = Math.min(end, bracket);
        if (at >= 0) end = Math.min(end, at);
        s = s.substring(0, end).trim();
        return s.isEmpty() ? null : s;
    }

    // 提取整数范围约束
    private static int[] intRange(String rawName) {
        if (rawName == null) return null;
        String s = rawName;
        int lb = s.indexOf('[');
        int rb = s.indexOf(']');
        if (lb < 0 || rb < 0 || rb <= lb) return null;
        String mid = s.substring(lb + 1, rb);
        int dots = mid.indexOf("..");
        if (dots < 0) return null;
        try {
            int min = Integer.parseInt(mid.substring(0, dots).trim());
            int max = Integer.parseInt(mid.substring(dots + 2).trim());
            return new int[]{min, max};
        } catch (Exception ignore) {
            return null;

        }
    }

    // tab 补全实现
    public List<String> tab(Object sender, String label, String[] args, PlatformBridge bridge) {
        // 分页
        if (args.length >= 1 && "help".equalsIgnoreCase(args[0])) {
            int totalPages = Math.max(1, (int) Math.ceil(nodes.size() / (double) help_page_size));
            if (args.length == 1) return List.of("1");
            if (args.length == 2 && totalPages > 1) {
                String pref = args[1];
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) {
                    String s = String.valueOf(i);
                    if (s.startsWith(pref)) pages.add(s);
                }
                return pages;
            }
            return List.of();
        }

        // 当仅输入根命令或根命令后紧跟空格时，不猜测参数，直接枚举所有二级子命令
        if (args.length == 0 || (args.length == 1 && (args[0] == null || args[0].isEmpty()))) {
            java.util.LinkedHashSet<String> subs = new java.util.LinkedHashSet<>();
            for (var n : nodes) {
                if (n.literals.size() > 1) subs.add(n.literals.get(1));
            }
            return new java.util.ArrayList<>(subs);
        }

        var engine = new ArgEngine(resolvers);
        var high = new LinkedHashSet<String>(); // 有二级字面量的分支候选
        var low = new LinkedHashSet<String>(); // 仅 root 的分支候选

        for (var n : nodes) {
            boolean matched = matchLiterals(n.literals, label, args, true);
            if (!matched) continue;
            int consumed = Math.max(0, n.literals.size() - 1);
            if (consumed > args.length) consumed = args.length;
            String[] rest = Arrays.copyOfRange(args, consumed, args.length);

            // 若正在输入第一个参数，并且该分支有二级字面量，则优先补全该二级字面量的前缀
            if (consumed == 0 && args.length == 1 && n.literals.size() > 1) {
                String need = n.literals.get(1);
                String prefTok = args[0] == null ? "" : args[0];
                if (!prefTok.isEmpty() && need.toLowerCase(java.util.Locale.ROOT).startsWith(prefTok.toLowerCase(java.util.Locale.ROOT))) {
                    high.add(need);
                    // 不再为该分支填充参数候选，避免与以数字开头的 root 分支产生竞争
                    continue;
                }
            }
            if (n.params.isEmpty()) {
                if (n.literals.size() > 1) {
                    String need = n.literals.get(1);
                    if (args.length == 0) {
                        high.add(need);
                    } else if (args.length == 1) {
                        String prefTok = args[0] == null ? "" : args[0];
                        if (need.toLowerCase(java.util.Locale.ROOT).startsWith(prefTok.toLowerCase(java.util.Locale.ROOT))) {
                            high.add(need);
                        }
                    }
                }
                continue;
            }
            var vars = new LinkedHashMap<String, Object>();
            var pctx = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
            int pi = 0;
            for (; pi < n.params.size() && pi < rest.length - 1; pi++) {
                String tok = rest[pi];
                var param = n.params.get(pi);
                boolean ok = false;
                for (var ts : param.types) {
                    try {
                        vars.put(param.name, engine.parseOne(pctx, ts, tok));
                        ok = true;
                        break;
                    } catch (Exception ignore) {
                    }
                }
                if (!ok) {
                    pi = -1;
                    break;
                }
            }
            if (pi < 0) continue;
            int completeIdx = Math.min(rest.length == 0 ? 0 : rest.length - 1, n.params.size() - 1);
            var param = n.params.get(completeIdx);
            String prefixTok = rest.length == 0 ? "" : rest[rest.length - 1];
            var bucket = (n.literals.size() > 1 ? high : low);
            // 如果正在补全第一个子命令 token，也加入二级字面量候选
            if (consumed == 0 && args.length <= 1 && n.literals.size() > 1) {
                String need = n.literals.get(1);
                String prefTok = (args.length == 0 ? "" : (args[0] == null ? "" : args[0]));
                if (prefTok.isEmpty() || need.toLowerCase(java.util.Locale.ROOT).startsWith(prefTok.toLowerCase(java.util.Locale.ROOT))) {
                    high.add(need);
                }
            }
            for (var ts : param.types) bucket.addAll(engine.completeOne(pctx, ts, prefixTok));
            if (param.desc != null && !param.desc.isBlank()) bucket.add(param.desc);
        }

        if (!high.isEmpty()) return new ArrayList<>(high);
        if (!low.isEmpty()) return new ArrayList<>(low);

        String pref = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        for (var n : nodes) {
            String first = n.literals.isEmpty() ? "" : n.literals.get(0);
            if (first.toLowerCase(java.util.Locale.ROOT).startsWith(pref)) low.add(first);
        }
        return new ArrayList<>(low);
    }

    public void addResolver(LinCommand.TypeResolver r) {
        this.resolvers.add(r);
    }


    @Deprecated
    public LinCommandImpl withlocale(LocaleTag loc) {
        return withPreferredLocale(loc);
    }

    // —— 工具 —— //
    private static boolean matchLiterals(List<String> lits, String label, String[] args) {
        return matchLiterals(lits, label, args, false);
    }

    private static boolean matchLiterals(List<String> lits, String label, String[] args, boolean prefix) {
        if (lits.isEmpty()) return false;
        if (!lits.get(0).equalsIgnoreCase(label)) {
            return false;
        }
        // TAB 模式：只要“已输入”的字面量都匹配即可（允许还没输入到子字面量）
        if (prefix) {
            int provided = Math.min(args.length, Math.max(0, lits.size() - 1));
            for (int i = 1; i <= provided; i++) {
                String tok = args[i - 1] == null ? "" : args[i - 1];
                String need = lits.get(i);
                boolean ok;
                if (i < args.length) {
                    // 对于已完整输入的前置字面量，要求完全匹配
                    ok = need.equalsIgnoreCase(tok);
                } else {
                    // 对于当前正在输入的最后一个字面量，允许前缀匹配（忽略大小写）
                    ok = need.regionMatches(true, 0, tok, 0, tok.length());
                }
                if (!ok) return false;
            }
            return true;
        }
        // 普通模式：要求完全匹配全部字面量
        for (int i = 1; i < lits.size(); i++) {
            if (i > args.length) {
                return false;
            }
            String tok = args[i - 1];
            String need = lits.get(i);
            boolean ok = need.equalsIgnoreCase(tok);
            if (!ok) return false;
        }
        return true;
    }

    private static Object coerceDefault(String s, String type) { // 简化
        if (type == null) return s;
        return switch (type) {
            case "int" -> Integer.parseInt(s);
            case "double" -> Double.parseDouble(s);
            default -> s;
        };
    }

    // 内置 help 实现
    private void renderHelp(Object sender, PlatformBridge bridge, int page) {
        // Header
        bridge.msg(sender, prefix + messages.get("help.header"));
        bridge.msg(sender, messages.get("help.legend"));

        // Pagination
        final int pageSize = help_page_size;
        int total = nodes.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int cur = Math.max(1, Math.min(page, totalPages));
        int from = (cur - 1) * pageSize;
        int to = Math.min(from + pageSize, total);

        LocaleTag senderLoc = (this.locale == null ? LocaleTag.parse("zh_CN") : this.locale);
        String localeTag = senderLoc.tag();
        String localeDash = localeTag.replace('_', '-');
        String langOnly = localeTag.contains("_")
                ? localeTag.substring(0, localeTag.indexOf('_'))
                : (localeTag.contains("-") ? localeTag.substring(0, localeTag.indexOf('-')) : localeTag);

        for (int i = from; i < to; i++) {
            var n = nodes.get(i);
            String usagePerSender = buildUsage(n);
            // Select description from n.descI18n according to locale
            String desc = "";
            if (n.descI18n != null && !n.descI18n.isEmpty()) {
                desc = n.descI18n.get(localeTag);
                if (desc == null) desc = n.descI18n.get(localeDash);
                if (desc == null) desc = n.descI18n.get(langOnly);
                if (desc == null) desc = n.descI18n.get("zh_CN");
                if (desc == null) desc = n.descI18n.get("en_GB");
                if (desc == null && !n.descI18n.isEmpty()) desc = n.descI18n.values().iterator().next();
            }
            bridge.msg(sender, "§7   |- §f" + usagePerSender + "§7 - " + (desc == null ? "" : desc));
        }

        if (total > pageSize) {
            String root = nodes.isEmpty() || nodes.get(0).literals.isEmpty() ? "" : nodes.get(0).literals.get(0);
            boolean atFirst = cur <= 1;
            boolean atLast = cur >= totalPages;

            String leftText = atFirst ? messages.get("help.home-page") : messages.get("help.previous-page");
            String rightText = atLast ? messages.get("help.last-page") : messages.get("help.next-page");
            String middle = messages.get("help.page-info").replace("{current}", String.valueOf(cur)).replace("{total_pages}", String.valueOf(totalPages));

            String leftCmd = "/" + root + " help " + (atFirst ? 1 : (cur - 1));
            String rightCmd = "/" + root + " help " + (atLast ? totalPages : (cur + 1));

            String leftHover = messages.get("help.hover-left");
            String rightHover = messages.get("help.hover-right");

            bridge.clickableRow(
                    sender,
                    new String[]{leftText, middle, rightText},
                    new String[]{leftHover, "", rightHover},
                    new String[]{leftCmd, "", rightCmd}
            );
        }
    }

    // 由 adapter 提供的平台桥
    public interface PlatformBridge {
        void msg(Object sender, String text);

        boolean hasPermission(Object sender, String node);

        default void clickable(Object sender, String text, String hover, String command) {
            msg(sender, text);
        }

        default void clickable(Object sender, String text, String hover, String command, boolean append) {
            if (append) {
                msg(sender, text);
            } else {
                clickable(sender, text, hover, command);
            }
        }

        default void clickableRow(Object sender, String[] texts, String[] hovers, String[] commands) {
            if (texts == null || texts.length == 0) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < texts.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(texts[i] == null ? "" : texts[i]);
            }
            msg(sender, sb.toString());
        }

        boolean checkTarget(Object sender, LinCommand.ExecTarget t);


    }

    private static void sendTo(Object sender, String text) {
        if (sender == null) {
            LinLog.info(text);
            return;
        }
        try {

            var m = sender.getClass().getMethod("sendMessage", String.class);
            m.invoke(sender, text);
        } catch (Throwable t) {
            LinLog.info(text);
        }
    }


    // Ctx 实现
    public static final class CtxImpl implements LinCommand.Ctx {
        final Object sender;
        final Map<String, Object> vars;

        public CtxImpl(Object s, Map<String, Object> v) {
            sender = s;
            vars = v;
        }

        public Object sender() {
            return sender;
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String name) {
            return (T) vars.get(name);
        }

        @SuppressWarnings("unchecked")
        public <T> T getOr(String n, T def) {
            return (T) vars.getOrDefault(n, def);
        }

        public java.util.Locale locale() {
            return java.util.Locale.getDefault();
        }
    }

    /**
     * 获取琳琅库版本：
     * 优先从依赖的 pom.properties 读取（对 shaded/fat-jar 最稳），
     * 其次读取 Manifest Implementation-Version，最后读系统属性，再不行返回 "dev"。
     */
    private static String libVersion() {
        // 1) 先从 pom.properties 读取（shaded 后也常被保留）
        final String[] pomPaths = {
                "META-INF/maven/me.jling/linlang-core/pom.properties",
                "META-INF/maven/me.jling/linlang/pom.properties" // 兜底：父聚合也尝试
        };
        for (String path : pomPaths) {
            // 先用 API 所在类的 ClassLoader，再用当前类的 ClassLoader
            for (ClassLoader cl : new ClassLoader[]{
                    api.linlang.command.LinCommand.class.getClassLoader(),
                    LinCommandImpl.class.getClassLoader()
            }) {
                if (cl == null) continue;
                try (java.io.InputStream in = cl.getResourceAsStream(path)) {
                    if (in != null) {
                        java.util.Properties prop = new java.util.Properties();
                        prop.load(in);
                        String v = prop.getProperty("version");
                        if (v != null && !v.isBlank()) return v.trim();
                    }
                } catch (Throwable ignore) {
                }
            }
        }

        // 2) 再尝试 Manifest 的 Implementation-Version
        try {
            Package p = api.linlang.command.LinCommand.class.getPackage();
            if (p != null) {
                String v = p.getImplementationVersion();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Throwable ignore) {
        }
        try {
            Package p = LinCommandImpl.class.getPackage();
            if (p != null) {
                String v = p.getImplementationVersion();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Throwable ignore) {
        }

        // 3) 系统属性兜底
        String v = System.getProperty("linlang.version");
        if (v != null && !v.isBlank()) return v.trim();

        // 4) 最后兜底
        return "dev";
    }

    // 根据节点与本地化环境构建 usage 文本（支持 @i18n）
    private String buildUsage(Model.Node n) {
        LocaleTag eff = (this.locale == null ? (LocaleTag.parse("zh_CN")) : this.locale);
        final String localeTag = eff.tag();

        String head = "/" + String.join(" ", n.literals);
        if (n.params == null || n.params.isEmpty()) return head;

        Map<String, Map<String, String>> labels = paramI18n.get(n);
        java.util.List<String> parts = new java.util.ArrayList<>();

        for (var p : n.params) {
            // 使用原始 token（含类型/约束/注释），用于可靠解析
            String raw = (p.name == null ? "" : p.name);
            String trimmed = raw.trim();

            // 变量名（去空格、去类型提示、去其后的残留）
            String varKey = trimmed;
            int sp = varKey.indexOf(' ');
            if (sp >= 0) varKey = varKey.substring(0, sp);
            int colon = varKey.indexOf(':');
            String namePart = (colon >= 0 ? varKey.substring(0, colon) : varKey);

            // inline 注释（@后面的内容）
            String descInline = null;
            int atPos = trimmed.indexOf('@');
            if (atPos >= 0) descInline = trimmed.substring(atPos + 1).trim();

            String displayName = null;

// 1) 若标记了 i18nTag，则从外部映射取（paramI18n）
            if (p.i18nTag) {
                if (labels != null) {
                    Map<String, String> byLocale = labels.get(namePart);
                    if (byLocale != null && !byLocale.isEmpty()) {
                        // 宽松匹配 zh_CN / zh-CN / zh
                        String tagNorm = localeTag;                  // 例如 zh_CN
                        String tagDash = tagNorm.replace('_', '-');   // zh-CN
                        String langOnly = tagNorm.contains("_")
                                ? tagNorm.substring(0, tagNorm.indexOf('_'))
                                : (tagNorm.contains("-") ? tagNorm.substring(0, tagNorm.indexOf('-')) : tagNorm);

                        String v = byLocale.get(tagNorm);
                        if (v == null) v = byLocale.get(tagDash);
                        if (v == null) v = byLocale.get(langOnly);
                        if (v == null) v = byLocale.get("zh_CN");
                        if (v == null) v = byLocale.get("en_GB");
                        if (v == null && !byLocale.isEmpty()) v = byLocale.values().iterator().next();
                        displayName = v;
                    }
                }
            }

            // 2) 未标记 i18nTag：优先使用 p.desc（内联注释文字）
            if (displayName == null && p.desc != null && !p.desc.isBlank()) {
                displayName = p.desc;
            }

            // 3) 再尝试 inline 的 @注释（trimmed 中 @ 之后的）
            if (displayName == null && descInline != null && !descInline.isEmpty()) {
                displayName = descInline;
            }

            // 4) 最后回退到冒号前的简名
            if (displayName == null || displayName.isBlank()) {
                displayName = namePart;
            }

            parts.add(p.optional ? "[" + displayName + "]" : "<" + displayName + ">");
        }

        return head + " " + String.join(" ", parts);
    }

    public void rebuildUsage(Model.Node n) {
        n.usage = buildUsage(n);
    }

}
