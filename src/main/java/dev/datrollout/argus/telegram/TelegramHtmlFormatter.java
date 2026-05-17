package dev.datrollout.argus.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts common LLM markdown to Telegram HTML ({@code parse_mode=HTML}).
 */
final class TelegramHtmlFormatter {

    private static final Pattern FENCED_CODE = Pattern.compile("```([\\w-]*)?\\r?\\n?([\\s\\S]*?)```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern HEADER = Pattern.compile("(?m)^#{1,6}\\s+(.+)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");

    private TelegramHtmlFormatter() {
    }

    static String format(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        List<String> fencedBlocks = new ArrayList<>();
        String withoutFenced = extractFencedCode(markdown, fencedBlocks);

        List<String> inlineBlocks = new ArrayList<>();
        String withoutInline = extractInlineCode(withoutFenced, inlineBlocks);

        String escaped = escapeHtml(withoutInline);
        String formatted = applyInlineFormatting(escaped);
        formatted = restorePlaceholders(formatted, inlineBlocks, "<code>%s</code>");
        return restorePlaceholders(formatted, fencedBlocks, "<pre><code>%s</code></pre>");
    }

    private static String extractFencedCode(String text, List<String> blocks) {
        Matcher matcher = FENCED_CODE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            blocks.add(escapeHtml(matcher.group(2).stripTrailing()));
            matcher.appendReplacement(result, placeholder(blocks.size() - 1));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String extractInlineCode(String text, List<String> blocks) {
        Matcher matcher = INLINE_CODE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            blocks.add(escapeHtml(matcher.group(1)));
            matcher.appendReplacement(result, placeholder(blocks.size() - 1));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String applyInlineFormatting(String text) {
        text = HEADER.matcher(text).replaceAll("<b>$1</b>");
        text = BOLD.matcher(text).replaceAll(match -> {
            String content = match.group(1) != null ? match.group(1) : match.group(2);
            return "<b>" + content + "</b>";
        });
        return ITALIC.matcher(text).replaceAll("<i>$1</i>");
    }

    private static String restorePlaceholders(String text, List<String> blocks, String template) {
        for (int i = 0; i < blocks.size(); i++) {
            text = text.replace(placeholder(i), template.formatted(blocks.get(i)));
        }
        return text;
    }

    private static String placeholder(int index) {
        return "\u0000CODE" + index + "\u0000";
    }

    private static String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
