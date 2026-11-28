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
package tech.kwik.core.send;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PacketPacerTest {

    @Test
    void initialBurstAllowsFourPacketsAndFifthPacesNext() {
        AtomicLong nowNanos = new AtomicLong(1_000_000_000L);
        PacketPacer pacer = new PacketPacer(1200, nowNanos::get);
        long pacingRateBps = 1_200_000L; // 1.2 MB/s -> 1 ms per 1200B packet
        long cwnd = 64 * 1200L;

        // 4 * 1200 idle burst: first 5 calls see budget >= 0 (4800 -> 3600 -> 2400 -> 1200 -> 0 -> -1200)
        for (int i = 0; i < 5; i++) {
            assertThat(pacer.canSendData(nowNanos.get(), pacingRateBps, cwnd)).isTrue();
            pacer.onDataPacketSent(1200, nowNanos.get(), pacingRateBps, cwnd);
        }

        // Next packet is blocked and delayed until deficit (-1200) refills
        assertThat(pacer.canSendData(nowNanos.get(), pacingRateBps, cwnd)).isFalse();
        assertThat(pacer.getDelayMillis(nowNanos.get(), pacingRateBps, cwnd)).isEqualTo(1L);

        // Advance by 1 ms: budget refills +1200 -> 0 -> canSendData is true again
        nowNanos.addAndGet(1_000_000L);
        assertThat(pacer.canSendData(nowNanos.get(), pacingRateBps, cwnd)).isTrue();
    }

    @Test
    void idleRefillCapsAtFourPacketsWhileActiveTimerTickAllowsUpToHalfCwnd() {
        AtomicLong nowNanos = new AtomicLong(1_000_000_000L);
        PacketPacer pacer = new PacketPacer(1200, nowNanos::get);
        long pacingRateBps = 10_000_000L; // 10 MB/s
        long cwnd = 40 * 1200L; // 48,000 B -> active cap min(24,000, 38,400) = 24,000 B

        // Long idle period does not allow exceeding 4 * 1200 idle burst
        pacer.setPacedPending(false);
        nowNanos.addAndGet(500_000_000L);
        assertThat(pacer.canSendData(nowNanos.get(), pacingRateBps, cwnd)).isTrue();
        assertThat(pacer.getBudgetBytes()).isEqualTo(4.0 * 1200);

        // Drain into deficit and enter pacedPending state
        for (int i = 0; i < 5; i++) {
            pacer.onDataPacketSent(1200, nowNanos.get(), pacingRateBps, cwnd);
        }
        pacer.setPacedPending(true);

        // Simulate a 15ms OS timer wakeup: active cap allows recovering up to cwnd/2 (24,000 B)
        nowNanos.addAndGet(15_000_000L);
        assertThat(pacer.canSendData(nowNanos.get(), pacingRateBps, cwnd)).isTrue();
        assertThat(pacer.getBudgetBytes()).isEqualTo(cwnd / 2.0);
    }

    @Test
    void activeWakeupBurstIsNotClampedWhenSendingMultiplePackets() {
        AtomicLong nowNanos = new AtomicLong(1_000_000_000L);
        PacketPacer pacer = new PacketPacer(1200, nowNanos::get);
        long pacingRateBps = 10_000_000L;
        long cwnd = 40 * 1200L; // 48,000 B -> active cap = 24,000 B

        for (int i = 0; i < 5; i++) {
            pacer.onDataPacketSent(1200, nowNanos.get(), pacingRateBps, cwnd);
        }
        pacer.setPacedPending(true);

        // 15ms OS timer wakeup refills up to cwnd / 2 = 24,000 B
        nowNanos.addAndGet(15_000_000L);
        assertThat(pacer.getDelayMillis(nowNanos.get(), pacingRateBps, cwnd)).isEqualTo(0L);

        // Even if pacedPending was cleared right before sending, sending at the same timestamp must NOT clamp 24,000 -> 4,800
        pacer.setPacedPending(false);
        pacer.onDataPacketSent(1200, nowNanos.get(), pacingRateBps, cwnd);
        assertThat(pacer.getBudgetBytes()).isEqualTo(cwnd / 2.0 - 1200.0);
    }
}
