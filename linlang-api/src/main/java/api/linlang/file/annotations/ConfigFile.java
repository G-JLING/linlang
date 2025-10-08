package api.linlang.file.annotations;

import api.linlang.file.types.FileType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 声明当前类为 LinFile 配置文件服务中定义配置文件的类
 *
 * <p>此注解应用于类。</p>
 *
 * @apiNote
 * 指定配置文件的文件名、存放路径以及格式。
 * 例如：
 * <pre><code>
 * {@literal @}ConfigFile(name = "config", path = "", format = FileType.YAML)
 *  public class MyConfig { ... }
 * </code></pre>
 */
@Retention(RUNTIME)
@Target({TYPE})
public @interface ConfigFile {

    /**
     * 文件名（不带扩展名），例如 {@code "config"}。
     * 默认为 config
     */
    String name() default "config";

    /**
     * 配置文件相对路径（相对于插件根目录）。
     * 默认为空，即插件根目录}。
     */
    String path() default "";

    /**
     * 配置文件格式。
     * 默认为 {@link FileType#YAML}。
     */
    FileType format() default FileType.YAML;
}