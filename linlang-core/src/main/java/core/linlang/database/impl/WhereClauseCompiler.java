package core.linlang.database.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WhereClauseCompiler {
    private static final Pattern COMPARISON = Pattern.compile(
            "\\G\\s*(?:(AND|OR)\\s+)?([A-Za-z_][A-Za-z0-9_$]*)\\s*(=|<>|!=|<=|>=|<|>|LIKE)\\s*\\?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NULL_CHECK = Pattern.compile(
            "\\G\\s*(?:(AND|OR)\\s+)?([A-Za-z_][A-Za-z0-9_$]*)\\s+IS\\s+(NOT\\s+)?NULL",
            Pattern.CASE_INSENSITIVE
    );

    private WhereClauseCompiler() {
    }

    static CompiledWhere compile(String where, int parameterCount, Function<String, String> columnResolver) {
        if (where == null || where.isBlank()) {
            if (parameterCount != 0) {
                throw new IllegalArgumentException("Query parameters require a where clause");
            }
            return new CompiledWhere("", 0);
        }

        int position = 0;
        int placeholders = 0;
        boolean first = true;
        List<String> predicates = new ArrayList<>();
        while (position < where.length()) {
            if (where.substring(position).isBlank()) break;
            Matcher comparison = COMPARISON.matcher(where);
            comparison.region(position, where.length());
            Matcher nullCheck = NULL_CHECK.matcher(where);
            nullCheck.region(position, where.length());

            String connector;
            String field;
            String predicate;
            int end;
            if (comparison.lookingAt()) {
                connector = comparison.group(1);
                field = comparison.group(2);
                predicate = DatabaseDialect.quote(columnResolver.apply(field))
                        + " " + comparison.group(3).toUpperCase(Locale.ROOT) + " ?";
                placeholders++;
                end = comparison.end();
            } else if (nullCheck.lookingAt()) {
                connector = nullCheck.group(1);
                field = nullCheck.group(2);
                predicate = DatabaseDialect.quote(columnResolver.apply(field))
                        + " IS " + (nullCheck.group(3) == null ? "" : "NOT ") + "NULL";
                end = nullCheck.end();
            } else {
                throw new IllegalArgumentException("Unsupported or unsafe where clause near: " + where.substring(position));
            }

            if (first && connector != null || !first && connector == null) {
                throw new IllegalArgumentException("Invalid logical connector in where clause");
            }
            predicates.add((connector == null ? "" : connector.toUpperCase(Locale.ROOT) + " ") + predicate);
            first = false;
            position = end;
        }

        if (!where.substring(position).isBlank()) {
            throw new IllegalArgumentException("Unsupported or unsafe where clause near: " + where.substring(position));
        }
        if (placeholders != parameterCount) {
            throw new IllegalArgumentException(
                    "Where clause expects " + placeholders + " parameters but received " + parameterCount
            );
        }
        return new CompiledWhere(String.join(" ", predicates), placeholders);
    }

    record CompiledWhere(String sql, int placeholders) {
    }
}
