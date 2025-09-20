// linlang-core/src/main/java/io/linlang/filesystem/runtime/PathResolver.java
package io.linlang.filesystem.runtime;

import java.nio.file.Path;

/** 由适配层提供 Bukkit dataFolder。非 Bukkit 环境可用默认实现。 */
public interface PathResolver {
    Path root();                // dataFolder
    default Path sub(String first, String... more){ return root().resolve(Path.of(first, more)); }
}