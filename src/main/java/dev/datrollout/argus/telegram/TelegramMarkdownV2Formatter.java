package dev.datrollout.argus.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts standard LLM markdown output to Telegram MarkdownV2 format.
 *
 * <p>MarkdownV2 rules:
 * <ul>
 *   <li>All special chars {@code _ * [ ] ( ) ~ ` > # + - = | { } . ! \} must be escaped
 *       with a backslash in plain text.</li>
 *   <li>Inside {@code code} / {@code pre} spans: only {@code `} and {@code \} need escaping.</li>
 *   <li>Inside inline URLs: only {@code )} and {@code \} need escaping.</li>
 * </ul>
 *
 * <p>Conversion map from standard markdown:
 * <ul>
 *   <li>{@code **text**} / {@code __text__} → {@code *text*} (bold)</li>
 *   <li>{@code *text*} / {@code _text_}     → {@code _text_} (italic)</li>
 *   <li>{@code # Header}                    → {@code *Header*} (bold line, no native headers in MarkdownV2)</li>
 *   <li>{@code `code`}                      → {@code `code`}</li>
 *   <li>{@code ```lang\ncode\n```}           → {@code ```lang\ncode\n```}</li>
 *   <li>{@code [text](url)}                 → {@code [text](url)}</li>
 * </ul>
 */
final class TelegramMarkdownV2Formatter {

    // Every char listed in Telegram MarkdownV2 spec that must be escaped in plain text.
    private static final String SPECIAL_CHARS = "_*[]()~`>#+-=|{}.!\\";

    private static final Pattern FENCED_CODE =
            Pattern.compile("```([\\w.-]*)\\r?\\n?([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern INLINE_CODE =
            Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD =
            Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__", Pattern.DOTALL);
    private static final Pattern ITALIC =
            // single * or _ not preceded/followed by another of the same char
            Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)" +
                    "|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", Pattern.DOTALL);
    private static final Pattern HEADER =
            Pattern.compile("(?m)^#{1,6}[ \\t]+(.+)$");
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");

    private TelegramMarkdownV2Formatter() {
    }

    static String format(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        List<String> slots = new ArrayList<>();
        String text = markdown;

        // Order matters: most-specific first, so inner patterns aren't re-processed.
        text = extract(text, FENCED_CODE, slots, m -> {
            String lang = m.group(1) == null ? "" : m.group(1).trim();
            String code = escapeCode(m.group(2).stripTrailing());
            return "```" + lang + "\n" + code + "\n```";
        });

        text = extract(text, INLINE_CODE, slots, m ->
                "`" + escapeCode(m.group(1)) + "`"
        );

        text = extract(text, BOLD, slots, m -> {
            String content = m.group(1) != null ? m.group(1) : m.group(2);
            return "*" + escapePlain(content.strip()) + "*";
        });

        text = extract(text, ITALIC, slots, m -> {
            String content = m.group(1) != null ? m.group(1) : m.group(2);
            return "_" + escapePlain(content.strip()) + "_";
        });

        text = extract(text, HEADER, slots, m ->
                "*" + escapePlain(m.group(1).strip()) + "*"
        );

        text = extract(text, LINK, slots, m ->
                "[" + escapePlain(m.group(1)) + "](" + escapeUrl(m.group(2)) + ")"
        );

        // Escape all MarkdownV2 special chars in what remains (plain text only).
        text = escapePlain(text);

        // Restore formatted slots — these are already valid MarkdownV2 fragments.
        for (int i = 0; i < slots.size(); i++) {
            text = text.replace(slot(i), slots.get(i));
        }

        return text;
    }

    private static String extract(String text, Pattern pattern, List<String> slots,
                                   Function<Matcher, String> converter) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            slots.add(converter.apply(m));
            m.appendReplacement(sb, Matcher.quoteReplacement(slot(slots.size() - 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Escapes all MarkdownV2 special characters in plain prose text. */
    static String escapePlain(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            if (SPECIAL_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Inside {@code code} / {@code pre}: only backtick and backslash require escaping. */
    private static String escapeCode(String text) {
        return text.replace("\\", "\\\\").replace("`", "\\`");
    }

    /** Inside inline URLs: only {@code )} and {@code \} require escaping. */
    private static String escapeUrl(String url) {
        return url.replace("\\", "\\\\").replace(")", "\\)");
    }

    /** Null-byte slot marker — none of these chars are in SPECIAL_CHARS, so they survive escapePlain. */
    private static String slot(int index) {
        return "\u0000SLOT" + index + "\u0000";
    }
}
