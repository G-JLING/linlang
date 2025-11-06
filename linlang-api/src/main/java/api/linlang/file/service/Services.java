package api.linlang.file.service;

import api.linlang.file.database.services.DataService;

/**
 *
 */
public interface Services {
    ConfigService config();
    LangService lang();
    DataService data();
}