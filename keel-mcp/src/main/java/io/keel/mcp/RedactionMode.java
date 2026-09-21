package io.keel.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FR-33：yml 级可配置脱敏模式。每种模式对应一组脱敏规则，
 * 业务通过 keel.tools.redact-mode 选择启用哪些规则。
 *
 * <ul>
 *   <li>{@link #TOKEN}：JSON 中 token/apiKey/authorization 字段值；</li>
 *   <li>{@link #MOBILE}：手机号（11 位）+ 身份证（18 位）；</li>
 *   <li>{@link #SECRET}：密钥模式——password/secret/credential 字段值 + Bearer token 串；</li>
 *   <li>{@link #ALL}：上述全部（默认，等价旧 redact=true）；</li>
 *   <li>{@link #NONE}：关闭脱敏（等价旧 redact=false）。</li>
 * </ul>
 */
public enum RedactionMode {

    /** JSON 中 token/apiKey/authorization 字段值 */
    TOKEN,

    /** 手机号（11 位）+ 身份证（18 位） */
    MOBILE,

    /** 密钥模式——password/secret/credential 字段值 + Bearer token 串 */
    SECRET,

    /** 上述全部（默认，等价旧 redact=true） */
    ALL,

    /** 关闭脱敏（等价旧 redact=false） */
    NONE;

    private static final String MASK = "[REDACTED]";

    /** JSON 中 token/apiKey/authorization 字段值（与 LangfuseTracePort 的 SENSITIVE_JSON_FIELD 对齐） */
    static final Pattern TOKEN_JSON_FIELD = Pattern.compile(
            "(\"(?:[^\"]*(?:token|api[_-]?key|authorization)[^\"]*)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);

    /** JSON 中 password/secret/credential 字段值 */
    static final Pattern SECRET_JSON_FIELD = Pattern.compile(
            "(\"(?:[^\"]*(?:password|passwd|secret|credential)[^\"]*)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);

    /** Bearer token 串（Authorization: Bearer xxx） */
    static final Pattern BEARER_TOKEN = Pattern.compile(
            "(Bearer\\s+)([A-Za-z0-9._~+/=-]+)",
            Pattern.CASE_INSENSITIVE);

    /** 18 位身份证（末位可能为 X） */
    static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");

    /** 手机号（11 位，1 开头） */
    static final Pattern MOBILE_NUMBER = Pattern.compile("\\b1[3-9]\\d{9}\\b");

    /**
     * 按模式构建脱敏规则列表。
     *
     * @return 该模式对应的脱敏规则列表，{@link #NONE} 返回空列表
     */
    public List<ContentRedactor.Rule> buildRules() {
        if (this == NONE) {
            return List.of();
        }
        List<ContentRedactor.Rule> rules = new ArrayList<>();
        if (this == TOKEN || this == ALL) {
            rules.add(ContentRedactor.Rule.of(TOKEN_JSON_FIELD, "$1" + MASK + "$3"));
        }
        if (this == SECRET || this == ALL) {
            rules.add(ContentRedactor.Rule.of(SECRET_JSON_FIELD, "$1" + MASK + "$3"));
            rules.add(ContentRedactor.Rule.of(BEARER_TOKEN, "$1" + MASK));
        }
        if (this == MOBILE || this == ALL) {
            // 顺序很重要：更具体的身份证先于银行卡，避免被宽规则截断
            rules.add(ContentRedactor.Rule.of(ID_CARD, MASK));
            rules.add(ContentRedactor.Rule.of(MOBILE_NUMBER, MASK));
        }
        return List.copyOf(rules);
    }

    /**
     * 按模式构建 {@link ContentRedactor}。
     *
     * @param mode 脱敏模式
     * @return 对应模式的 ContentRedactor
     */
    public static ContentRedactor forMode(RedactionMode mode) {
        if (mode == null) {
            return new ContentRedactor(List.of());
        }
        return new ContentRedactor(mode.buildRules());
    }
}