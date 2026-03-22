package io.interactum.validator;

import io.interactum.decoder.InteractumDecoder;

import java.util.*;

public class InteractumValidator {

    /**
     * LSON Validator — performs structural and encoding invariant checks
     * on LSON documents without fully decoding them.
     *
     * Reports:
     *  - Indentation errors (non-multiple of indentSize)
     *  - Missing colons in key-value lines
     *  - Mismatched array lengths
     *  - Mismatched tabular row widths
     *  - Trailing spaces or trailing newline
     *  - Unclosed block arrays (missing ---)
     *  - Invalid escape sequences in quoted strings
     */


        // ─── Diagnostic ───────────────────────────────────────────────────────────

    public enum Severity { ERROR, WARNING, INFO }

        public static class Diagnostic {
            public final Severity severity;
            final int      lineNo;
            final String   message;

            Diagnostic(Severity severity, int lineNo, String message) {
                this.severity = severity;
                this.lineNo   = lineNo;
                this.message  = message;
            }

            @Override public String toString() {
                return "[" + severity + " L" + lineNo + "] " + message;
            }
        }

        public static class ValidationResult {
            public final List<Diagnostic> diagnostics = new ArrayList<>();
            public boolean valid = true;

            void error(int lineNo, String msg) {
                diagnostics.add(new Diagnostic(Severity.ERROR, lineNo, msg));
                valid = false;
            }

            void warn(int lineNo, String msg) {
                diagnostics.add(new Diagnostic(Severity.WARNING, lineNo, msg));
            }

            void info(int lineNo, String msg) {
                diagnostics.add(new Diagnostic(Severity.INFO, lineNo, msg));
            }

            public int errorCount()   { return (int) diagnostics.stream().filter(d -> d.severity == Severity.ERROR).count(); }
            public int warnCount()    { return (int) diagnostics.stream().filter(d -> d.severity == Severity.WARNING).count(); }
        }

        // ─── Entry Point ─────────────────────────────────────────────────────────

        public static ValidationResult validate(String input) {
            ValidationResult result = new ValidationResult();
            String[] rawLines = input.split("\n", -1);

            // Check trailing newline
            if (input.endsWith("\n")) {
                result.warn(rawLines.length, "Document ends with a trailing newline");
            }

            int indentSize = 2;
            int startLine  = 0;

            // Scan for directives
            for (int i = 0; i < rawLines.length; i++) {
                String t = rawLines[i].trim();
                if (t.equals("---")) { startLine = i + 1; break; }
            }

            // Validate each line
            validateLines(rawLines, startLine, indentSize, result);

            return result;
        }

        // ─── Line Validation ──────────────────────────────────────────────────────

        // Tracks whether a given depth is currently inside a tabular array body
        static class ArrayScope {
            final int  depth;        // depth at which rows appear
            final int  fieldCount;   // expected columns per row (0 = not tabular)
            final int  declaredN;    // declared length (-1 = unknown)
            final int  headerLineNo;
            final char delimiter;
            boolean    isTabular;
            int        rowCount;

            ArrayScope(int depth, int fieldCount, int declaredN,
                       int headerLineNo, char delimiter, boolean isTabular) {
                this.depth         = depth;
                this.fieldCount    = fieldCount;
                this.declaredN     = declaredN;
                this.headerLineNo  = headerLineNo;
                this.delimiter     = delimiter;
                this.isTabular     = isTabular;
                this.rowCount      = 0;
            }
        }

        static void validateLines(String[] rawLines, int start,
                                  int indentSize, ValidationResult result) {
            Deque<ArrayScope> scopeStack = new ArrayDeque<>();

            for (int i = start; i < rawLines.length; i++) {
                String raw     = rawLines[i];
                int    lineNo  = i + 1;
                String trimmed = raw.trim();

                // Trailing space check
                if (raw.length() > 0 && raw.charAt(raw.length() - 1) == ' ') {
                    result.warn(lineNo, "Trailing space");
                }

                // Skip blank and comment lines
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("%")) continue;

                // Count leading spaces
                int spaces = 0;
                while (spaces < raw.length() && raw.charAt(spaces) == ' ') spaces++;

                // Tab in indentation
                for (int j = 0; j < spaces; j++) {
                    if (raw.charAt(j) == '\t') {
                        result.error(lineNo, "Tab character used in indentation (only spaces allowed)");
                        break;
                    }
                }

                // Indentation multiple check
                if (spaces % indentSize != 0) {
                    result.error(lineNo, "Indentation " + spaces + " is not a multiple of " + indentSize);
                }

                int    depth   = spaces / indentSize;
                String content = raw.substring(spaces);

                // Pop scopes that are no longer active
                while (!scopeStack.isEmpty() && scopeStack.peek().depth > depth) {
                    ArrayScope closed = scopeStack.pop();
                    // Validate row count if declared
                    if (closed.declaredN >= 0 && closed.rowCount != closed.declaredN) {
                        result.error(closed.headerLineNo,
                                "Declared " + closed.declaredN + " items but counted " + closed.rowCount);
                    }
                }

                // Block close ---
                if (content.equals("---")) {
                    if (!scopeStack.isEmpty() && scopeStack.peek().depth == depth + 1) {
                        ArrayScope closed = scopeStack.pop();
                        if (closed.declaredN >= 0 && closed.rowCount != closed.declaredN) {
                            result.error(closed.headerLineNo,
                                    "Declared " + closed.declaredN + " items but counted " + closed.rowCount);
                        }
                    }
                    continue;
                }

                // Check if this line is a tabular row under an active tabular scope
                if (!scopeStack.isEmpty()) {
                    ArrayScope top = scopeStack.peek();
                    if (top.isTabular && top.depth == depth) {
                        // It's a tabular row — count columns
                        validateQuotedStrings(content, lineNo, result);
                        List<String> cols = splitForCount(content, top.delimiter);
                        top.rowCount++;
                        if (top.fieldCount > 0 && cols.size() != top.fieldCount) {
                            result.error(lineNo,
                                    "Row has " + cols.size() + " columns, expected " + top.fieldCount);
                        }
                        continue;
                    }
                    if (!top.isTabular && top.depth == depth && content.startsWith("- ")) {
                        top.rowCount++;
                        // Don't skip — fall through to validate the item itself
                    }
                }

                // Validate quoted strings in line
                validateQuotedStrings(content, lineNo, result);

                // Array header detection — push new scope
                java.util.regex.Matcher m = InteractumDecoder.ARRAY_HEADER_PATTERN.matcher(content);
                if (m.find()) {
                    validateArrayHeader(content, lineNo, result);

                    // Extract info for scope tracking
                    String lenStr = m.group(1);
                    int declaredN = lenStr.isEmpty() ? -1 : Integer.parseInt(lenStr);
                    String delimStr = m.group(2);
                    char delim = delimStr.isEmpty() ? ',' : delimStr.equals("|") ? '|' : '\t';
                    String afterColon = m.group(3).trim();

                    // Is it tabular? Look for { } segment
                    boolean isTabular = false;
                    int fieldCount    = 0;
                    int closeB        = content.indexOf(']');
                    int openBrace     = content.indexOf('{', closeB);
                    int closeBrace    = content.indexOf('}', openBrace > -1 ? openBrace : 0);
                    if (openBrace > -1 && closeBrace > openBrace) {
                        isTabular = true;
                        String fieldStr = content.substring(openBrace + 1, closeBrace);
                        fieldCount = splitForCount(fieldStr, delim).size();
                    }

                    // Only push scope for block arrays (not inline primitive arrays)
                    if (afterColon.isEmpty() || isTabular) {
                        scopeStack.push(new ArrayScope(
                                depth + 1, fieldCount, declaredN, lineNo, delim, isTabular
                        ));
                    }
                    continue;
                }

                // List item line — skip for colon check
                if (content.startsWith("- ") || content.equals("-")) continue;

                // Key-value line
                int colonIdx = InteractumDecoder.findUnquotedColon(content);
                if (colonIdx < 0) {
                    result.error(lineNo, "Missing colon — expected key: value but got: " + content);
                }
            }

            // Close any remaining open scopes
            while (!scopeStack.isEmpty()) {
                ArrayScope scope = scopeStack.pop();
                if (scope.declaredN >= 0) {
                    result.warn(scope.headerLineNo,
                            "Array block opened at line " + scope.headerLineNo + " was never closed with ---");
                }
            }
        }

        static List<String> splitForCount(String s, char delim) {
            List<String> result   = new ArrayList<>();
            boolean      inQuotes = false;
            int          start    = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') { inQuotes = !inQuotes; continue; }
                if (!inQuotes && c == delim) { result.add(s.substring(start, i)); start = i + 1; }
            }
            result.add(s.substring(start));
            return result;
        }

        static void validateArrayHeader(String content, int lineNo, ValidationResult result) {
            // Check delimiter consistency: delimiter in [] must match delimiter in {}
            java.util.regex.Matcher m = InteractumDecoder.ARRAY_HEADER_PATTERN.matcher(content);
            if (!m.matches()) {
                result.error(lineNo, "Malformed array header: " + content);
                return;
            }

            String delimInBracket = m.group(2); // "" | "|" | "\t"
            int    closeB         = content.indexOf(']');
            int    openBrace      = content.indexOf('{', closeB);
            int    closeBrace     = content.indexOf('}', openBrace > -1 ? openBrace : 0);

            if (openBrace > -1 && closeBrace > openBrace) {
                String fieldStr      = content.substring(openBrace + 1, closeBrace);
                char   expectedDelim = delimInBracket.isEmpty() ? ','
                        : delimInBracket.equals("|") ? '|' : '\t';
                // Check at least one field
                if (fieldStr.trim().isEmpty()) {
                    result.error(lineNo, "Empty field list {} in tabular header");
                }
                // Check field delimiter matches bracket delimiter
                if (expectedDelim != ',' && !fieldStr.contains(String.valueOf(expectedDelim))) {
                    result.warn(lineNo,
                            "Field list may not use the declared delimiter '" + expectedDelim + "'");
                }
            }

            // Length must be non-negative integer
            String lenStr = m.group(1);
            if (!lenStr.isEmpty()) {
                try {
                    int n = Integer.parseInt(lenStr);
                    if (n < 0) result.error(lineNo, "Negative array length: " + n);
                } catch (NumberFormatException e) {
                    result.error(lineNo, "Non-integer array length: " + lenStr);
                }
            }
        }

        static void validateQuotedStrings(String content, int lineNo, ValidationResult result) {
            boolean inQuotes = false;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (inQuotes && c == '\\') {
                    if (i + 1 >= content.length()) {
                        result.error(lineNo, "Unterminated escape sequence at end of line");
                        return;
                    }
                    char esc = content.charAt(++i);
                    switch (esc) {
                        case '"': case '\\': case 'n': case 'r': case 't':
                            break; // valid
                        default:
                            result.error(lineNo, "Invalid escape sequence: \\" + esc);
                    }
                }
            }
            if (inQuotes) {
                result.error(lineNo, "Unterminated quoted string");
            }
        }

}
