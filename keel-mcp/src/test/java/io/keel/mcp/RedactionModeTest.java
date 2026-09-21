package io.keel.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FR-33：yml 级可配置脱敏模式测试。
 * 各模式只命中对应规则，不误杀。
 */
class RedactionModeTest {

    @Test
    void tokenModeRedactsTokenJsonFieldOnly() {
        ContentRedactor redactor = RedactionMode.forMode(RedactionMode.TOKEN);
        String input = "{\"token\": \"abc123\", \"phone\": \"13800138000\"}";

        String result = redactor.redact(input);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("abc123");
        // TOKEN 模式不脱敏手机号
        assertThat(result).contains("13800138000");
    }

    @Test
    void mobileModeRedactsPhoneAndIdCardOnly() {
        ContentRedactor redactor = RedactionMode.forMode(RedactionMode.MOBILE);
        String input = "联系手机 13800138000，身份证 110101199001011234，token: abc";

        String result = redactor.redact(input);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("13800138000");
        assertThat(result).doesNotContain("110101199001011234");
        // MOBILE 模式不脱敏 token
        assertThat(result).contains("abc");
    }

    @Test
    void secretModeRedactsPasswordAndBearer() {
        ContentRedactor redactor = RedactionMode.forMode(RedactionMode.SECRET);
        String input = "{\"password\": \"secret123\", \"auth\": \"Bearer xyz789\"}";

        String result = redactor.redact(input);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("secret123");
        assertThat(result).doesNotContain("xyz789");
    }

    @Test
    void allModeRedactsEverything() {
        ContentRedactor redactor = RedactionMode.forMode(RedactionMode.ALL);
        String input = "{\"token\": \"abc\", \"password\": \"pwd\", \"phone\": \"13800138000\"}";

        String result = redactor.redact(input);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("abc");
        assertThat(result).doesNotContain("pwd");
        assertThat(result).doesNotContain("13800138000");
    }

    @Test
    void noneModeKeepsEverything() {
        ContentRedactor redactor = RedactionMode.forMode(RedactionMode.NONE);
        String input = "{\"token\": \"abc\", \"phone\": \"13800138000\"}";

        String result = redactor.redact(input);

        assertThat(result).isEqualTo(input);
    }
}