/*
 * Copyright © 2019, 2020, 2021, 2022, 2023, 2024, 2025, 2026 Peter Doornbosch
 *
 * This file is part of Kwik, an implementation of the QUIC protocol in Java.
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package tech.kwik.core.cc;

import tech.kwik.core.log.Logger;
import tech.kwik.core.packet.PacketInfo;
import tech.kwik.core.packet.QuicPacket;
import tech.kwik.core.recovery.RttEstimator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Delay-aware congestion controller designed for low queuing latency and high responsiveness
 * over interactive real-time streams alongside burst transfers.
 */
public class DelayAwareCongestionController extends AbstractCongestionController implements CongestionController {

    private static final long MAX_DATAGRAM_SIZE = 1200L;
    private static final long MIN_WINDOW = 4L * MAX_DATAGRAM_SIZE;
    private static final long MIN_DELAY_BACKOFF_WINDOW = 8L * MAX_DATAGRAM_SIZE;
    private static final long MAX_WINDOW = 1024L * 1024L;
    private static final double SLOW_START_PACING_GAIN = 2.0;
    private static final double AVOIDANCE_PACING_GAIN = 1.25;
    private static final long MIN_PACING_RATE_BPS = 64_000L;
    private static final long MAX_PACING_RATE_BPS = 250_000_000L;
    private static final long UNDO_WINDOW_MS = 3_000L;

    private final Clock clock;
    private final RttEstimator rttEstimator;

    private volatile long slowStartThreshold = Long.MAX_VALUE;
    private Instant congestionRecoveryStartTime = Instant.MIN;

    private boolean hasUndoSnapshot;
    private long preLossCwnd;
    private long preLossSsthresh;
    private Instant undoTimestamp;

    private Instant roundStartTime;
    private int roundMinRtt = Integer.MAX_VALUE;
    private int roundSampleCount;
    private long roundBytesAcked;
    private int cleanRounds;
    private int consecutiveDelayRounds;
    private int consecutiveHighDelaySamples;
    private int prevRoundMinRtt = Integer.MAX_VALUE;
    private Instant lastDelayReductionTime;
    private double recentDeliveryRateBps;

    public DelayAwareCongestionController(RttEstimator rttEstimator, Logger logger, CongestionControlEventListener eventListener) {
        this(Clock.systemUTC(), rttEstimator, logger, eventListener);
    }

    public DelayAwareCongestionController(Clock clock, RttEstimator rttEstimator, Logger logger, CongestionControlEventListener eventListener) {
        super(logger, eventListener);
        this.clock = clock;
        this.rttEstimator = rttEstimator;
    }

    @Override
    public synchronized void registerInFlight(QuicPacket sentPacket) {
        super.registerInFlight(sentPacket);
    }

    @Override
    public synchronized void registerAcked(List<? extends PacketInfo> acknowlegdedPackets) {
        boolean cwndLimited = (bytesInFlight + 3 * MAX_DATAGRAM_SIZE >= congestionWindow)
                || (isInSlowStart() && bytesInFlight * 2 >= congestionWindow);

        Instant now = clock.instant();
        super.registerAcked(acknowlegdedPackets);

        long totalAckedBytes = 0L;
        long ackedAfterRecoveryBytes = 0L;
        for (PacketInfo info : acknowlegdedPackets) {
            int size = info.packet().getSize();
            totalAckedBytes += size;
            if (info.timeSent().isAfter(congestionRecoveryStartTime)) {
                ackedAfterRecoveryBytes += size;
            }
        }
        roundBytesAcked += totalAckedBytes;

        if (rttEstimator != null && rttEstimator.hasRttSample()) {
            int latestRtt = rttEstimator.getLatestRtt();
            int smoothedRtt = Math.max(1, rttEstimator.getSmoothedRtt());
            int baseRtt = Math.max(1, rttEstimator.getWindowedMinRtt());
            int rttVar = rttEstimator.getRttVar();
            int delayThresholdMs = Math.max(20, Math.min(50, baseRtt / 5 + rttVar));

            roundMinRtt = Math.min(roundMinRtt, latestRtt);
            roundSampleCount++;
            if (latestRtt - baseRtt > delayThresholdMs) {
                consecutiveHighDelaySamples++;
            }
            else {
                consecutiveHighDelaySamples = 0;
            }

            if (isInSlowStart()
                    && congestionWindow >= 16L * MAX_DATAGRAM_SIZE
                    && consecutiveHighDelaySamples >= 10) {
                double rateForBdp = recentDeliveryRateBps > 0.0
                        ? recentDeliveryRateBps
                        : (congestionWindow * 1000.0) / Math.max(1, smoothedRtt);
                long bdpTarget = Math.max(MIN_DELAY_BACKOFF_WINDOW, computeBdpTarget(rateForBdp, baseRtt, delayThresholdMs));
                congestionWindow = clampWindow(Math.min((congestionWindow * 7L) / 8L, bdpTarget));
                slowStartThreshold = congestionWindow;
                lastDelayReductionTime = now;
                consecutiveHighDelaySamples = 0;
                log.cc("Delay fast-exit slow-start cwnd -> " + congestionWindow);
            }

            if (roundStartTime == null) {
                roundStartTime = now;
            }
            else {
                long elapsedMs = Duration.between(roundStartTime, now).toMillis();
                long maxValidRoundMs = Math.max(4L * Math.max(smoothedRtt, 25), 500L);
                if (elapsedMs > maxValidRoundMs && roundBytesAcked < 4L * MAX_DATAGRAM_SIZE) {
                    // Application pause or flow-control stall: discard stale rate/delay round.
                    roundStartTime = now;
                    roundMinRtt = Integer.MAX_VALUE;
                    roundSampleCount = 0;
                    roundBytesAcked = 0L;
                }
                else if (elapsedMs >= Math.max(baseRtt, 20) && roundSampleCount >= 4) {
                    long effectiveElapsedMs = Math.max(1L, elapsedMs);
                    double roundRateBps = (roundBytesAcked * 1000.0) / effectiveElapsedMs;
                    boolean meaningfulSample = roundBytesAcked >= 4L * MAX_DATAGRAM_SIZE && elapsedMs <= maxValidRoundMs;
                    if (meaningfulSample) {
                        recentDeliveryRateBps = recentDeliveryRateBps <= 0.0
                                ? roundRateBps
                                : 0.5 * recentDeliveryRateBps + 0.5 * roundRateBps;
                    }

                    int queueDelayMs = Math.max(0, roundMinRtt - baseRtt);
                    boolean standingRttShift = consecutiveDelayRounds >= 4
                            && prevRoundMinRtt != Integer.MAX_VALUE
                            && roundMinRtt <= prevRoundMinRtt + Math.max(15, delayThresholdMs / 2);

                    if (queueDelayMs > delayThresholdMs && meaningfulSample && !standingRttShift) {
                        cleanRounds = 0;
                        consecutiveDelayRounds++;
                        double rateForBdp = Math.max(roundRateBps, recentDeliveryRateBps);
                        if (isInSlowStart() && congestionWindow >= 16L * MAX_DATAGRAM_SIZE) {
                            long bdpTarget = Math.max(MIN_DELAY_BACKOFF_WINDOW, computeBdpTarget(rateForBdp, baseRtt, delayThresholdMs));
                            congestionWindow = clampWindow(Math.min((congestionWindow * 7L) / 8L, bdpTarget));
                            slowStartThreshold = congestionWindow;
                            lastDelayReductionTime = now;
                            log.cc("Delay exit slow-start cwnd -> " + congestionWindow + " (qDelay=" + queueDelayMs + "ms)");
                        }
                        else if (!isInSlowStart()
                                && congestionWindow > 10L * MAX_DATAGRAM_SIZE
                                && (lastDelayReductionTime == null
                                        || Duration.between(lastDelayReductionTime, now).toMillis() >= Math.max(smoothedRtt, 25))) {
                            long bdpTarget = Math.max(MIN_DELAY_BACKOFF_WINDOW, computeBdpTarget(rateForBdp, baseRtt, delayThresholdMs));
                            long targetCwnd;
                            if (queueDelayMs > 3 * delayThresholdMs) {
                                targetCwnd = Math.min((congestionWindow * 7L) / 10L, bdpTarget);
                            }
                            else {
                                targetCwnd = Math.min((congestionWindow * 85L) / 100L, (congestionWindow + bdpTarget) / 2L);
                            }
                            congestionWindow = clampWindow(Math.max(MIN_DELAY_BACKOFF_WINDOW, targetCwnd));
                            slowStartThreshold = Math.min(slowStartThreshold, congestionWindow);
                            lastDelayReductionTime = now;
                            log.cc("Delay backoff cwnd -> " + congestionWindow + " (qDelay=" + queueDelayMs + "ms)");
                        }
                    }
                    else {
                        if (queueDelayMs <= delayThresholdMs) {
                            consecutiveDelayRounds = 0;
                        }
                        else {
                            consecutiveDelayRounds++;
                        }
                        if (queueDelayMs <= delayThresholdMs / 2) {
                            cleanRounds = Math.min(cleanRounds + 1, 16);
                        }
                        else {
                            cleanRounds = Math.max(0, cleanRounds - 1);
                        }
                    }

                    prevRoundMinRtt = roundMinRtt;
                    roundStartTime = now;
                    roundMinRtt = Integer.MAX_VALUE;
                    roundSampleCount = 0;
                    roundBytesAcked = 0L;
                }
            }

            if (cwndLimited && ackedAfterRecoveryBytes > 0L) {
                if (isInSlowStart()) {
                    congestionWindow = clampWindow(congestionWindow + ackedAfterRecoveryBytes);
                }
                else {
                    int currentDelay = Math.max(0, latestRtt - baseRtt);
                    boolean severeQueue = currentDelay > 2 * delayThresholdMs
                            && congestionWindow > 16L * MAX_DATAGRAM_SIZE
                            && consecutiveDelayRounds < 4;
                    if (!severeQueue) {
                        long scale = (currentDelay <= delayThresholdMs)
                                ? (cleanRounds >= 6 ? 4L : (cleanRounds >= 3 ? 2L : 1L))
                                : 1L;
                        long increment = Math.max(1L, (scale * MAX_DATAGRAM_SIZE * ackedAfterRecoveryBytes) / Math.max(congestionWindow, MAX_DATAGRAM_SIZE));
                        congestionWindow = clampWindow(congestionWindow + increment);
                    }
                }
            }
        }
        else if (cwndLimited && ackedAfterRecoveryBytes > 0L && isInSlowStart()) {
            congestionWindow = clampWindow(congestionWindow + ackedAfterRecoveryBytes);
        }
    }

    @Override
    public synchronized void registerLost(List<? extends PacketInfo> lostPackets) {
        super.registerLost(lostPackets);

        if (!lostPackets.isEmpty()) {
            PacketInfo largest = lostPackets.stream()
                    .max(Comparator.comparing(p -> p.packet().getPacketNumber()))
                    .get();
            if (largest.timeSent().isAfter(congestionRecoveryStartTime)) {
                Instant now = clock.instant();
                preLossCwnd = congestionWindow;
                preLossSsthresh = slowStartThreshold;
                hasUndoSnapshot = true;
                undoTimestamp = now;

                congestionRecoveryStartTime = now;
                congestionWindow = clampWindow((congestionWindow * 7L) / 10L);
                slowStartThreshold = congestionWindow;
                cleanRounds = 0;
                log.cc("Cwnd(-): " + congestionWindow + "; inflight: " + bytesInFlight);
            }
        }
        log.getQLog().emitCongestionControlMetrics(congestionWindow, bytesInFlight);
    }

    @Override
    public synchronized void onSpuriousLoss() {
        if (hasUndoSnapshot && undoTimestamp != null && Duration.between(undoTimestamp, clock.instant()).toMillis() <= UNDO_WINDOW_MS) {
            congestionWindow = Math.max(congestionWindow, preLossCwnd);
            slowStartThreshold = Math.max(slowStartThreshold, preLossSsthresh);
            congestionRecoveryStartTime = Instant.MIN;
            hasUndoSnapshot = false;
            log.cc("Spurious loss undo -> cwnd=" + congestionWindow);
        }
    }

    @Override
    public long getPacingRateBytesPerSecond(int rttMs) {
        int effectiveRttMs = Math.max(rttMs, 1);
        long cwnd = congestionWindow;
        boolean slowStart = cwnd < slowStartThreshold;
        double gain = slowStart ? SLOW_START_PACING_GAIN : AVOIDANCE_PACING_GAIN;
        long rate = (long) ((gain * cwnd * 1000.0) / effectiveRttMs);
        return Math.max(MIN_PACING_RATE_BPS, Math.min(MAX_PACING_RATE_BPS, rate));
    }

    public boolean isInSlowStart() {
        return congestionWindow < slowStartThreshold;
    }

    public synchronized long getSlowStartThreshold() {
        return slowStartThreshold;
    }

    public synchronized double getRecentDeliveryRateBytesPerSecond() {
        return recentDeliveryRateBps;
    }

    private long computeBdpTarget(double rateBps, int baseRttMs, int delayThresholdMs) {
        if (rateBps <= 0.0) {
            return (congestionWindow * 85L) / 100L;
        }
        long bdp = (long) (rateBps * (baseRttMs + Math.min(10, delayThresholdMs / 2)) / 1000.0);
        return clampWindow(bdp + 4L * MAX_DATAGRAM_SIZE);
    }

    private static long clampWindow(long value) {
        return Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, value));
    }
}
