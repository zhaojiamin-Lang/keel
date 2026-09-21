package io.keel.harness;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.Extension;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;

/**
 * Harness 的 JUnit 5 扩展（FR-71）：把「YAML/JSON 数据集 + 断言规则」接进标准测试生命周期。
 *
 * <p>用法一：注入数据集，逐条跑 agent 后用 {@link #check} 断言——</p>
 *
 * <pre>{@code
 * @ExtendWith(KeelEvalExtension.class)
 * @EvalDataset("eval/ticket")
 * class TicketEvalTest {
 *     @Test
 *     void eval(List<EvalCase> cases) {
 *         for (EvalCase evalCase : cases) {
 *             AgentResult result = agent.run(evalCase.toAgentRequest());
 *             KeelEvalExtension.check(evalCase, result);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>参数解析规则：</p>
 * <ul>
 *   <li>{@code List<EvalCase>}：注入数据集内全部用例；</li>
 *   <li>{@code EvalCase}：仅当数据集恰好一条用例时注入（多条时无法一一对应，直接报错）。</li>
 * </ul>
 *
 * <p>数据集位置取方法级 {@link EvalDataset}，缺省回落到类级，再缺省 {@code eval}。
 * {@link #check} 失败时抛 {@link AssertionError}，消息带机器可读的规则码列表，
 * 方便 CI 直接摘出「越权 / 无引用 / 编造 ID / 无幂等 / 超步数」。</p>
 */
public class KeelEvalExtension implements ParameterResolver {

    private static final String DEFAULT_DATASET = "eval";

    private final KeelEvalChecker checker = new KeelEvalChecker();

    // ---------- 参数解析（Extension API） ----------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) throws ParameterResolutionException {
        return supportsParameterType(parameterContext.getParameter());
    }

    /** 包内可见，便于测试在不构造 ExtensionContext 的情况下验证参数语义。 */
    boolean supportsParameterType(Parameter parameter) {
        if (EvalCase.class.equals(parameter.getType())) {
            return true;
        }
        return List.class.equals(parameter.getType()) && isEvalCaseList(parameter);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) throws ParameterResolutionException {
        return resolveCases(
                parameterContext.getParameter(),
                extensionContext.getRequiredTestMethod(),
                extensionContext.getRequiredTestClass());
    }

    /**
     * 包内可见的实际解析逻辑：按注解加载数据集，再按参数类型分发。
     * 拆出 {@code Method}/{@code Class} 参数使测试无需桩掉 ExtensionContext。
     */
    Object resolveCases(Parameter parameter, Method testMethod, Class<?> testClass) {
        List<EvalCase> cases = loadCases(testMethod, testClass);
        if (EvalCase.class.equals(parameter.getType())) {
            if (cases.size() != 1) {
                throw new ParameterResolutionException(
                        "EvalCase 参数注入要求数据集恰好一条用例，实际 "
                                + cases.size() + " 条；多条用例请注入 List<EvalCase> 后自行循环");
            }
            return cases.get(0);
        }
        return cases;
    }

    /** 方法级注解优先，其次类级，最后默认 eval。 */
    private String datasetLocation(Method testMethod, Class<?> testClass) {
        EvalDataset onMethod = testMethod.getAnnotation(EvalDataset.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        EvalDataset onClass = testClass.getAnnotation(EvalDataset.class);
        return onClass == null ? DEFAULT_DATASET : onClass.value();
    }

    private List<EvalCase> loadCases(Method testMethod, Class<?> testClass) {
        String location = datasetLocation(testMethod, testClass);
        return new EvalCaseLoader().load(location);
    }

    private static boolean isEvalCaseList(Parameter parameter) {
        var genericType = parameter.getParameterizedType();
        if (!(genericType instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return false;
        }
        var typeArgs = parameterized.getActualTypeArguments();
        return typeArgs.length == 1 && EvalCase.class.equals(typeArgs[0]);
    }

    // ---------- 断言入口（同时供不使用扩展的调用方复用） ----------

    /** 用例规则断言：不通过抛 {@link AssertionError}，消息携带失败规则码。 */
    public static void check(EvalCase evalCase, AgentResult result) {
        Objects.requireNonNull(evalCase, "evalCase");
        Objects.requireNonNull(result, "result");
        EvalReport report = new KeelEvalChecker().check(evalCase, result);
        if (!report.isPassed()) {
            throw new AssertionError(
                    "评测用例 " + evalCase.getId() + " 未通过，失败规则: " + report.getFailedRules());
        }
    }

    /** 同 {@link #check}，另附请求上下文（当前与 check 等价，预留扩展点）。 */
    public static void check(EvalCase evalCase, AgentRequest request, AgentResult result) {
        Objects.requireNonNull(request, "request");
        check(evalCase, result);
    }

    /** 供测试内直接复用底层 checker（如需要拿到 EvalReport 做自定义报告）。 */
    public EvalReport inspect(EvalCase evalCase, AgentResult result) {
        return checker.check(evalCase, result);
    }
}
