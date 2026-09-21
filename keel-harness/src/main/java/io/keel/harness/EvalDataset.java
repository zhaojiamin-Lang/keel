package io.keel.harness;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明评测数据集的 classpath 目录（FR-71）。
 *
 * <p>标在测试类或测试方法上，由 {@link KeelEvalExtension} 读取并加载其中的
 * YAML/JSON 用例（目录不存在或为空时直接抛异常——没有考卷就不该跑评测，fail-closed）。</p>
 *
 * <pre>{@code
 * @ExtendWith(KeelEvalExtension.class)
 * @EvalDataset("eval/ticket")
 * class TicketEvalTest {
 *     @Test
 *     void eval(List<EvalCase> cases, AgentRunner runner) {
 *         for (EvalCase evalCase : cases) {
 *             AgentResult result = runner.run(evalCase.toAgentRequest());
 *             KeelEvalExtension.check(evalCase, result);
 *         }
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface EvalDataset {

    /** classpath 目录，默认 {@code eval}。 */
    String value() default "eval";
}
