package io.keel.harness;

import java.util.List;
import java.util.Objects;

public final class EvalReport {

    private final boolean passed;
    private final List<String> failedRules;

    public EvalReport(boolean passed, List<String> failedRules) {
        this.passed = passed;
        this.failedRules = failedRules == null ? List.of() : List.copyOf(failedRules);
    }

    public boolean isPassed() {
        return passed;
    }

    public List<String> getFailedRules() {
        return failedRules;
    }
}
