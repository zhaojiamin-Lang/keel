package io.keel.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;


import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;

/**
 * FR-34：MCP Server 参考应用——SSE 传输（远程 HTTP 接入）。
 *
 * <p>把 {@link HttpServletSseServerTransportProvider} 作为 Servlet 注册到内嵌容器：
 * {@code GET /sse} 建立事件流，{@code POST /mcp/message} 收发 JSON-RPC 消息。
 * 这是 MCP Server 参考实现的应用壳，不是 keel 内核的 Web 层。</p>
 */
@SpringBootApplication
public class IssueMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssueMcpServerApplication.class, args);
    }

    @Bean
    InMemoryIssueServer issueServer() {
        return new InMemoryIssueServer();
    }

    @Bean
    IssueToolRegistry issueToolRegistry(InMemoryIssueServer issueServer) {
        return new IssueToolRegistry(issueServer);
    }

    /**
     * SSE 传输 Servlet + MCP Server 组装：
     * create() 内部完成 McpServer.sync(provider)...build() 后，
     * 服务器即随 Servlet 生命周期对外服务。
     */
    @Bean
    ServletRegistrationBean<HttpServletSseServerTransportProvider> issueMcpServlet(
            ObjectMapper objectMapper, IssueToolRegistry registry) {
        HttpServletSseServerTransportProvider transportProvider =
                HttpServletSseServerTransportProvider.builder()
                        .objectMapper(objectMapper)
                        .sseEndpoint("/sse")
                        .messageEndpoint("/mcp/message")
                        .build();
        IssueMcpServerFactory.create(transportProvider, registry);
        return new ServletRegistrationBean<>(transportProvider, "/sse", "/mcp/message");
    }
}
