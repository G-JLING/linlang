package api.linlang.file.annotations;

import java.lang.annotation.*;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 定义类中字段名称到配置文件键名的命名转换方式。
 *
 * <p>此注解应用于类或字段。</p>
 *
 * <p>支持三种命名风格：</p>
 * <ul>
 *   <li>{@link Style#KEBAB}：驼峰转 KEBAB（中划线连接）。例如 {@code myValue → my-value}</li>
 *   <li>{@link Style#IDENTITY}：用于列表，将 List 根据 PUT 次序转化为有序列表。</li>
 *   <li>{@link Style#LIST}：用于列表，将 List 转化为无序列表。</li>
 * </ul>
 *
 * @apiNote
 * 用于控制配置文件或语言文件中键名的生成方式。
 */
@Retention(RUNTIME) @Target(TYPE)
public @interface NamingStyle {
    Style value() default Style.KEBAB;
    enum Style { IDENTITY, KEBAB, LIST }
}