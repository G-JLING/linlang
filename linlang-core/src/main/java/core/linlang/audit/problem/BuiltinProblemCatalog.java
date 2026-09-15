package core.linlang.audit.problem;

import api.linlang.audit.problem.ProblemDefinition;
import api.linlang.runtime.version.VersionCheck;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 不依赖文件服务的 Linlang 内建问题代码目录。
 */
public final class BuiltinProblemCatalog {

    public static final String RUNTIME_CONFIG_LOAD_FAILED = "LIN-AUDIT-RUNTIME-CONFIG-LOAD-FAIL";
    public static final String TENANT_CONFIG_LOAD_FAILED = "LIN-AUDIT-TENANT-CONFIG-LOAD-FAIL";
    public static final String OUTPUT_PATH_INVALID = "LIN-AUDIT-OUTPUT-PATH-INVALID";
    public static final String FILE_WRITE_FAILED = "LIN-AUDIT-FILE-WRITE-FAIL";
    public static final String WRITER_QUEUE_FULL = "LIN-AUDIT-WRITER-QUEUE-FULL";
    public static final String WRITER_FLUSH_FAILED = "LIN-AUDIT-WRITER-FLUSH-FAIL";
    public static final String JSON_SERIALIZATION_FAILED = "LIN-AUDIT-JSON-SERIALIZE-FAIL";

    public static final String RUNTIME_ENABLE_FAILED = "LIN-RUNTIME-ENABLE-FAIL";
    public static final String MESSAGE_TEMPLATE_INSTALL_FAILED = "LIN-RUNTIME-MESSAGE-TEMPLATE-INSTALL-FAIL";
    public static final String RUNTIME_CONFIG_RELOAD_FAILED = "LIN-RUNTIME-CONFIG-RELOAD-FAIL";
    public static final String RUNTIME_LANGUAGE_RELOAD_FAILED = "LIN-RUNTIME-LANGUAGE-RELOAD-FAIL";
    public static final String FACADE_RELOAD_FAILED = "LIN-RUNTIME-FACADE-RELOAD-FAIL";
    public static final String FACADE_RESTART_FAILED = "LIN-RUNTIME-FACADE-RESTART-FAIL";
    public static final String FACADE_CLOSE_FAILED = "LIN-RUNTIME-FACADE-CLOSE-FAIL";
    public static final String RESOURCE_CLOSE_FAILED = "LIN-RUNTIME-RESOURCE-CLOSE-FAIL";

    public static final String COMMAND_INFO_FAILED = "LIN-COMMAND-INFO-FAIL";
    public static final String COMMAND_MESSAGE_LOAD_FAILED = "LIN-COMMAND-MESSAGE-LOAD-FAIL";
    public static final String COMMAND_BUKKIT_BIND_FAILED = "LIN-COMMAND-BUKKIT-BIND-FAIL";
    public static final String COMMAND_EXECUTION_FAILED = "LIN-COMMAND-EXECUTE-FAIL";
    public static final String COMMAND_ARGUMENT_PARSE_FAILED = "LIN-COMMAND-ARGUMENT-PARSE-FAIL";
    public static final String COMMAND_LOCALE_REFRESH_FAILED = "LIN-COMMAND-LOCALE-REFRESH-FAIL";
    public static final String COMMAND_GROUP_CALLBACK_FAILED = "LIN-COMMAND-GROUP-CALLBACK-FAIL";

    public static final String CONFIG_SAVE_FAILED = "LIN-FILE-CONFIG-SAVE-FAIL";
    public static final String CONFIG_RELOAD_FAILED = "LIN-FILE-CONFIG-RELOAD-FAIL";
    public static final String CONFIG_BIND_FAILED = "LIN-FILE-CONFIG-BIND-FAIL";
    public static final String CONFIG_LOAD_FAILED = "LIN-FILE-CONFIG-LOAD-FAIL";
    public static final String LANGUAGE_LOCALE_SWITCH_FAILED = "LIN-FILE-LANGUAGE-LOCALE-SWITCH-FAIL";
    public static final String LANGUAGE_BIND_FAILED = "LIN-FILE-LANGUAGE-BIND-FAIL";
    public static final String LANGUAGE_SAVE_FAILED = "LIN-FILE-LANGUAGE-SAVE-FAIL";
    public static final String LANGUAGE_RELOAD_FAILED = "LIN-FILE-LANGUAGE-RELOAD-FAIL";
    public static final String LANGUAGE_ENSURE_FAILED = "LIN-FILE-LANGUAGE-ENSURE-FAIL";
    public static final String LANGUAGE_LAZY_LOAD_FAILED = "LIN-FILE-LANGUAGE-LAZY-LOAD-FAIL";
    public static final String LANGUAGE_LISTENER_FAILED = "LIN-FILE-LANGUAGE-LISTENER-FAIL";
    public static final String LANGUAGE_REFERENCE_FAILED = "LIN-FILE-LANGUAGE-REFERENCE-FAIL";
    public static final String VIEW_LANGUAGE_REFRESH_FAILED = "LIN-VIEW-LANGUAGE-REFRESH-FAIL";
    public static final String LANGUAGE_RESOURCE_LOAD_FAILED = "LIN-FILE-LANGUAGE-RESOURCE-LOAD-FAIL";
    public static final String LANGUAGE_LOCALE_SCAN_FAILED = "LIN-FILE-LANGUAGE-LOCALE-SCAN-FAIL";
    public static final String LANGUAGE_LOCALE_NORMALIZE_FAILED = "LIN-FILE-LANGUAGE-LOCALE-NORMALIZE-FAIL";
    public static final String LANGUAGE_RESOURCE_COPY_FAILED = "LIN-FILE-LANGUAGE-RESOURCE-COPY-FAIL";
    public static final String DIFF_WRITE_FAILED = "LIN-FILE-DIFF-WRITE-FAIL";
    public static final String FILE_MAPPING_FAILED = "LIN-FILE-OBJECT-MAPPING-FAIL";
    public static final String WATCH_CALLBACK_FAILED = "LIN-FILE-WATCH-CALLBACK-FAIL";
    public static final String WATCH_START_FAILED = "LIN-FILE-WATCH-START-FAIL";
    public static final String WATCH_LOOP_FAILED = "LIN-FILE-WATCH-LOOP-FAIL";

    public static final String DATA_FLUSH_FAILED = "LIN-DATA-FLUSH-FAIL";
    public static final String DATA_INITIALIZATION_FAILED = "LIN-DATA-INITIALIZE-FAIL";
    public static final String DATA_MIGRATION_FAILED = "LIN-DATA-MIGRATE-FAIL";
    public static final String DATA_OPERATION_FAILED = "LIN-DATA-OPERATION-FAIL";
    public static final String DATA_RESOURCE_CLOSE_FAILED = "LIN-DATA-RESOURCE-CLOSE-FAIL";
    public static final String BANNER_FONT_LOAD_FAILED = "LIN-BANNER-FONT-LOAD-FAIL";
    public static final String EVENT_LISTENER_FAILED = "LIN-EVENT-LISTENER-FAIL";
    public static final String TEXT_SOURCE_TOO_LONG = "LIN-TEXT-SOURCE-TOO-LONG";
    public static final String YAML_INITIALIZATION_FAILED = "LIN-YAML-INIT-FAIL";
    public static final String MESSAGE_PREFIX_RESOLVE_FAILED = "LIN-MESSAGE-PREFIX-RESOLVE-FAIL";
    public static final String MESSAGE_DELIVERY_FAILED = "LIN-MESSAGE-DELIVERY-FAIL";
    public static final String MAIN_DISPATCH_FAILED = "LIN-PLATFORM-MAIN-DISPATCH-FAIL";
    public static final String ASYNC_DISPATCH_FAILED = "LIN-PLATFORM-ASYNC-DISPATCH-FAIL";
    public static final String VIEW_INITIALIZATION_FAILED = "LIN-VIEW-INITIALIZE-FAIL";
    public static final String VIEW_DEFINITION_LOAD_FAILED = "LIN-VIEW-DEFINITION-LOAD-FAIL";
    public static final String VIEW_SOURCE_LOAD_FAILED = "LIN-VIEW-SOURCE-LOAD-FAIL";
    public static final String VIEW_HOOK_EXECUTION_FAILED = "LIN-VIEW-HOOK-EXECUTE-FAIL";
    public static final String VIEW_REOPEN_FAILED = "LIN-VIEW-REOPEN-FAIL";
    public static final String VIEW_ICON_RESOLVE_FAILED = "LIN-VIEW-ICON-RESOLVE-FAIL";

    private static final String DOCUMENTATION = "/p/linlang/total_service/审计服务";
    private static final Map<String, ProblemDefinition> DEFINITIONS = definitions();

    public Optional<ProblemDefinition> lookup(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return Optional.ofNullable(DEFINITIONS.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public List<ProblemDefinition> list() {
        return DEFINITIONS.values().stream()
                .sorted(java.util.Comparator.comparing(ProblemDefinition::code))
                .toList();
    }

    private static Map<String, ProblemDefinition> definitions() {
        Map<String, ProblemDefinition> values = new LinkedHashMap<>();
        add(values, "LIN-RUNTIME-RELOAD-FAIL", "Runtime",
                "重载存在失败步骤，未完成的步骤不会报告成功。",
                "查看失败文件和步骤，修正后重新执行软重载。");
        add(values, LANGUAGE_REFERENCE_FAILED, "File",
                "配置语言引用不存在或文本类型不匹配，已使用回退内容。",
                "检查语言包别名、包内路径以及字符串或字符串列表类型。");
        add(values, LANGUAGE_LISTENER_FAILED, "File",
                "语言变更监听器执行失败。", "根据异常检查消费语言变更的服务。");
        add(values, VIEW_LANGUAGE_REFRESH_FAILED, "View",
                "语言变更后界面重绘失败。", "根据 view 上下文检查语言引用和平台界面状态。");
        add(values, RUNTIME_CONFIG_LOAD_FAILED, "Audit",
                "运行时审计配置无法加载，当前使用安全默认配置。",
                "检查 audit.yml 的结构、字段类型和文件权限。");
        add(values, TENANT_CONFIG_LOAD_FAILED, "Audit",
                "插件审计租户配置无法加载，插件将暂时使用运行时配置。",
                "检查插件数据目录中的 audit.yml，并确认插件 owner 已正确注册。");
        add(values, OUTPUT_PATH_INVALID, "Audit",
                "日志输出路径无效或离开了插件数据目录。",
                "使用插件数据目录内的相对路径，不要填写绝对路径或包含 .. 的路径。");
        add(values, FILE_WRITE_FAILED, "Audit",
                "日志、审计或问题文件写入失败。",
                "检查磁盘空间、目录权限、文件占用和路径有效性。");
        add(values, WRITER_QUEUE_FULL, "Audit",
                "异步文件写入队列已满，当前记录已经降级为同步写入。",
                "检查磁盘写入速度，并根据负载调整 queue-capacity。");
        add(values, WRITER_FLUSH_FAILED, "Audit",
                "文件写入队列未能在规定时间内完成刷新。",
                "检查磁盘状态，并确认插件关闭流程没有阻塞写入线程。");
        add(values, JSON_SERIALIZATION_FAILED, "Audit",
                "结构化记录无法序列化为 JSON。",
                "检查上下文字段是否包含不可安全转换的对象。");

        add(values, RUNTIME_ENABLE_FAILED, "Runtime",
                "Linlang 运行时启动失败。",
                "检查同一异常的 cause，并确认 API 版本、服务注册和运行环境兼容。");
        add(values, VersionCheck.INCOMPATIBLE_CODE, "Runtime",
                "依赖版本与运行时的 A.B 版本号不同，或运行时缺少插件要求的 API 功能版本，已终止初始化。",
                "安装满足插件要求的 Runtime；版本过低时获取新的匹配构建：" + VersionCheck.PROJECT_URL);
        add(values, VersionCheck.WARNING_CODE, "Runtime",
                "运行时的 API 功能版本高于插件编译版本，请注意检查兼容性。",
                "建议使用相同的 A.B.C；插件可以更新 API 依赖并重新测试：" + VersionCheck.PROJECT_URL);
        add(values, VersionCheck.INVALID_CODE, "Runtime",
                "无法识别依赖或运行时的四段版本号，已终止初始化。",
                "检查版本元数据是否为 A.B.C.D 格式，并重新获取完整构建：" + VersionCheck.PROJECT_URL);
        add(values, MESSAGE_TEMPLATE_INSTALL_FAILED, "Runtime",
                "运行时内建消息模板无法安装。",
                "检查运行时语言资源与语言服务是否已经完成初始化。");
        add(values, RUNTIME_CONFIG_RELOAD_FAILED, "Runtime",
                "运行时配置服务重载失败。",
                "检查配置文件格式、字段类型和文件权限。");
        add(values, RUNTIME_LANGUAGE_RELOAD_FAILED, "Runtime",
                "运行时语言服务重载失败。",
                "检查语言文件格式、语言代码和文件权限。");
        add(values, FACADE_RELOAD_FAILED, "Runtime",
                "插件门面软重载失败。",
                "根据 owner 定位插件，并检查前缀、语言与服务刷新回调。");
        add(values, FACADE_RESTART_FAILED, "Runtime",
                "插件门面硬重启失败。",
                "根据 owner 定位插件，并检查文件、命令、消息和界面服务的重建过程。");
        add(values, FACADE_CLOSE_FAILED, "Runtime",
                "插件门面关闭失败。",
                "检查门面持有服务的关闭实现，并确认没有阻塞任务。");
        add(values, RESOURCE_CLOSE_FAILED, "Runtime",
                "运行时资源释放失败。",
                "根据 resource 与 owner 上下文定位未能关闭的服务。");

        add(values, COMMAND_INFO_FAILED, "Command",
                "命令框架无法读取插件信息。",
                "检查平台插件描述对象和运行时版本元数据。");
        add(values, COMMAND_MESSAGE_LOAD_FAILED, "Command",
                "命令框架的内建消息无法从语言服务加载。",
                "检查命令语言资源；运行时会使用不依赖文件的默认消息继续启动。");
        add(values, COMMAND_BUKKIT_BIND_FAILED, "Command",
                "命令根节点无法绑定到 Bukkit PluginCommand。",
                "确认命令根节点已在 plugin.yml 中声明，并由正确的插件注册。");
        add(values, COMMAND_EXECUTION_FAILED, "Command",
                "已匹配命令的执行器抛出异常。",
                "根据 command 与 sender 上下文检查命令业务代码。");
        add(values, COMMAND_ARGUMENT_PARSE_FAILED, "Command",
                "命令参数解析器发生非预期异常。",
                "检查参数规范、解析器实现和收到的参数类型。");
        add(values, COMMAND_LOCALE_REFRESH_FAILED, "Command",
                "命令用法和参数说明无法刷新到新语言。",
                "检查延迟语言字段提供者以及命令描述、参数标签的实现。");
        add(values, COMMAND_GROUP_CALLBACK_FAILED, "Command",
                "命令组的公共结果回调执行失败。",
                "根据 command、stage 与 handler 上下文检查命令组回调代码。");

        add(values, CONFIG_SAVE_FAILED, "File",
                "配置对象无法写入文件。",
                "检查目标路径、文件权限、配置字段和值的可序列化性。");
        add(values, CONFIG_RELOAD_FAILED, "File",
                "配置文件无法重载到现有对象。",
                "检查配置格式、迁移器和字段类型是否与代码契约一致。");
        add(values, CONFIG_BIND_FAILED, "File",
                "配置类无法完成首次绑定。",
                "检查配置注解、构造方法、迁移器、文件格式和字段类型。");
        add(values, CONFIG_LOAD_FAILED, "File",
                "配置加载或类型校验失败，失败文件保留原有活动值。",
                "查看文件内的 Linlang 诊断注释或同名 .errors.txt 文件，修正后重新加载。");
        add(values, LANGUAGE_LOCALE_SWITCH_FAILED, "File",
                "语言对象无法切换到目标语言。",
                "检查目标语言文件和已绑定语言字段的类型。");
        add(values, LANGUAGE_BIND_FAILED, "File",
                "语言类无法完成首次绑定。",
                "检查 LangPack 注解、无参构造方法、资源文件和语言字段类型。");
        add(values, LANGUAGE_SAVE_FAILED, "File",
                "语言对象无法写入语言文件。",
                "检查语言文件路径、权限和字段值的可序列化性。");
        add(values, LANGUAGE_RELOAD_FAILED, "File",
                "语言文件无法重载到现有语言对象。",
                "检查语言文件格式以及 LangText、LangList 和 LangMap 字段结构。");
        add(values, LANGUAGE_ENSURE_FAILED, "File",
                "语言文件完整性检查或默认值补齐失败。",
                "检查语言包注解、语言代码、资源文件和目标目录权限。");
        add(values, LANGUAGE_LAZY_LOAD_FAILED, "File",
                "引用型语言字段无法按需加载目标语言。",
                "检查目标语言文件格式、路径和 LangText、LangList 或 LangMap 对应值的类型。");
        add(values, LANGUAGE_RESOURCE_LOAD_FAILED, "File",
                "内建语言资源存在但无法读取或解析。",
                "检查 langservice 资源内容、字符编码和打包结果。");
        add(values, LANGUAGE_LOCALE_SCAN_FAILED, "File",
                "语言目录无法扫描可用语言文件。",
                "检查语言目录权限、路径有效性和文件系统状态。");
        add(values, LANGUAGE_LOCALE_NORMALIZE_FAILED, "File",
                "语言文件名无法规范为标准语言代码。",
                "检查文件占用、目录权限和规范化后是否发生名称冲突。");
        add(values, LANGUAGE_RESOURCE_COPY_FAILED, "File",
                "内建语言资源无法复制到插件数据目录。",
                "检查目标目录权限、磁盘空间和内建资源是否可读取。");
        add(values, DIFF_WRITE_FAILED, "File",
                "缺失字段差异文件无法生成。",
                "检查目标目录权限和配置或语言文档是否能够序列化。");
        add(values, FILE_MAPPING_FAILED, "File",
                "文件文档与 Java 对象之间的字段映射失败。",
                "检查字段可访问性、嵌套对象无参构造方法和集合字段类型。");
        add(values, WATCH_CALLBACK_FAILED, "File",
                "文件热重载回调执行失败。",
                "检查对应文件的重载逻辑，并查看 path 上下文。");
        add(values, WATCH_START_FAILED, "File",
                "文件热重载监听无法启动。",
                "检查监听目录是否可创建，以及系统文件监听器是否可用。");
        add(values, WATCH_LOOP_FAILED, "File",
                "文件监听循环发生非预期故障。",
                "检查 WatchService 状态、监听目录和回调线程是否仍然有效。");

        add(values, DATA_FLUSH_FAILED, "Data",
                "仓库中的待处理数据无法刷新。",
                "检查数据库连接、事务状态和仓库实现。");
        add(values, DATA_INITIALIZATION_FAILED, "Data",
                "数据库目录或连接池无法初始化。",
                "检查 JDBC 配置、驱动、数据库目录权限和数据库可用性。");
        add(values, DATA_MIGRATION_FAILED, "Data",
                "实体表结构创建或迁移失败。",
                "检查数据库权限、实体注解、列定义和现有表结构。");
        add(values, DATA_OPERATION_FAILED, "Data",
                "数据库仓库操作执行失败。",
                "根据 operation、entity 与 table 上下文检查 SQL、连接和实体字段映射。");
        add(values, DATA_RESOURCE_CLOSE_FAILED, "Data",
                "数据库仓库或连接池无法正常关闭。",
                "检查仍在执行的数据库任务和资源关闭实现。");
        add(values, BANNER_FONT_LOAD_FAILED, "Banner",
                "ASCII Banner 字体资源存在但无法解析。",
                "检查字体 YAML 的 height、gap 与 glyphs 结构。");
        add(values, EVENT_LISTENER_FAILED, "Event",
                "事件监听器执行失败。",
                "根据 event 与 owner 上下文定位监听器业务代码。");
        add(values, TEXT_SOURCE_TOO_LONG, "Text",
                "高级字符串源码超过运行时长度限制。",
                "缩短文本或拆分消息，不要把大型外部内容直接作为高级字符串解析。");
        add(values, YAML_INITIALIZATION_FAILED, "YAML",
                "SnakeYAML 无法加载或初始化。",
                "检查 SnakeYAML 依赖是否存在，并确认版本与当前 Runtime 兼容。");
        add(values, MESSAGE_PREFIX_RESOLVE_FAILED, "Messenger",
                "消息前缀提供函数执行失败。",
                "检查自定义前缀提供函数，并避免在其中访问尚未初始化的服务。");
        add(values, MESSAGE_DELIVERY_FAILED, "Messenger",
                "已选中的消息传输器无法完成投递。",
                "根据 transport 与 recipient 上下文检查平台连接和传输器实现。");
        add(values, MAIN_DISPATCH_FAILED, "Platform",
                "任务无法提交到 Bukkit 主线程，已尝试当前线程降级执行。",
                "检查插件是否已禁用，以及 Bukkit 调度器是否仍可接受任务。");
        add(values, ASYNC_DISPATCH_FAILED, "Platform",
                "任务无法提交到 Bukkit 异步调度器，已尝试当前线程降级执行。",
                "检查插件是否已禁用，以及 Bukkit 调度器是否仍可接受任务。");
        add(values, VIEW_INITIALIZATION_FAILED, "View",
                "界面服务或平台事件桥初始化失败。",
                "检查 GUI 事件监听注册、平台上下文和视图目录。");
        add(values, VIEW_DEFINITION_LOAD_FAILED, "View",
                "GUI 定义文件无法读取、解析或编译。",
                "根据 view 上下文检查 gui 目录中的 YAML 或 JSON 文件与布局定义。");
        add(values, VIEW_SOURCE_LOAD_FAILED, "View",
                "GUI 动态数据源执行失败。",
                "根据 view、area 与 source 上下文检查数据源实现。");
        add(values, VIEW_HOOK_EXECUTION_FAILED, "View",
                "GUI 交互回调执行失败。",
                "根据 view、hook 与 slot 上下文检查回调业务代码。");
        add(values, VIEW_REOPEN_FAILED, "View",
                "禁止手动关闭的 GUI 无法重新打开。",
                "检查玩家在线状态、插件生命周期和 Bukkit 调度器状态。");
        add(values, VIEW_ICON_RESOLVE_FAILED, "View",
                "GUI 图标无法由平台资源解析器建立。",
                "根据 resolver 与 icon 上下文检查材质名称或可选资源插件接口。");
        return Map.copyOf(values);
    }

    private static void add(Map<String, ProblemDefinition> target,
                            String code,
                            String component,
                            String description,
                            String resolution) {
        target.put(code, new ProblemDefinition(
                code,
                component,
                description,
                resolution,
                DOCUMENTATION + "#问题代码目录"
        ));
    }
}
