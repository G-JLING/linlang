package api.linlang.file.service;

import api.linlang.database.services.DataService;

/**
 *
 */
public interface Services {
    ConfigService config();
    LangService lang();
    DataService data();
}