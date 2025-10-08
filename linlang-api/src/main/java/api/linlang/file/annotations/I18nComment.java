// called.linlang.annotations.file.I18nComment.java
package api.linlang.file.annotations;

import java.lang.annotation.*;

/**
 * 多语言注释，用于为类或字段提供针对特定语言的注释说明。
 *
 * <p>此注解应用于类和字段。特别的，应用于 @ConfigFile 注解的类，且注册在 ConfigService 服务时无效。</p>
 *
 * @apiNote
 * 提供针对特定语言（locale）的注释说明。例如：
 * <pre><code>
 * {@literal @}I18nComment(locale = "zh_CN", lines = "欢迎语")
 * {@literal @}I18nComment(locale = "en_GB", lines = "Welcome message")
 *  public String welcome;
 * </code></pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
@Repeatable(I18nComments.class)
public @interface I18nComment {
    /**
     * 语言代码，例如 {@code zh_CN} 或 {@code en_GB}。
     */
    String locale();

    /**
     * 注释的具体内容，支持多行。
     */
    String[] lines();
}
