package io.keel.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keel")
public class KeelProperties {

    public static final String MODE_SINGLE_SHOT = "single-shot";
    public static final String MODE_GRAPH = "graph";

    private Model model = new Model();
    private Agent agent = new Agent();
    private Tools tools = new Tools();
    private Retrieval retrieval = new Retrieval();
    private Checkpoint checkpoint = new Checkpoint();
    private Langfuse langfuse = new Langfuse();
    private Memory memory = new Memory();
    private Map<String, String> skill = new LinkedHashMap<>();
    private SkillGray skillGray = new SkillGray();
    private Startup startup = new Startup();
    private Client client = new Client();
    private Principal principal = new Principal();

    /**
     * 接入适配层配置（keel.client.*）。
     *
     * <p>{@code KeelAgentClient} 把 AgentRequest 拼装、session 解析、幂等键生成收口，
     * 业务只写 {@code keelClient.chat(input)}。enabled=false 时不创建该 bean，
     * 业务退回自己拼 AgentRequest（保持向后兼容，现行为零变化）。</p>
     */
    public static class Client {

        private boolean enabled = true;

        /**
         * 幂等键前缀，最终键形如 {@code {prefix}:{tenantId}:{sessionId}}。
         * 多套 Keel 实例共用一个 Redis 时用不同前缀避免互相 dedupe。
         */
        private String idempotencyKeyPrefix = "keel";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIdempotencyKeyPrefix() {
            return idempotencyKeyPrefix;
        }

        public void setIdempotencyKeyPrefix(String idempotencyKeyPrefix) {
            this.idempotencyKeyPrefix = idempotencyKeyPrefix;
        }
    }

    /**
     * 默认身份（keel.principal.*）：仅在业务<b>没有</b>注册
     * {@code PrincipalResolver} bean 时生效，保证零配置可启动。
     *
     * <p><b>多租户务必覆盖：</b>本配置对所有请求返回同一个身份，多租户下会导致
     * 跨租户数据串号（NFR-02 把租户隔离失败视为 P0 缺陷）。生产请注册
     * {@code PrincipalResolver} bean（从 SecurityContext / 请求头 / RPC 上下文解析）。</p>
     */
    public static class Principal {

        private String tenantId = "default";

        private String subjectId = "anonymous";

        /** 资源级权限（如 projectId），参与 RAG / MCP 的 ACL 过滤（FR-60） */
        private List<String> resourceIds = new ArrayList<>();

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(String subjectId) {
            this.subjectId = subjectId;
        }

        public List<String> getResourceIds() {
            return resourceIds;
        }

        public void setResourceIds(List<String> resourceIds) {
            this.resourceIds = resourceIds;
        }
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public Principal getPrincipal() {
        return principal;
    }

    public void setPrincipal(Principal principal) {
        this.principal = principal;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    public Langfuse getLangfuse() {
        return langfuse;
    }

    public void setLangfuse(Langfuse langfuse) {
        this.langfuse = langfuse;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public Map<String, String> getSkill() {
        return skill;
    }

    public void setSkill(Map<String, String> skill) {
        this.skill = skill;
    }

    public SkillGray getSkillGray() {
        return skillGray;
    }

    public void setSkillGray(SkillGray skillGray) {
        this.skillGray = skillGray;
    }

    public Startup getStartup() {
        return startup;
    }

    public void setStartup(Startup startup) {
        this.startup = startup;
    }

    /**
     * NFR-04 启动自检配置（keel.startup.*）。
     * 设计取舍：可选依赖（Redis / 向量库 / Langfuse）默认保持静默降级、
     * 不强制阻塞启动；业务期望「连不通就启动失败」时显式开 strict，
     * 探针失败报告会点名具体 bean 与配置键。
     */
    public static class Startup {

        /**
         * off / warn / strict。
         * off（默认）= 不探测，现行为零变化；warn = 失败项日志告警不阻断；
         * strict = 失败即抛异常、启动失败。
         */
        private String connectivityCheck = "off";

        /** 单项探针超时（Redis PING / Langfuse HTTP 探活共用） */
        private Duration probeTimeout = Duration.ofSeconds(3);

        public String getConnectivityCheck() {
            return connectivityCheck;
        }

        public void setConnectivityCheck(String connectivityCheck) {
            this.connectivityCheck = connectivityCheck;
        }

        public Duration getProbeTimeout() {
            return probeTimeout;
        }

        public void setProbeTimeout(Duration probeTimeout) {
            this.probeTimeout = probeTimeout;
        }
    }

    /**
     * Skill 灰度配置（keel.skill-gray.*，§8 第三期）。
     * 灰度版本本身来自 classpath {@code skills-gray/} 目录（与 skills/ 同格式，
     * 同名不同版本）；本配置只决定「谁命中灰度」。enabled 默认 false——
     * 不配置灰度时策略不创建，图内核行为零变化。
     */
    public static class SkillGray {

        private boolean enabled = false;

        /** percent / allowlist */
        private String strategy = "percent";

        /** percent 策略：灰度流量百分比 [0, 100] */
        private int percent = 0;

        /** allowlist 策略：tenantId 白名单 */
        private List<String> tenants = new ArrayList<>();

        /** allowlist 策略：subjectId 白名单 */
        private List<String> subjects = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }

        public List<String> getTenants() {
            return tenants;
        }

        public void setTenants(List<String> tenants) {
            this.tenants = tenants;
        }

        public List<String> getSubjects() {
            return subjects;
        }

        public void setSubjects(List<String> subjects) {
            this.subjects = subjects;
        }
    }

    public static class Model {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Agent 执行形态与 Loop 预算（FR-11）。
     * mode=single-shot 走单次模型调用；mode=graph 走 LangGraph 风格循环。
     */
    public static class Agent {

        private String mode = MODE_SINGLE_SHOT;
        private int maxSteps = 5;
        private Duration timeout = Duration.ofSeconds(30);
        /** FR-63：默认最多 3 次写动作。 */
        private int maxWrites = 3;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxWrites() {
            return maxWrites;
        }

        public void setMaxWrites(int maxWrites) {
            this.maxWrites = maxWrites;
        }
    }

    public static class Tools {

        private List<String> allowed = new ArrayList<>();

        /**
         * FR-33：yml 级可配置脱敏模式。
         * 可选值：TOKEN / MOBILE / SECRET / ALL / NONE。
         * 默认 ALL（等价旧 redact=true）；NONE 等价旧 redact=false。
         * 不区分大小写，BindConverter 会自动转换。
         */
        private String redactMode = "ALL";

        public List<String> getAllowed() {
            return allowed;
        }

        public void setAllowed(List<String> allowed) {
            this.allowed = allowed;
        }

        public String getRedactMode() {
            return redactMode;
        }

        public void setRedactMode(String redactMode) {
            this.redactMode = redactMode;
        }

        // ==================== MCP 工具（FR-31） ====================
        private Mcp mcp = new Mcp();

        public Mcp getMcp() {
            return mcp;
        }

        public void setMcp(Mcp mcp) {
            this.mcp = mcp;
        }

        public static final String TRANSPORT_SSE = "sse";
        public static final String TRANSPORT_STDIO = "stdio";

        /**
         * MCP client 配置：默认开启只读 MCP（matchIfMissing），transport 必须显式选择；
         * 业务也可以完全自定义 McpSyncClient bean，此时 transport 留空即可跳过 starter 建连。
         */
        public static class Mcp {

            private boolean enabled = true;

            /** sse / stdio；为空表示不创建默认 client（由业务自定义 McpSyncClient bean） */
            private String transport;

            /** SSE：MCP server 基地址 */
            private String baseUrl;

            /** SSE endpoint（0.10.0 默认 /sse；新版协议 /mcp 需显式覆盖） */
            private String sseEndpoint = "/sse";

            /** 可选 Bearer token，放请求头 Authorization 透传 */
            private String token;

            /** stdio：可执行命令（如 npx） */
            private String command;

            /** stdio：命令参数 */
            private List<String> args = new ArrayList<>();

            /** stdio：额外环境变量 */
            private Map<String, String> env = new LinkedHashMap<>();

            private Duration requestTimeout = Duration.ofSeconds(10);

            private Duration initializationTimeout = Duration.ofSeconds(10);

            /** 传输类故障重试次数（0 = 不重试） */
            private int maxRetries = 2;

            /** 重试退避（毫秒） */
            private Duration retryBackoff = Duration.ofMillis(100);

            /** 本 server 暴露的工具清单；为空时启动阶段用 listTools() 自动发现（多 server 路由用） */
            private List<String> tools = new ArrayList<>();

            /**
             * 多 server 路由配置（FR-31）：serverName → 该 server 的连接配置。
             * 对应 yaml 形如 {@code keel.tools.mcp.servers.gitlab.transport=sse}。
             * 非空时 starter 装配 RoutingMcpToolGateway 按工具名路由；为空沿用单 server 路径。
             */
            private Map<String, Mcp> servers = new LinkedHashMap<>();

            /**
             * FR-31 per-call 凭证：serverName → token 映射。
             * 对应 yaml 形如 {@code keel.tools.mcp.credentials.gitlab=token-123}。
             * server 在映射中即视为有凭证要求（isCredentialRequired=true），
             * principal 解析不到凭证时 gateway fail-closed 拒绝调用。
             */
            private Map<String, String> credentials = new LinkedHashMap<>();

            /**
             * FR-31：true 时 principal 的 resourceIds 必须包含 serverName 才能解析到凭证，
             * 否则返回 empty 触发拒绝；默认 false（向后兼容，任意 principal 共享 token 映射）。
             */
            private boolean requirePrincipalMatch = false;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getTransport() {
                return transport;
            }

            public void setTransport(String transport) {
                this.transport = transport;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getSseEndpoint() {
                return sseEndpoint;
            }

            public void setSseEndpoint(String sseEndpoint) {
                this.sseEndpoint = sseEndpoint;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }

            public String getCommand() {
                return command;
            }

            public void setCommand(String command) {
                this.command = command;
            }

            public List<String> getArgs() {
                return args;
            }

            public void setArgs(List<String> args) {
                this.args = args;
            }

            public Map<String, String> getEnv() {
                return env;
            }

            public void setEnv(Map<String, String> env) {
                this.env = env;
            }

            public Duration getRequestTimeout() {
                return requestTimeout;
            }

            public void setRequestTimeout(Duration requestTimeout) {
                this.requestTimeout = requestTimeout;
            }

            public Duration getInitializationTimeout() {
                return initializationTimeout;
            }

            public void setInitializationTimeout(Duration initializationTimeout) {
                this.initializationTimeout = initializationTimeout;
            }

            public int getMaxRetries() {
                return maxRetries;
            }

            public void setMaxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
            }

            public Duration getRetryBackoff() {
                return retryBackoff;
            }

            public void setRetryBackoff(Duration retryBackoff) {
                this.retryBackoff = retryBackoff;
            }

            public List<String> getTools() {
                return tools;
            }

            public void setTools(List<String> tools) {
                this.tools = tools;
            }

            public Map<String, Mcp> getServers() {
                return servers;
            }

            public void setServers(Map<String, Mcp> servers) {
                this.servers = servers;
            }

            public Map<String, String> getCredentials() {
                return credentials;
            }

            public void setCredentials(Map<String, String> credentials) {
                this.credentials = credentials;
            }

            public boolean isRequirePrincipalMatch() {
                return requirePrincipalMatch;
            }

            public void setRequirePrincipalMatch(boolean requirePrincipalMatch) {
                this.requirePrincipalMatch = requirePrincipalMatch;
            }
        }
    }

    /**
     * RAG 检索配置（keel.retrieval.*，FR-15/FR-40）。
     * enabled 默认 true，但真正生效还要求 classpath 存在 langchain4j 且容器中有
     * EmbeddingModel / EmbeddingStore bean（@ConditionalOnBean 双保险），业务不引 RAG 依赖时零负担。
     * 租户 / 资源隔离在适配层代码内强制（AclScopeFilterFactory），不接受配置放宽（FR-41）。
     */
    public static class Retrieval {

        private boolean enabled = true;

        /** 单轮检索最多取回的片段数（topK，必须为正）；与适配层 DEFAULT_MAX_RESULTS 对齐 */
        private int maxResults = 5;

        /** 生产向量库选择（keel.retrieval.store.*，FR-40）；type 未配置时不创建默认 store。 */
        private Store store = new Store();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public Store getStore() {
            return store;
        }

        public void setStore(Store store) {
            this.store = store;
        }

        /** 向量库后端配置：type 决定 in-memory / pgvector / milvus 三选一。 */
        public static class Store {

            /** in-memory / pgvector / milvus；为空表示不创建默认 store（业务自备 EmbeddingStore bean） */
            private String type;

            /** 向量维度，pgvector / milvus 必填（与 embedding 模型输出一致） */
            private Integer dimension;

            private String host = "localhost";

            /** pgvector 默认 5432 / milvus 默认 19530，由工厂按 type 兜底 */
            private Integer port;

            /** pgvector：库名 */
            private String database;

            /** pgvector：账号 */
            private String user;

            /** pgvector：口令（密钥只走配置 / 环境变量，NFR-08） */
            private String password;

            /** pgvector：表名 */
            private String table = "keel_embeddings";

            /** milvus：集合名 */
            private String collectionName = "keel_embeddings";

            /** milvus：可选 gRPC uri（覆盖 host/port） */
            private String uri;

            /** milvus：可选访问 token */
            private String token;

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public Integer getDimension() {
                return dimension;
            }

            public void setDimension(Integer dimension) {
                this.dimension = dimension;
            }

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public Integer getPort() {
                return port;
            }

            public void setPort(Integer port) {
                this.port = port;
            }

            public String getDatabase() {
                return database;
            }

            public void setDatabase(String database) {
                this.database = database;
            }

            public String getUser() {
                return user;
            }

            public void setUser(String user) {
                this.user = user;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getTable() {
                return table;
            }

            public void setTable(String table) {
                this.table = table;
            }

            public String getCollectionName() {
                return collectionName;
            }

            public void setCollectionName(String collectionName) {
                this.collectionName = collectionName;
            }

            public String getUri() {
                return uri;
            }

            public void setUri(String uri) {
                this.uri = uri;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }
        }
    }

    /**
     * L1 Redis Checkpoint 配置（keel.checkpoint.*，FR-12）。
     * enabled 默认 true，但仅当 classpath 存在 keel-memory（RedisClient）时才装配；
     * 显式开启却缺依赖时启动期 fail-fast（见 KeelCheckpointAutoConfiguration），
     * 不静默降级——内存 checkpoint 在多实例下会让 FR-13 写确认失效。
     */
    public static class Checkpoint {

        private boolean enabled = true;

        private String host = "localhost";

        private int port = 6379;

        /** Redis 认证密码（FR-08：密钥只走配置/环境变量，建议写 ${REDIS_PASSWORD}） */
        private String password;

        /** Redis DB index（0-15），默认 0 */
        private int database = 0;

        /** 是否启用 TLS（rediss://）；云 Redis / 跨公网访问需开启 */
        private boolean ssl = false;

        /** checkpoint 在 Redis 的存活时间 */
        private Duration ttl = Duration.ofMinutes(30);

        /** 建连超时：避免 Redis 不可达时启动/请求长时间挂住 */
        private Duration connectTimeout = Duration.ofSeconds(2);

        /** 单条命令超时：避免单次 GET/SET 卡死整个 Agent 请求（NFR-06） */
        private Duration commandTimeout = Duration.ofSeconds(3);

        /**
         * 断线自动重连次数。默认 3——Lettuce 默认不重连，一次网络抖动会让后续
         * 所有 checkpoint 读写持续失败，表现为「点了确认没反应」。
         * 设 0 显式关闭重连。
         */
        private int maxRetries = 3;

        /** 重连退避基数 */
        private Duration retryBackoff = Duration.ofMillis(200);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }

    /**
     * Langfuse Trace 配置（keel.langfuse.*，FR-70 / NFR-08）。
     * 仅当 classpath 存在 keel-langfuse、enabled=true 且密钥已配置时才创建 TracePort。
     */
    public static class Langfuse {

        private boolean enabled = false;

        /** Langfuse 地址，SaaS 默认 EU 区；自建填自己的域名 */
        private String host = "https://cloud.langfuse.com";

        private String publicKey;

        private String secretKey;

        /** 默认开启脱敏（NFR-08）：工具 IO 中的 token/密码等字段替换为 *** */
        private boolean redact = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean isRedact() {
            return redact;
        }

        public void setRedact(boolean redact) {
            this.redact = redact;
        }
    }

    /**
     * L2 / L3 记忆配置（keel.memory.*，FR-51/FR-52/FR-54）。
     * enabled 默认 false，显式开启后才装配 MemoryPort 注入 Loop。
     */
    public static class Memory {

        private boolean enabled = false;

        /** 单次注入的最大条数（FR-54） */
        private int maxFragments = 5;

        /** 单条记忆注入的最大字符数（FR-54 截断） */
        private int maxCharsPerFragment = 200;

        private L2 l2 = new L2();

        private L3 l3 = new L3();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxFragments() {
            return maxFragments;
        }

        public void setMaxFragments(int maxFragments) {
            this.maxFragments = maxFragments;
        }

        public int getMaxCharsPerFragment() {
            return maxCharsPerFragment;
        }

        public void setMaxCharsPerFragment(int maxCharsPerFragment) {
            this.maxCharsPerFragment = maxCharsPerFragment;
        }

        public L2 getL2() {
            return l2;
        }

        public void setL2(L2 l2) {
            this.l2 = l2;
        }

        public L3 getL3() {
            return l3;
        }

        public void setL3(L3 l3) {
            this.l3 = l3;
        }

        /** L2 情景记忆后端：in-memory / mongo。 */
        public static class L2 {

            private String type = "in-memory";

            /** mongo 连接串 */
            private String connectionString;

            private String database = "keel";

            private String collection = "keel_episodic_memory";

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getConnectionString() {
                return connectionString;
            }

            public void setConnectionString(String connectionString) {
                this.connectionString = connectionString;
            }

            public String getDatabase() {
                return database;
            }

            public void setDatabase(String database) {
                this.database = database;
            }

            public String getCollection() {
                return collection;
            }

            public void setCollection(String collection) {
                this.collection = collection;
            }
        }

        /** L3 长期记忆后端：in-memory / jdbc（PG）。 */
        public static class L3 {

            private String type = "in-memory";

            /** PG JDBC 连接串 */
            private String jdbcUrl;

            private String user;

            private String password;

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getJdbcUrl() {
                return jdbcUrl;
            }

            public void setJdbcUrl(String jdbcUrl) {
                this.jdbcUrl = jdbcUrl;
            }

            public String getUser() {
                return user;
            }

            public void setUser(String user) {
                this.user = user;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }
        }
    }
}
