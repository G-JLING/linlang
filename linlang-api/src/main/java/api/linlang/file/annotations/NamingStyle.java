package api.linlang.file.annotations;

import java.lang.annotation.*;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/* 将定义的字段变量名生成为键名的方式
 * 默认：驼峰 -> KEBAB
 * 可选: IDENTITY，即列表转有序列表
 * */
@Retention(RUNTIME) @Target(TYPE)
public @interface NamingStyle {
    Style value() default Style.KEBAB;
    enum Style { IDENTITY, KEBAB }
}