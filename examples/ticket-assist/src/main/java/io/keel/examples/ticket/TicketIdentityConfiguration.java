package io.keel.examples.ticket;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.client.PrincipalResolver;
import io.keel.core.KeelPrincipal;
import io.keel.starter.KeelProperties;

/**
 * 示例的身份接入（FR-60）：演示「多租户怎么接」。
 *
 * <p>真实项目应从 Spring Security 的 {@code SecurityContextHolder} 取身份
 * （认证主体 + 租户 claim + 资源权限）；示例为了不引入安全框架，改从请求头读，
 * <b>但解析路径与语义完全一致</b>——都是「每请求解析」，不是启动时固定。</p>
 *
 * <p>对照改造前的写法：身份曾是 {@code TicketController} 里的
 * {@code static final KeelPrincipal PRINCIPAL = new KeelPrincipal("tenant-demo", ...)}，
 * 所有请求共用一个租户。那在多租户下是真实的数据串号缺陷（NFR-02 P0），
 * 不是风格问题——所以本次把它从 Controller 里彻底移出来。</p>
 *
 * <p>请求头缺省时落回 {@code keel.principal.*} 配置（见 application.yml），
 * 保证 curl 直接调也能跑通。</p>
 */
@Configuration
public class TicketIdentityConfiguration {

    static final String HEADER_TENANT_ID = "X-Tenant-Id";
    static final String HEADER_USER_ID = "X-User-Id";
    static final String HEADER_RESOURCE_IDS = "X-Resource-Ids";

    @Bean
    public PrincipalResolver ticketPrincipalResolver(ObjectProvider<KeelProperties> keelProperties) {
        return () -> {
            // 默认值来自 keel.principal.*，单一来源，不在示例里再硬编码一份
            KeelProperties.Principal defaults = defaultPrincipal(keelProperties);
            String tenantId = headerOrDefault(HEADER_TENANT_ID, defaults.getTenantId());
            String subjectId = headerOrDefault(HEADER_USER_ID, defaults.getSubjectId());
            String resourceHeader = readHeader(HEADER_RESOURCE_IDS);
            List<String> resourceIds = (resourceHeader == null || resourceHeader.isBlank())
                    ? defaults.getResourceIds()
                    : List.of(resourceHeader.split(","));
            return new KeelPrincipal(tenantId, subjectId, resourceIds);
        };
    }

    private static KeelProperties.Principal defaultPrincipal(ObjectProvider<KeelProperties> provider) {
        KeelProperties properties = provider.getIfAvailable();
        // KeelProperties 由 starter 的 @EnableConfigurationProperties 注册；
        // 万一本示例脱离 starter 独立起，也给一份等价兜底，保证 resolver 不返回 null
        return properties == null ? new KeelProperties().getPrincipal() : properties.getPrincipal();
    }

    private static String headerOrDefault(String header, String fallback) {
        String value = readHeader(header);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** 非 Web 线程（如测试直调）拿不到请求上下文时返回 null，全部落回配置默认值。 */
    private static String readHeader(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        // 必须走 HttpServletRequest.getHeader：RequestAttributes.getAttribute 只查
        // request attribute（setAttribute 写入的值），不含 HTTP header，会永远返回 null
        return servletAttributes.getRequest().getHeader(name);
    }
}
