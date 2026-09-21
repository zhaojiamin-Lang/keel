package io.keel.mcp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具返回文本脱敏器（FR-33）：MCP 响应进入图状态 / 模型上下文前，
 * 必须先按规则抹掉身份证 / 手机号 / 银行卡等敏感串。
 * 默认给一组基础正则；业务可注入自定义规则（allowlist 语义，默认拒绝敏感明文）。
 */
public final class ContentRedactor {

    /** 18 位身份证（末位可能为 X） */
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");

    /** 手机号（11 位，1 开头） */
    private static final Pattern MOBILE = Pattern.compile("\\b1[3-9]\\d{9}\\b");

    /** 银行卡：12~19 位连续数字（夹在长数字中的短号需要更严，基础规则先覆盖常见形态） */
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{12,19}\\b");

    private static final String MASK = "[REDACTED]";

    private final List<Rule> rules;

    public ContentRedactor(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static ContentRedactor defaultRules() {
        // 顺序很重要：更具体的身份证先于银行卡，避免被宽规则截断
        return new ContentRedactor(List.of(
                Rule.of(ID_CARD, MASK),
                Rule.of(MOBILE, MASK),
                Rule.of(BANK_CARD, MASK)));
    }

    /**
     * FR-33：按 yml 配置的脱敏模式构建 ContentRedactor。
     *
     * @param mode 脱敏模式（TOKEN / MOBILE / SECRET / ALL / NONE）
     * @return 对应模式的 ContentRedactor
     */
    public static ContentRedactor forMode(RedactionMode mode) {
        return RedactionMode.forMode(mode);
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String output = text;
        for (Rule rule : rules) {
            output = replaceAll(rule.pattern(), output, rule.replacement());
        }
        return output;
    }

    private String replaceAll(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 脱敏规则：命中 pattern 的片段整体替换为 replacement。 */
    public record Rule(Pattern pattern, String replacement) {

        public static Rule of(Pattern pattern, String replacement) {
            return new Rule(pattern, replacement);
        }
    }
}
