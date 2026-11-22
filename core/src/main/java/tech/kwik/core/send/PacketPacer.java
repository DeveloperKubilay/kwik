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

import java.util.function.LongSupplier;

/**
 * Token-bucket packet pacer (RFC 9002 §7.7) for smoothing congestion-controlled QUIC bursts
 * while letting interactive small bursts, ACKs, probes, and handshake packets leave immediately.
 */
final class PacketPacer {

    private final int maxPacketSize;
    private final long idleBurstBytes;
    private final LongSupplier nanoTimeSupplier;
    private double budgetBytes;
    private long lastRefillNanos;
    private boolean pacedPending;

    PacketPacer(int maxPacketSize) {
        this(maxPacketSize, System::nanoTime);
    }

    PacketPacer(int maxPacketSize, LongSupplier nanoTimeSupplier) {
        this.maxPacketSize = Math.max(1200, maxPacketSize);
        this.idleBurstBytes = 4L * this.maxPacketSize;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.budgetBytes = this.idleBurstBytes;
        this.lastRefillNanos = nanoTimeSupplier.getAsLong();
    }

    boolean canSendData(long nowNanos, long pacingRateBps, long currentCwnd) {
        refill(nowNanos, pacingRateBps, currentCwnd, !pacedPending);
        return budgetBytes >= 0.0;
    }

    void onDataPacketSent(int bytes, long nowNanos, long pacingRateBps, long currentCwnd) {
        refill(nowNanos, pacingRateBps, currentCwnd, !pacedPending);
        budgetBytes -= bytes;
    }

    long getDelayMillis(long nowNanos, long pacingRateBps, long currentCwnd) {
        refill(nowNanos, pacingRateBps, currentCwnd, !pacedPending);
        if (budgetBytes >= 0.0) {
            return 0L;
        }
        long rate = Math.max(1L, pacingRateBps);
        long delayNanos = (long) Math.ceil((-budgetBytes / (double) rate) * 1_000_000_000.0);
        return Math.max(1L, (delayNanos + 999_999L) / 1_000_000L);
    }

    void setPacedPending(boolean pacedPending) {
        this.pacedPending = pacedPending;
    }

    boolean isPacedPending() {
        return pacedPending;
    }

    double getBudgetBytes() {
        return budgetBytes;
    }

    private void refill(long nowNanos, long pacingRateBps, long currentCwnd, boolean wasIdle) {
        long elapsedNanos = Math.max(0L, nowNanos - lastRefillNanos);
        lastRefillNanos = nowNanos;
        long rate = Math.max(1L, pacingRateBps);
        double added = (elapsedNanos * (double) rate) / 1_000_000_000.0;
        double cap = wasIdle
                ? idleBurstBytes
                : Math.max(idleBurstBytes, Math.min(Math.max(currentCwnd / 2.0, idleBurstBytes), 32.0 * maxPacketSize));
        budgetBytes = Math.min(cap, budgetBytes + added);
    }
}
