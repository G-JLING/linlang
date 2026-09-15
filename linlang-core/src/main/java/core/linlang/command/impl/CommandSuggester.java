package core.linlang.command.impl;

import core.linlang.command.model.Model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;

/**
 * 为未知命令生成保守的字面量修正建议。
 */
final class CommandSuggester {

    private CommandSuggester() {
    }

    static String suggest(String label, String[] args, List<Model.Node> nodes,
                          Predicate<Model.Node> available,
                          ToIntBiFunction<Model.Node, String[]> acceptedArguments) {
        Set<String> suggestions = new LinkedHashSet<>();

        for (Model.Node node : nodes) {
            if (!available.test(node)) continue;
            Suggestion candidate = evaluate(label, args, node, acceptedArguments);
            if (candidate == null) continue;
            suggestions.add(candidate.command);
        }
        return suggestions.size() == 1 ? suggestions.iterator().next() : null;
    }

    private static Suggestion evaluate(String label, String[] args, Model.Node node,
                                       ToIntBiFunction<Model.Node, String[]> acceptedArguments) {
        if (node.literals == null || node.literals.size() < 2) return null;
        if (!node.literals.get(0).equalsIgnoreCase(label)) return null;

        int literalCount = node.literals.size() - 1;
        if (args.length < literalCount) return null;

        int mismatch = -1;
        for (int index = 0; index < literalCount; index++) {
            String actual = args[index];
            String expected = node.literals.get(index + 1);
            if (expected.equalsIgnoreCase(actual)) continue;
            if (mismatch >= 0 || !matchesBranch(actual, expected)) return null;
            mismatch = index;
        }

        String[] suppliedArguments = java.util.Arrays.copyOfRange(args, literalCount, args.length);
        int accepted = acceptedArguments.applyAsInt(node, suppliedArguments);
        if (accepted < 0 || accepted > suppliedArguments.length) return null;
        if (mismatch < 0 && accepted == suppliedArguments.length) return null;

        StringBuilder command = new StringBuilder("/").append(label);
        for (int index = 1; index < node.literals.size(); index++) {
            command.append(' ').append(node.literals.get(index));
        }
        for (int index = 0; index < accepted; index++) {
            command.append(' ').append(suppliedArguments[index]);
        }
        return new Suggestion(command.toString());
    }

    private static boolean matchesBranch(String actual, String expected) {
        if (actual == null || expected == null) return false;
        if (!actual.isEmpty() && expected.regionMatches(true, 0, actual, 0, actual.length())) {
            return true;
        }
        int length = Math.max(actual.length(), expected.length());
        if (Math.min(actual.length(), expected.length()) < 3) return false;
        int distance = damerauLevenshtein(
                actual.toLowerCase(Locale.ROOT),
                expected.toLowerCase(Locale.ROOT)
        );
        int allowed = length >= 6 ? 2 : 1;
        return distance > 0 && distance <= allowed && (double) distance / length <= 0.34;
    }

    /**
     * 相邻字符交换按一次编辑计算。
     */
    private static int damerauLevenshtein(String left, String right) {
        int[][] distance = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) distance[i][0] = i;
        for (int j = 0; j <= right.length(); j++) distance[0][j] = j;
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + cost
                );
                if (i > 1 && j > 1
                        && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    distance[i][j] = Math.min(distance[i][j], distance[i - 2][j - 2] + 1);
                }
            }
        }
        return distance[left.length()][right.length()];
    }

    private record Suggestion(String command) {
    }
}
