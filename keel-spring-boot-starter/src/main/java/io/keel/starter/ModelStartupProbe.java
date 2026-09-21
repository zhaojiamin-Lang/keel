package io.keel.starter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * NFR-04 模型探针：keel.model.enabled=true（默认）时容器必须有 ChatModel bean。
 *
 * <p>缺 bean 时 {@code singleShotKeelAgent / graphKeelAgent} 被 {@code @ConditionalOnBean}
 * 静默跳过装配，业务到注入点才收到晦涩的 NoSuchBeanDefinition——strict/warn 模式下
 * 启动即点名。检查只做装配完整性校验，<b>不发起真实模型调用</b>（避免启动期计费与
 * 外部副作用）；模型连通性由运行期请求路径与 Spring AI 重试兜底。</p>
 */
final class ModelStartupProbe implements StartupProbe {

    private final KeelProperties properties;
    private final ObjectProvider<ChatModel> chatModels;

    ModelStartupProbe(KeelProperties properties, ObjectProvider<ChatModel> chatModels) {
        this.properties = properties;
        this.chatModels = chatModels;
    }

    @Override
    public String check() {
        if (!properties.getModel().isEnabled()) {
            return null;
        }
        if (chatModels.getIfAvailable() != null) {
            return null;
        }
        return "模型: keel.model.enabled=true 但容器中没有 ChatModel bean，"
                + "KeelAgent（singleShotKeelAgent / graphKeelAgent）被 @ConditionalOnBean 静默跳过装配；"
                + "请注册 Spring AI ChatModel bean，或设 keel.model.enabled=false 明确关闭";
    }
}
