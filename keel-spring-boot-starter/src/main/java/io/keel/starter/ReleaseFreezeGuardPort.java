package io.keel.starter;

import java.time.DayOfWeek;

import java.time.LocalDateTime;
import java.time.ZoneId;

import io.keel.graph.LoopState;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;

/**
 * FR-64：内置业务规则——发布冻结规则。
 *
 * <p>在配置的冻结时段（默认周五 18:00 ~ 周日 23:59，北京时间）内，
 * 拒绝任何写操作（writable=true 的 skill），避免周末发布无人值守。</p>
 *
 * <p>这是 {@link GuardPort} 扩展点的内置参考实现，展示如何加业务规则。
 * 业务可自定义更多 GuardPort（如审批状态校验、变更窗口校验）并加入
 * {@link CompositeGuardPort}。</p>
 */
public class ReleaseFreezeGuardPort implements GuardPort {

    private final ZoneId zone;
    private final DayOfWeek freezeStartDay;
    private final int freezeStartHour;
    private final DayOfWeek freezeEndDay;
    private final int freezeEndHour;

    public ReleaseFreezeGuardPort() {
        this(ZoneId.of("Asia/Shanghai"), DayOfWeek.FRIDAY, 18, DayOfWeek.SUNDAY, 23);
    }

    public ReleaseFreezeGuardPort(
            ZoneId zone,
            DayOfWeek freezeStartDay, int freezeStartHour,
            DayOfWeek freezeEndDay, int freezeEndHour) {
        this.zone = zone;
        this.freezeStartDay = freezeStartDay;
        this.freezeStartHour = freezeStartHour;
        this.freezeEndDay = freezeEndDay;
        this.freezeEndHour = freezeEndHour;
    }

    @Override
    public Verdict inspect(LoopState state) {
        // 只对写操作生效
        if (state.getSkill() == null || !state.getSkill().isWritable()) {
            return Verdict.approved();
        }
        if (isInFreezeWindow(LocalDateTime.now(zone))) {
            return Verdict.rejected(
                    "RELEASE_FREEZE",
                    "当前处于发布冻结时段（" + freezeStartDay + " " + freezeStartHour
                            + ":00 ~ " + freezeEndDay + " " + freezeEndHour
                            + ":59），禁止写操作");
        }
        return Verdict.approved();
    }

    private boolean isInFreezeWindow(LocalDateTime now) {
        DayOfWeek today = now.getDayOfWeek();
        int hour = now.getHour();

        // 周五 18:00 后开始冻结
        if (today == freezeStartDay && hour >= freezeStartHour) {
            return true;
        }
        // 周六全天冻结
        if (today == DayOfWeek.SATURDAY) {
            return true;
        }
        // 周日 23:59 前结束冻结
        return today == freezeEndDay && hour <= freezeEndHour;
    }
}