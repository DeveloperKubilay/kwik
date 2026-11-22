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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kwik.core.impl.MockPacket;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.packet.PacketInfo;
import tech.kwik.core.packet.QuicPacket;
import tech.kwik.core.recovery.RttEstimator;
import tech.kwik.core.test.TestClock;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DelayAwareCongestionControllerTest {

    private TestClock clock;
    private RttEstimator rttEstimator;
    private DelayAwareCongestionController controller;

    @BeforeEach
    void setup() {
        clock = new TestClock();
        clock.fastForward(1000);
        rttEstimator = new RttEstimator(new NullLogger());
        controller = new DelayAwareCongestionController(clock, rttEstimator, new NullLogger(), mock(CongestionControlEventListener.class));
    }

    @Test
    void cwndGrowsEvenWithSubMssOrPacingSlack() {
        // Reproduces the bug where NewReno epsilon=3 refused growth when cwnd - bytesInFlight was 4..1199 bytes
        long initialCwnd = controller.getWindowSize();
        QuicPacket p1 = new MockPacket(0, 10800, "");
        controller.registerInFlight(p1);
        // remainingCwnd is 1200 > 3, so old NewReno failed to grow on slow-start ACKs!
        QuicPacket p2 = new MockPacket(1, 1000, "");
        controller.registerInFlight(p2);

        controller.registerAcked(List.of(new PacketInfo(clock.instant().minusMillis(20), p2, p -> {})));

        assertThat(controller.getWindowSize()).isEqualTo(initialCwnd + 1000);
    }

    @Test
    void hystartExitsSlowStartWhenStandingQueueGrowsPastThreshold() {
        // Grow cwnd >= 16 * 1200 in baseline 20ms RTT
        for (int i = 0; i < 12; i++) {
            QuicPacket p = new MockPacket(i, 1200, "");
            controller.registerInFlight(p);
            Instant sent = clock.instant();
            clock.fastForward(20);
            rttEstimator.addSample(clock.instant(), sent, 0);
            if (controller.remainingCwnd() > 3600) {
                QuicPacket filler = new MockPacket(100 + i, (int) (controller.remainingCwnd() - 1200), "");
                controller.registerInFlight(filler);
            }
            controller.registerAcked(List.of(new PacketInfo(sent, p, x -> {})));
        }
        assertThat(controller.isInSlowStart()).isTrue();
        assertThat(controller.getWindowSize()).isGreaterThanOrEqualTo(16 * 1200L);

        // Now queue builds up: min RTT within round jumps from 20ms to 65ms across >= 4 samples and >= 1 RTT
        Instant roundStart = clock.instant();
        for (int i = 0; i < 6; i++) {
            QuicPacket p = new MockPacket(200 + i, 1200, "");
            controller.registerInFlight(p);
            Instant sent = clock.instant();
            clock.fastForward(65);
            rttEstimator.addSample(clock.instant(), sent, 0);
            controller.registerAcked(List.of(new PacketInfo(sent, p, x -> {})));
        }
        assertThat(clock.instant()).isAfter(roundStart);
        assertThat(controller.isInSlowStart()).isFalse();
        assertThat(controller.getSlowStartThreshold()).isLessThan(Long.MAX_VALUE);
    }

    @Test
    void randomJitterWithLowRoundMinRttDoesNotFalseTriggerDelayBackoff() {
        // Put into CongestionAvoidance via hystart window first or loss recovery
        QuicPacket seed = new MockPacket(0, 1200, "");
        controller.registerInFlight(seed);
        Instant t0 = clock.instant();
        clock.fastForward(20);
        rttEstimator.addSample(clock.instant(), t0, 0);
        controller.registerAcked(List.of(new PacketInfo(t0, seed, p -> {})));

        // Grow cwnd cleanly so loss puts ssthresh above 4 * MSS
        for (int i = 1; i <= 15; i++) {
            QuicPacket p = new MockPacket(i, 1200, "");
            controller.registerInFlight(p);
            controller.registerAcked(List.of(new PacketInfo(clock.instant().minusMillis(20), p, x -> {})));
        }
        QuicPacket lost = new MockPacket(99, 1200, "");
        controller.registerInFlight(lost);
        controller.registerLost(List.of(new PacketInfo(clock.instant(), lost, p -> {})));
        assertThat(controller.isInSlowStart()).isFalse();
        long cwndAfterLoss = controller.getWindowSize();

        // Simulate random jitter where some samples spike to 90ms (inflating rttVar),
        // but minimum in the round stays 21ms (~minRtt=20ms) -> queueDelay is ~1ms, no throttle!
        int[] samples = { 21, 90, 22, 85, 20, 88 };
        clock.fastForward(100); // pass recovery
        for (int i = 0; i < samples.length; i++) {
            QuicPacket p = new MockPacket(300 + i, 1200, "");
            controller.registerInFlight(p);
            Instant sent = clock.instant();
            clock.fastForward(samples[i]);
            rttEstimator.addSample(clock.instant(), sent, 0);
            controller.registerAcked(List.of(new PacketInfo(sent, p, x -> {})));
        }
        assertThat(controller.getWindowSize()).isGreaterThanOrEqualTo(cwndAfterLoss);
    }

    @Test
    void lossUsesPointSevenFactorWithFourMssFloorAndSpuriousLossRestoresCwnd() {
        // Establish > 12,000 byte cwnd by keeping cwnd utilized while ACKs arrive
        QuicPacket[] packets = new QuicPacket[8];
        for (int i = 0; i < packets.length; i++) {
            packets[i] = new MockPacket(i, 1200, "");
            controller.registerInFlight(packets[i]);
        }
        for (QuicPacket p : packets) {
            controller.registerAcked(List.of(new PacketInfo(clock.instant(), p, x -> {})));
        }
        long cwndBeforeLoss = controller.getWindowSize();
        assertThat(cwndBeforeLoss).isGreaterThan(12_000L);

        QuicPacket lost = new MockPacket(50, 1200, "");
        controller.registerInFlight(lost);
        clock.fastForward(10);
        controller.registerLost(List.of(new PacketInfo(clock.instant(), lost, p -> {})));

        assertThat(controller.getWindowSize()).isEqualTo(Math.max(4 * 1200L, (cwndBeforeLoss * 7L) / 10L));

        // Spurious loss detected within 3 seconds restores previous cwnd
        clock.fastForward(50);
        controller.onSpuriousLoss();
        assertThat(controller.getWindowSize()).isEqualTo(cwndBeforeLoss);
    }
}
