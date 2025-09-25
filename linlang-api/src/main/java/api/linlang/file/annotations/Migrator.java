package api.linlang.file.annotations;

import api.linlang.file.doc.MutableDocument;

public interface Migrator {
    int from(); int to();
    void migrate(MutableDocument doc);               // 文档级迁移（键/值重命名等）
}
