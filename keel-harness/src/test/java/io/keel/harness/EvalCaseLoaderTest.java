package io.keel.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EvalCaseLoaderTest {

    private final EvalCaseLoader loader = new EvalCaseLoader();

    @Test
    void loadsAllSixCasesFromClasspathWithNonBlankIds() {
        List<EvalCase> cases = loader.load("eval");

        assertEquals(6, cases.size());

        Set<String> ids = new HashSet<>();
        for (EvalCase evalCase : cases) {
            assertNotNull(evalCase.getId());
            assertFalse(evalCase.getId().isBlank());
            ids.add(evalCase.getId());
        }

        assertEquals(
                Set.of(
                        "leak",
                        "missing-citation",
                        "fabricated-id",
                        "unexpected-write",
                        "write-without-idempotency",
                        "exceeded-step-budget"),
                ids);
    }
}
