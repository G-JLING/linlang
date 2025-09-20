package io.linlang.filesystem.annotations;

import io.linlang.filesystem.types.FileFormat;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/* 语言包声明，默认路径 "lang" */
@Retention(RUNTIME) @Target(TYPE)
public @interface LangPack {
    String locale();                    // e.g. "zh_CN", "en_US"
    String name() default "";           // 文件名，默认=locale
    String path() default "lang";       // 目录
    FileFormat format() default FileFormat.YAML;
}