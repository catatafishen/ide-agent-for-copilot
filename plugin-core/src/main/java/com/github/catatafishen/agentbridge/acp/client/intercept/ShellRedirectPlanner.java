package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure (Project-free) classifier that maps a tokenized shell command to a
 * {@link RedirectPlan}, or returns {@code null} when no safe MCP equivalent exists.
 *
 * <p><b>Design constraints:</b>
 * <ul>
 *   <li>Conservative — when in doubt, return {@code null} so the command runs in the
 *       visible IDE terminal where the user can see it.</li>
 *   <li>No I/O, no Project, no service lookups — keeps unit tests trivial.</li>
 *   <li>Each redirect must preserve semantics. We accept format drift in the output
 *       (agents adapt) but never accept wrong results.</li>
 * </ul>
 *
 * <p><b>Currently supported:</b>
 * <ul>
 *   <li>{@code cat FILE} → {@code read_file}</li>
 *   <li>{@code head [-n N|--lines=N] FILE} → {@code read_file} with line range</li>
 *   <li>{@code grep -F/-E/-i/-r/-n PATTERN [GLOB]} → {@code search_text}.
 *       Note: plain {@code grep} (BRE regex) is intentionally not intercepted because
 *       BRE differs subtly from Java regex. Use {@code -F} (literal) or {@code -E}
 *       (extended) for explicit semantics.</li>
 *   <li>{@code egrep} ≡ {@code grep -E}, {@code fgrep} ≡ {@code grep -F}</li>
 *   <li>{@code rg [-F] [-i] [-g GLOB|--glob GLOB] PATTERN} → {@code search_text}</li>
 *   <li>{@code git status} → {@code git_status}</li>
 *   <li>{@code git diff [--staged] [--stat]} → {@code git_diff}</li>
 *   <li>{@code git log [--oneline] [-n N]} → {@code git_log}</li>
 *   <li>{@code git branch} → {@code git_branch}</li>
 * </ul>
 *
 * <p><b>Deliberately not intercepted</b> (see commit history for rationale):
 * <ul>
 *   <li>{@code ls} — recursion semantics differ from {@code list_project_files}</li>
 *   <li>{@code find} — requires file-vs-directory disambiguation we cannot do without I/O</li>
 *   <li>{@code tail} — {@code read_file} truncates large files, breaking tail semantics</li>
 *   <li>Plain {@code grep} — BRE regex not equivalent to Java regex</li>
 *   <li>Mutating git operations — must remain visible so {@code GitCommitTool}'s review
 *       gate is not bypassed</li>
 * </ul>
 */
public final class ShellRedirectPlanner {

    private ShellRedirectPlanner() {
    }

    /**
     * Classify the given argv. Returns {@code null} when the command should fall through
     * to the visible terminal.
     */
    public static @Nullable RedirectPlan plan(@NotNull List<String> argv) {
        if (argv.isEmpty()) return null;

        String head = stripPath(argv.getFirst()).toLowerCase(Locale.ROOT);

        return switch (head) {
            case "cat" -> planCat(argv);
            case "head" -> planHead(argv);
            case "grep" -> planGrep(argv, /* defaultRegex = */ null);
            case "egrep" -> planGrep(argv, /* defaultRegex = */ true);
            case "fgrep" -> planGrep(argv, /* defaultRegex = */ false);
            case "rg" -> planRg(argv);
            case "git" -> planGit(argv);
            default -> null;
        };
    }

    private static @NotNull String stripPath(@NotNull String binary) {
        int slash = binary.lastIndexOf('/');
        return (slash >= 0 && slash + 1 < binary.length()) ? binary.substring(slash + 1) : binary;
    }

    // ─── cat ──────────────────────────────────────────────────────────────

    private static @Nullable RedirectPlan planCat(@NotNull List<String> argv) {
        // Only redirect the bare `cat <file>` form. Any flag (-n, -A, ...) means the
        // agent wants formatting we don't reproduce; let it run in the real terminal.
        if (argv.size() != 2) return null;
        String path = argv.get(1);
        if (path.startsWith("-")) return null;

        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        return RedirectPlan.of("read_file", args);
    }

    // ─── head ─────────────────────────────────────────────────────────────

    private static @Nullable RedirectPlan planHead(@NotNull List<String> argv) {
        int n = 10;
        String path = null;
        boolean afterDoubleDash = false;

        int i = 1;
        while (i < argv.size()) {
            String tok = argv.get(i);
            if (afterDoubleDash) {
                if (path != null) return null;
                path = tok;
                i++;
                continue;
            }
            if (tok.equals("--")) {
                afterDoubleDash = true;
                i++;
                continue;
            }
            if (tok.equals("-n") || tok.equals("--lines")) {
                if (i + 1 >= argv.size()) return null;
                Integer parsed = parsePositiveInt(argv.get(i + 1));
                if (parsed == null) return null;
                n = parsed;
                i += 2;
                continue;
            }
            if (tok.startsWith("--lines=")) {
                Integer parsed = parsePositiveInt(tok.substring("--lines=".length()));
                if (parsed == null) return null;
                n = parsed;
                i++;
                continue;
            }
            if (tok.startsWith("-n") && tok.length() > 2) {
                Integer parsed = parsePositiveInt(tok.substring(2));
                if (parsed == null) return null;
                n = parsed;
                i++;
                continue;
            }
            if (tok.startsWith("-") && tok.length() > 1) {
                return null; // unknown flag
            }
            if (path != null) return null; // multiple files unsupported
            path = tok;
            i++;
        }

        if (path == null) return null;

        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        args.addProperty("start_line", 1);
        args.addProperty("end_line", n);
        return RedirectPlan.of("read_file", args);
    }

    // ─── grep family ──────────────────────────────────────────────────────

    /**
     * @param defaultRegex {@code null} to refuse interception when the form is ambiguous
     *                     (plain {@code grep}), {@code true} for {@code egrep},
     *                     {@code false} for {@code fgrep}. Plain {@code grep} would map
     *                     to BRE which is not equivalent to Java regex.
     */
    private static @Nullable RedirectPlan planGrep(@NotNull List<String> argv, @Nullable Boolean defaultRegex) {
        boolean caseSensitive = true;
        Boolean regexExplicit = null;
        String fileGlob = null;
        List<String> positional = new ArrayList<>();
        boolean afterDoubleDash = false;

        int i = 1;
        while (i < argv.size()) {
            String tok = argv.get(i);
            if (afterDoubleDash) {
                positional.add(tok);
                i++;
                continue;
            }
            if (tok.equals("--")) {
                afterDoubleDash = true;
                i++;
                continue;
            }
            if (tok.startsWith("-") && tok.length() > 1) {
                // Bundled short flags like -rin
                for (int j = 1; j < tok.length(); j++) {
                    char c = tok.charAt(j);
                    switch (c) {
                        case 'i' -> caseSensitive = false;
                        case 'E' -> regexExplicit = true;
                        case 'F' -> regexExplicit = false;
                        case 'r', 'R', 'n', 'H', 'I' -> {
                            // -r/-R: recursion is implicit in search_text
                            // -n: search_text always returns line numbers
                            // -H: search_text always shows file names
                            // -I: search_text already skips binary files
                        }
                        default -> {
                            return null; // unknown flag
                        }
                    }
                }
                i++;
                continue;
            }
            positional.add(tok);
            i++;
        }

        if (positional.isEmpty() || positional.size() > 2) return null;
        String pattern = positional.get(0);
        // Reject leading-dash patterns unless escaped via `--` separator
        if (pattern.startsWith("-") && !afterDoubleDash) return null;

        if (positional.size() == 2) {
            String secondArg = positional.get(1);
            // Only accept when the second positional is clearly a glob — directories or
            // single files don't translate to search_text's file_pattern (filename glob).
            if (!secondArg.contains("*") && !secondArg.contains("?")) return null;
            fileGlob = secondArg;
        }

        boolean regex;
        if (regexExplicit != null) {
            regex = regexExplicit;
        } else if (defaultRegex != null) {
            regex = defaultRegex;
        } else {
            // Plain `grep` without -F or -E — BRE semantics, refuse.
            return null;
        }

        JsonObject args = new JsonObject();
        args.addProperty("query", pattern);
        args.addProperty("regex", regex);
        args.addProperty("case_sensitive", caseSensitive);
        if (fileGlob != null) args.addProperty("file_pattern", fileGlob);

        return new RedirectPlan(
            "search_text",
            args,
            ShellRedirectPlanner::stripSearchTextHeader,
            output -> output.startsWith("No matches found") ? 1 : 0
        );
    }

    private static @Nullable RedirectPlan planRg(@NotNull List<String> argv) {
        boolean caseSensitive = true;
        boolean regex = true; // ripgrep default
        String fileGlob = null;
        List<String> positional = new ArrayList<>();
        boolean afterDoubleDash = false;

        int i = 1;
        while (i < argv.size()) {
            String tok = argv.get(i);
            if (afterDoubleDash) {
                positional.add(tok);
                i++;
                continue;
            }
            if (tok.equals("--")) {
                afterDoubleDash = true;
                i++;
                continue;
            }
            if (tok.equals("-g") || tok.equals("--glob")) {
                if (i + 1 >= argv.size()) return null;
                fileGlob = argv.get(i + 1);
                i += 2;
                continue;
            }
            if (tok.startsWith("--glob=")) {
                fileGlob = tok.substring("--glob=".length());
                i++;
                continue;
            }
            if (tok.startsWith("-") && tok.length() > 1) {
                for (int j = 1; j < tok.length(); j++) {
                    char c = tok.charAt(j);
                    switch (c) {
                        case 'i' -> caseSensitive = false;
                        case 'F' -> regex = false;
                        case 'n', 'H' -> {
                            // search_text already includes line numbers and file names
                        }
                        default -> {
                            return null; // unknown flag
                        }
                    }
                }
                i++;
                continue;
            }
            positional.add(tok);
            i++;
        }

        // ripgrep can take an optional path arg, but search_text doesn't take a directory.
        // Accept only the single-pattern form to stay safe.
        if (positional.size() != 1) return null;
        String pattern = positional.get(0);
        if (pattern.startsWith("-") && !afterDoubleDash) return null;

        JsonObject args = new JsonObject();
        args.addProperty("query", pattern);
        args.addProperty("regex", regex);
        args.addProperty("case_sensitive", caseSensitive);
        if (fileGlob != null) args.addProperty("file_pattern", fileGlob);

        return new RedirectPlan(
            "search_text",
            args,
            ShellRedirectPlanner::stripSearchTextHeader,
            output -> output.startsWith("No matches found") ? 1 : 0
        );
    }

    /**
     * Strip the leading {@code "N matches:"} line that {@code search_text} emits — the
     * raw match lines below it are already in {@code path:line:text} form, which is
     * close enough to grep/rg output for agents that parse it.
     */
    static @NotNull String stripSearchTextHeader(@NotNull String result) {
        int newline = result.indexOf('\n');
        if (newline < 0) return result;
        String first = result.substring(0, newline);
        if (first.matches("\\d+ match(?:es)?:")) {
            return result.substring(newline + 1);
        }
        return result;
    }

    // ─── git read-only subcommands ────────────────────────────────────────

    private static @Nullable RedirectPlan planGit(@NotNull List<String> argv) {
        if (argv.size() < 2) return null;
        String sub = argv.get(1).toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "status" -> argv.size() == 2 ? RedirectPlan.of("git_status", new JsonObject()) : null;
            case "diff" -> planGitDiff(argv);
            case "log" -> planGitLog(argv);
            case "branch" -> argv.size() == 2 ? RedirectPlan.of("git_branch", new JsonObject()) : null;
            default -> null;
        };
    }

    private static @Nullable RedirectPlan planGitDiff(@NotNull List<String> argv) {
        boolean staged = false;
        boolean statOnly = false;
        for (int i = 2; i < argv.size(); i++) {
            String tok = argv.get(i);
            switch (tok) {
                case "--staged", "--cached" -> staged = true;
                case "--stat" -> statOnly = true;
                default -> {
                    return null; // unknown / positional path arg / commit ref — fall through
                }
            }
        }
        JsonObject args = new JsonObject();
        if (staged) args.addProperty("staged", true);
        if (statOnly) args.addProperty("stat_only", true);
        return RedirectPlan.of("git_diff", args);
    }

    private static @Nullable RedirectPlan planGitLog(@NotNull List<String> argv) {
        JsonObject args = new JsonObject();
        int i = 2;
        while (i < argv.size()) {
            String tok = argv.get(i);
            if (tok.equals("--oneline")) {
                args.addProperty("format", "oneline");
                i++;
                continue;
            }
            if (tok.equals("-n") || tok.equals("--max-count")) {
                if (i + 1 >= argv.size()) return null;
                Integer parsed = parsePositiveInt(argv.get(i + 1));
                if (parsed == null) return null;
                args.addProperty("max_count", parsed);
                i += 2;
                continue;
            }
            if (tok.startsWith("-n") && tok.length() > 2) {
                Integer parsed = parsePositiveInt(tok.substring(2));
                if (parsed == null) return null;
                args.addProperty("max_count", parsed);
                i++;
                continue;
            }
            if (tok.startsWith("--max-count=")) {
                Integer parsed = parsePositiveInt(tok.substring("--max-count=".length()));
                if (parsed == null) return null;
                args.addProperty("max_count", parsed);
                i++;
                continue;
            }
            return null; // unknown flag / commit ref / path filter
        }
        return RedirectPlan.of("git_log", args);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static @Nullable Integer parsePositiveInt(@NotNull String s) {
        try {
            int n = Integer.parseInt(s);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
