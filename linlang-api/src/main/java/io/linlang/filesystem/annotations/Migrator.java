package io.linlang.filesystem.annotations;

import io.linlang.filesystem.doc.MutableDocument;

public interface Migrator {
    int from(); int to();
    void migrate(MutableDocument doc);               // 文档级迁移（键/值重命名等）
}
