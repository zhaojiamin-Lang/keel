package io.keel.mcp.auth;

/**
 * FR-31：per-call 凭证 ThreadLocal 持有器。
 *
 * <p>{@link io.keel.mcp.gateway.SdkMcpToolGateway} 在调用前把解析到的凭证放入
 * ThreadLocal，SSE transport 的 customizeRequest 回调从中读取并注入
 * Authorization header，调用完成清除。</p>
 *
 * <p>使用 ThreadLocal 保证多线程下凭证不串号——每个请求线程独立持有。</p>
 */
public final class PerCallCredentialContext {

    private static final ThreadLocal<String> CREDENTIAL_HOLDER = new ThreadLocal<>();

    private PerCallCredentialContext() {
    }

    /** 设置当前线程的 per-call 凭证（Bearer token 值，不含 "Bearer " 前缀） */
    public static void set(String credential) {
        CREDENTIAL_HOLDER.set(credential);
    }

    /** 获取当前线程的 per-call 凭证，无则返回 null */
    public static String get() {
        return CREDENTIAL_HOLDER.get();
    }

    /** 清除当前线程的 per-call 凭证——必须在 finally 块调用，防止泄漏 */
    public static void clear() {
        CREDENTIAL_HOLDER.remove();
    }

    /** 当前线程是否有 per-call 凭证 */
    public static boolean hasCredential() {
        return CREDENTIAL_HOLDER.get() != null;
    }
}