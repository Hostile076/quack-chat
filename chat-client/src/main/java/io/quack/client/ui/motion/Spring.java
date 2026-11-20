package io.quack.client.ui.motion;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹簧动画引擎（Apple「Designing Fluid Interfaces」里的 damping ratio + response）。
 *
 * <p>为什么不用 {@code Timeline}/{@code Transition}：预定时长的动画没法被抓住、反向、重新定向；
 * 弹簧天然可中断——改目标即可，速度连续，不会出现「撞墙」。这里实现的是
 * 阻尼谐振子 {@code x'' = -ω²(x - target) - 2ζωx'}，每帧积分，同时支持：
 *
 * <ul>
 *   <li><b>从当前值出发</b>：永远读属性的现值，不做「从逻辑值重来」的跳变；</li>
 *   <li><b>速度接力</b>：调用方可以把手指/甩动的释放速度传进来；</li>
 *   <li><b>重新定向</b>：同一个属性在飞行中改目标，复用同一个 runner，速度自然保留。</li>
 * </ul>
 *
 * <p>参数口径与 Apple 一致：{@code response} 是"到达目标的大致时间（秒）"，
 * 不是时长；{@code damping} 为 1.0 时临界阻尼、不回弹，&lt;1 才允许过冲。
 */
public final class Spring {

    /** 默认：临界阻尼，优雅、不抢戏（移动/重排/普通出现） */
    public static final double DEFAULT_RESPONSE = 0.34;
    public static final double DEFAULT_DAMPING = 1.0;

    /** 带惯性的交互（甩动、开关、贴边）才允许轻微回弹 */
    public static final double MOMENTUM_DAMPING = 0.82;

    private static final double MAX_STEP = 1.0 / 240.0;

    private static final Map<DoubleProperty, Runner> RUNNERS = new LinkedHashMap<>();
    private static final List<Runner> SNAPSHOT = new ArrayList<>();
    private static AnimationTimer ticker;

    private Spring() {
    }

    /**
     * 把属性弹到目标值。
     *
     * @param property 要动的属性（scale/translate/opacity 等）
     * @param target   目标值
     * @param velocity 初始速度（单位/秒），可直接交给手势释放速度
     * @param response 响应时间（秒），越小越跟手
     * @param damping  阻尼比，1.0 = 不过冲
     */
    public static void to(DoubleProperty property, double target, double velocity,
                          double response, double damping) {
        if (property == null) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> to(property, target, velocity, response, damping));
            return;
        }
        Runner runner = RUNNERS.get(property);
        if (runner == null) {
            runner = new Runner(property, property.get(), target, velocity, response, damping);
            RUNNERS.put(property, runner);
        } else {
            // 重新定向：保留当前速度，只换目标 —— 这就是"可打断"的关键
            runner.retarget(target, velocity, response, damping);
        }
        startTicker();
    }

    /** 立刻停掉某个属性的弹簧（比如窗口隐藏时） */
    public static void stop(DoubleProperty property) {
        RUNNERS.remove(property);
    }

    /** 正在飞行中的属性数量（测试用） */
    public static int activeCount() {
        return RUNNERS.size();
    }

    private static void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                if (last == 0) {
                    last = now;
                    return;
                }
                double dt = (now - last) / 1_000_000_000.0;
                last = now;
                // 掉帧时不要一步跨太大，否则积分会发散
                dt = Math.max(1.0 / 1000.0, Math.min(dt, 1.0 / 30.0));

                SNAPSHOT.clear();
                SNAPSHOT.addAll(RUNNERS.values());
                for (Runner runner : SNAPSHOT) {
                    if (runner.tick(dt)) {
                        RUNNERS.remove(runner.property);
                    }
                }
                if (RUNNERS.isEmpty()) {
                    stop();
                    ticker = null;
                    last = 0;
                }
            }
        };
        ticker.start();
    }

    /** 单个属性的弹簧状态 */
    private static final class Runner {
        private final DoubleProperty property;
        private double value;
        private double velocity;
        private double target;
        private double omega;
        private double zeta;
        private double settleDistance;
        private double settleVelocity;

        Runner(DoubleProperty property, double from, double target, double velocity,
               double response, double damping) {
            this.property = property;
            this.value = from;
            this.velocity = velocity;
            configure(target, response, damping);
            this.settleDistance = Math.max(1e-4, Math.abs(target - from) * 0.0015);
            this.settleVelocity = Math.max(1e-3, Math.abs(target - from) * 0.02);
        }

        private void configure(double target, double response, double damping) {
            this.target = target;
            double safeResponse = Math.max(0.05, response);
            this.omega = 2 * Math.PI / safeResponse;
            this.zeta = Math.max(0.15, Math.min(1.6, damping));
        }

        void retarget(double newTarget, double newVelocity, double response, double damping) {
            this.value = property.get();
            this.velocity = newVelocity;
            configure(newTarget, response, damping);
            double distance = Math.abs(newTarget - value);
            this.settleDistance = Math.max(1e-4, distance * 0.0015);
            this.settleVelocity = Math.max(1e-3, distance * 0.02);
        }

        /** @return true = 已静止，可以从表里移除 */
        boolean tick(double dt) {
            int steps = Math.max(1, (int) Math.ceil(dt / MAX_STEP));
            double step = dt / steps;
            for (int i = 0; i < steps; i++) {
                double acceleration = -omega * omega * (value - target) - 2 * zeta * omega * velocity;
                velocity += acceleration * step;
                value += velocity * step;
            }
            if (Math.abs(value - target) <= settleDistance && Math.abs(velocity) <= settleVelocity) {
                if (Double.isNaN(target) || Double.isInfinite(target)) {
                    return true;
                }
                property.set(target);
                return true;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                property.set(target);
                return true;
            }
            property.set(value);
            return false;
        }
    }
}
