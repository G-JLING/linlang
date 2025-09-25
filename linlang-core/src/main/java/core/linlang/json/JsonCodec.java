package core.linlang.json;

// linlang-core/src/main/java/io/linlang/file/json/JsonCodec.java

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonCodec {
    private static final ObjectMapper M = new ObjectMapper();

    public static Map<String,Object> load(String s){
        try {
            if (s == null || s.isEmpty()) return new LinkedHashMap<>();
            return M.readValue(s, LinkedHashMap.class);
        } catch (Exception e){ throw new RuntimeException(e); }
    }
    public static String dump(Map<String,Object> m){
        try { return M.writerWithDefaultPrettyPrinter().writeValueAsString(m); }
        catch (Exception e){ throw new RuntimeException(e); }
    }
}