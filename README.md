# Linlang

Linlang（琳琅）是一个适用于 Bukkit/Paper 插件开发的服务框架，提供多种统一服务。

- 文件与语言
- 数据库
- 命令
- 审计与日志
- 容器 GUI

当前仓库为 Linlang Runtime 仓库。要使用琳琅开发插件，您应该使用 [linlang-api](https://github.com/G-JLING/linlang-api)，但是在服务器中安装当前仓库的构建。

> 项目仍在开发中。

## 使用

在 `Release` 中下载 `linlang-runtime-bukkit-X.X.X.X.jar`，然后将其放入服务器的 `plugins` 目录。使用 Linlang 的插件还需要在 `plugin.yml` 中声明依赖：

```yaml
depend:
  - LinlangRuntimeBukkit
```

插件以 `provided` 方式依赖对应版本的 API：

```xml
<dependency>
    <groupId>me.jling</groupId>
    <artifactId>linlang-api</artifactId>
    <version>2.2.1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

在插件主类中初始化并使用 Linlang：

```java
import api.linlang.runtime.Lin;
import api.linlang.runtime.Linlang;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {

    private Linlang lin;

    @Override
    public void onEnable() {
        lin = Lin.init(this);
    }

    @Override
    public void onDisable() {
        if (lin != null) {
            lin.close();
        }
    }
}
```

要了解如何使用 Linlang，请访问 [jling.me/linlang](https://jling.me/linlang)。

