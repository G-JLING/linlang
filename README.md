# 琳琅-运行时
琳琅（linlang）是一个用于简化 Minecraft（模组、插件）开发的开发库，由 JLING 编写，计划长期维护。

>此项目正在开发过程中。

此开发库通过了诸 Java 优秀开发框架的代码模式，尝试简化 Minecraft 开发的繁琐程度。我期望其拥有如下特点:
1. 在稳定可靠的基础上，在保证良好的逻辑性与可读性的前提下，尽可能简化 Minecraft 开发过程中高重复、高繁琐、低逻辑意义的代码。
2. 通过分离接口和适配器，使得其具有高可移植性，可以简单地转移到 Bukkit、Sponge，甚至是 Forge/NeoForge 模组平台。
3. 拥有全面的功能框架，包括文件、命令、GUI、显示、上下文、验证、通信等诸多功能。
4. 提供尽可能可靠的错误检查、报告与审计日志。

现有功能:
- 配置文件
- 数据库
- 语言（国际化）
- 命令
- 审计

## 项目结构
琳琅分为两个部分：开发者接口和运行时。其中，开发者接口是面向所有想要使用 linlang 开发插件或模组的开发者的；而运行时仓库则发布 java 程序包，运行在游戏或服务端中，为 linlang 接口提供具体实现。

您当前正在浏览「琳琅-运行时」仓库。

[点此前往](https://github.com/G-JLING/linlang-api) 琳琅-开发者接口仓库。

## WIKI
前往 https://jling.me/linlang 以浏览琳琅使用说明。

## 依赖
琳琅目前仍在开发，还未发布仓库。

在插件主类（继承 JavaPlugin）的 `onEnable()` 方法中
```java
LinlangBukkitBootstrap bootstrap = LinlangBukkitBootstrap.install(this);
```
即可装载 Linlang 服务。

## 文件与数据
通过 linlang，可以简单地实现创建文件、管理文件与连接数据库。

### 1. 配置文件（ConfigService）

#### 注解与定义
- 使用一个类来定义一个文件，该类被 `@ConfigFile` 注解，应传入如下参数：
  1. `name="文件名"` 是配置文件的名字，不包含拓展名。
  2. `path=""` 是配置文件的生成路径，根为 `plugins/当前插件/` 即插件文件夹。
  3. `format=FileFormat` 是配置文件类型，枚举 `FileFormat.YAML` 和 `FileFormat.JSON`。
- 声明一个公开的变量来定义一个配置键，声明匿名类来定义层级结构。为变量赋值以为配置键赋默认值。
- 要为配置键添加注释，应使用 `@Comment` 注解。支持多行，使用 `@Comment({"1", "2"})` 的写法。可以注解类、匿名类和变量。
- 默认情况下，变量名按 `驼峰 -> KEBAB` 的形式生成为配置键名，使用 `@NamingStyle` 注解来使用其他命名方法，枚举如下
  - `NamingStyle.Style.KEBAB` 即默认的情况。
  - `NamingStyle.Style.IDENTITY` 有序列表，适用于 `List<String>`。
  - `NamingStyle.Style.LIST` 无序列表，适用于 `List<String>`。


- 支持字段默认值、注释（字段上 `@Comment`）、嵌套类（自动递归绑定）。
- 字段名自动从驼峰转为 kebab-case（如 `someField` → `some-field`）。

#### 示例
```java
@ConfigFile(name="config.yml", path="", format=FileFormat.YAML)
@Comment("主配置文件")
public class MainConfig {
    @Comment("最大玩家数")
    public int maxPlayers = 20;

    public DatabaseConfig database = new DatabaseConfig();

    public static class DatabaseConfig {
        @Comment("数据库地址")
        public String url = "localhost";
    }
}
```

#### 绑定与操作
要将文件类绑定至插件，需要先装载 Linlang 服务。

随后，初始化 LinFile 文件服务，并将文件类绑定至 LinFile 文件服务。假设您的文件类是 `MainConfig`。
```java
private ConfigService configSvc;
private MainConfig config;

configSvc = LinFile.services().config();
config = configSvc.bind(MainConfig.class);
```

到此，您可以直接使用 `config` 访问您的文件类中声明的变量，这些变量的值就是对应键的值。

#### 重载
要热重新载入配置文件，使用
```configSvc.reload(MainConfig.class);```

特别的，您可以启用动态热重载
```java
bootstrap.enableHotReloadFor(Config.class, Addon.class);
```

如果您没有 `Addon` 或 `Config`，传递 `Null`


#### 行为说明
- “**文件值优先，默认值兜底**”：已有配置文件优先读取，未填写项自动写入默认值。
- 注释、嵌套结构与类型自动处理。

---

### 2. 附加文件（AddonService）

#### 注解与用法
- 使用 `@AddonFile` 注解，绑定到 `addons/` 目录下的文件。
- 用法与 `@ConfigFile` 类似，但**语义上用于插件附加文件**（如扩展、模板、第三方配置），**非主逻辑配置**。

#### 示例
```java
@AddonFile("addons/example-addon.yml")
public class ExampleAddonConfig {
    public boolean enabled = true;
    public String description = "示例附加配置";
}
```
```java
ExampleAddonConfig addonCfg = addonSvc.bind(ExampleAddonConfig.class);
addonSvc.save(addonCfg);
```

---

### 3. 语言文件（LangService）
语言文件是

#### 注解与定义
- 使用 `@LangPack` 注解或对象方式定义语言包类。
- 支持嵌套类与字段，自动生成语言文件（如 `lang/zh_CN.yml`）。

#### 示例
```java
@LangPack(locale = "zh_CN", name = "zh_CN", path = "lang")
public class ZhCNLang {
    public String welcome = "欢迎, {player}!";
    public TitleMsg title = new TitleMsg();

    public static class TitleMsg {
        public String main = "&a标题";
        public String sub = "&7副标题";
    }
}
```
```java
ZhCNLang lang = langSvc.bind(ZhCNLang.class);
```

#### 特性与行为
- **已有文件值优先**，未填项写入默认值。
- 支持变量替换（如 `{player}`），`&` 自动转为 `§`（Minecraft 颜色符号）。
- 可结合 `Messenger` 发送消息：
    ```java
    messenger.sendMsg(player, lang.welcome, Map.of("player", player.getName()));
    messenger.sendTitle(player, lang.title.main, lang.title.sub);
    messenger.actionBar(player, "&e操作栏提示");
    ```

---

### 4. 数据层（DataService）

#### 数据库类型
- `DbType` 枚举支持：`H2`, `MYSQL`, `YAML`, `JSON`

#### 数据库配置
```java
public class DbConfig {
    public DbType type = DbType.H2;
    public String url = "jdbc:h2:./data.db";
    public String username = "sa";
    public String password = "";
}
```

#### Repository 用法
- 通用接口：`Repository<T, ID>`
- 支持方法：
    - `save(T entity)`
    - `findById(ID id)`
    - `findAll()`
    - `deleteById(ID id)`
    - `query(QuerySpec spec)`

```java
Repository<PlayerData, UUID> repo = dataSvc.repo(PlayerData.class, UUID.class);
repo.save(new PlayerData(...));
Optional<PlayerData> pd = repo.findById(uuid);
repo.deleteById(uuid);
List<PlayerData> all = repo.findAll();
```

#### 数据库迁移
- `repo.migrate()`：关系型数据库自动新增缺失字段（DDL）；文档型（YAML/JSON）无结构迁移。

#### 文档型数据库（YAML/JSON）
- 适用于简单数据存储，无需建表，无 DDL。
- 文件结构示例（YAML）:
```yaml
players:
  123e4567-e89b-12d3-a456-426614174000:
    name: Steve
    score: 42
```

#### 简单文档 API
```java
YamlDocument doc = dataSvc.loadYamlDoc("data/players.yml");
doc.set("players." + uuid, playerData);
dataSvc.saveYamlDoc(doc);

JsonDocument jdoc = dataSvc.loadJsonDoc("data/items.json");
jdoc.set("items.0", itemData);
dataSvc.saveJsonDoc(jdoc);
```
