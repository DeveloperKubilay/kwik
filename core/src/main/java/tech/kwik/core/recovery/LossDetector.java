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
package tech.kwik.core.recovery;

import tech.kwik.core.cc.CongestionController;
import tech.kwik.core.frame.AckFrame;
import tech.kwik.core.log.QLog;
import tech.kwik.core.packet.PacketInfo;
import tech.kwik.core.packet.QuicPacket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class LossDetector {

    private static final int MAX_PACKET_THRESHOLD = 64;
    private static final float MAX_TIME_THRESHOLD = 2.0f;
    private static final int MAX_RECENTLY_LOST = 128;

    private final Clock clock;
    private final RecoveryManager recoveryManager;
    private final RttEstimator rttEstimater;
    private final CongestionController congestionController;
    private final Runnable postProcessLostCallback;
    private final QLog qLog;
    private final boolean adaptiveReordering;
    private final float kTimeThreshold = 9f/8f;
    private final int kPacketThreshold = 3;
    private volatile float timeThreshold = kTimeThreshold;
    private volatile int packetThreshold = kPacketThreshold;
    // https://www.rfc-editor.org/rfc/rfc9002.html#section-a.2
    // "Timer granularity. This is a system-dependent value, and Section 6.1.2 recommends a value of 1 ms."
    private final int kGranularity = 1;
    private final SortedMap<Long, PacketStatus> packetSentLog;
    private final Map<Long, LostPacketRecord> recentlyLost;
    private final AtomicInteger ackElicitingInFlight;
    private volatile long largestAcked = -1;
    private volatile long lost;
    private volatile Instant lossTime;
    private volatile Instant lastAckElicitingSent;
    private volatile boolean isClosed;


    public LossDetector(RecoveryManager recoveryManager, RttEstimator rttEstimator, CongestionController congestionController, Runnable postProcessLostCallback, QLog qLog) {
        this(Clock.systemUTC(), recoveryManager, rttEstimator, congestionController, postProcessLostCallback, qLog, false);
    }

    public LossDetector(Clock clock, RecoveryManager recoveryManager, RttEstimator rttEstimator, CongestionController congestionController, Runnable postProcessLostCallback, QLog qLog) {
        this(clock, recoveryManager, rttEstimator, congestionController, postProcessLostCallback, qLog, false);
    }

    public LossDetector(Clock clock, RecoveryManager recoveryManager, RttEstimator rttEstimator, CongestionController congestionController, Runnable postProcessLostCallback, QLog qLog, boolean adaptiveReordering) {
        this.clock = clock;
        this.recoveryManager = recoveryManager;
        this.rttEstimater = rttEstimator;
        this.congestionController = congestionController;
        this.postProcessLostCallback = postProcessLostCallback;
        this.qLog = qLog;
        this.adaptiveReordering = adaptiveReordering;

        ackElicitingInFlight = new AtomicInteger();
        packetSentLog = new ConcurrentSkipListMap<>();
        recentlyLost = Collections.synchronizedMap(new LinkedHashMap<Long, LostPacketRecord>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, LostPacketRecord> eldest) {
                return size() > MAX_RECENTLY_LOST;
            }
        });
    }

    public synchronized void packetSent(QuicPacket packet, Instant sent, Consumer<QuicPacket> lostPacketCallback) {
        if (isClosed) {
            return;
        }

        // https://www.rfc-editor.org/rfc/rfc9002.html#section-2
        // "Packets are considered in flight when they are ack-eliciting or contain a PADDING frame, and ..."
        assert packet.isInflightPacket();

        congestionController.registerInFlight(packet);

        if (packet.isAckEliciting()) {
            ackElicitingInFlight.getAndAdd(1);
            lastAckElicitingSent = sent;
        }

        // This method is synchronized, because during a reset operation, no new packets must be logged as sent.
        packetSentLog.put(packet.getPacketNumber(), new PacketStatus(sent, packet, lostPacketCallback));
    }

    public void onAckReceived(AckFrame ackFrame, Instant timeReceived) {
        if (isClosed) {
            return;
        }

        largestAcked = Long.max(largestAcked, ackFrame.getLargestAcknowledged());

        if (adaptiveReordering) {
            checkSpuriousLosses(ackFrame, timeReceived);
        }

        List<PacketStatus> newlyAcked = determineNewlyAcked(ackFrame);
        if (newlyAcked.isEmpty()) {
             return;
        }

        int ackedAckEliciting = (int) newlyAcked.stream().filter(packetStatus -> packetStatus.packet().isAckEliciting()).count();
        assert ackedAckEliciting <= ackElicitingInFlight.get();
        ackElicitingInFlight.getAndAdd(-1 * ackedAckEliciting);

        rttEstimater.ackReceived(ackFrame, timeReceived, newlyAcked);

        detectLostPackets();

        congestionController.registerAcked(filterInFlight(newlyAcked));

        recoveryManager.setLossDetectionTimer();

        // Cleanup
        newlyAcked.stream().forEach(p -> packetSentLog.remove(p.packet().getPacketNumber()));
    }

    private void checkSpuriousLosses(AckFrame ackFrame, Instant timeReceived) {
        long smallestTrackedPn;
        synchronized (recentlyLost) {
            if (recentlyLost.isEmpty()) {
                return;
            }
            smallestTrackedPn = Collections.min(recentlyLost.keySet());
        }
        List<Long> ackedTrackedPns = ackFrame.getAckedPacketNumbers(smallestTrackedPn)
                .collect(Collectors.toList());
        boolean detectedSpuriousLoss = false;
        for (Long pn : ackedTrackedPns) {
            LostPacketRecord record = recentlyLost.remove(pn);
            if (record != null) {
                detectedSpuriousLoss = true;
                int reorderSpan = (int) Long.min(MAX_PACKET_THRESHOLD, Long.max(1L, record.largestAckedAtLoss - pn + 2L));
                packetThreshold = Integer.min(MAX_PACKET_THRESHOLD, Integer.max(packetThreshold + 2, reorderSpan));
                if (Duration.between(record.timeSent, timeReceived).toMillis() >= record.lossDelayMs) {
                    timeThreshold = Float.min(MAX_TIME_THRESHOLD, timeThreshold + 0.25f);
                }
            }
        }
        if (detectedSpuriousLoss) {
            congestionController.onSpuriousLoss();
        }
    }

    /**
     * Determine which packets were newly acked by the given AckFrame and mark them as acked.
     * @param ackFrame
     * @return
     */
    private List<PacketStatus> determineNewlyAcked(AckFrame ackFrame) {
        long smallestLoggedPacketNumber = packetSentLog.isEmpty() ? Long.MAX_VALUE : packetSentLog.firstKey();
        return ackFrame.getAckedPacketNumbers(smallestLoggedPacketNumber)
                .map(pn -> packetSentLog.get(pn))
                .filter(packetStatus -> packetStatus != null)      // Could be null if the packet was already removed from the log
                .filter(packetStatus -> !packetStatus.acked())     // Only keep the ones that are not acked yet
                .filter(packetStatus -> packetStatus.setAcked())   // Only keep the ones that actually got set to acked
                .collect(Collectors.toList());
    }

    public synchronized void close() {
        List<PacketStatus> inflightPackets = packetSentLog.values().stream()
                .filter(packet -> packet.inFlight())
                .filter(packetStatus -> packetStatus.setLost())   // Only keep the ones that actually were set to lost
                .collect(Collectors.toList());
        congestionController.discard(inflightPackets);
        ackElicitingInFlight.set(0);
        packetSentLog.clear();
        recentlyLost.clear();
        lossTime = null;
        lastAckElicitingSent = null;
        isClosed = true;
    }

    /**
     * Reset to initial state.
     */
    public synchronized void reset() {
        List<PacketStatus> inflightPackets = packetSentLog.values().stream()
                .filter(packet -> packet.inFlight())
                .filter(packetStatus -> packetStatus.setLost())   // Only keep the ones that actually were set to lost
                .collect(Collectors.toList());
        congestionController.discard(inflightPackets);
        packetSentLog.clear();
        recentlyLost.clear();
        ackElicitingInFlight.set(0);
        lossTime = null;
        lastAckElicitingSent = null;
        largestAcked = -1;
        lost = 0;
        packetThreshold = kPacketThreshold;
        timeThreshold = kTimeThreshold;
    }

    /**
     * Detect lost packets.
     * Intentionally package protected, because it needs to be called by the loss detection timer also.
     */
    void detectLostPackets() {
        if (isClosed) {
            return;
        }

        int lossDelay = (int) (timeThreshold * Integer.max(rttEstimater.getSmoothedRtt(), rttEstimater.getLatestRtt()));
        // https://www.rfc-editor.org/rfc/rfc9002.html#section-6.1.2
        // "To avoid declaring packets as lost too early, this time threshold MUST be set to at least the local timer
        //  granularity, as indicated by the kGranularity constant. "
        lossDelay = Integer.max(lossDelay, kGranularity);
        Instant lostSendTime = Instant.now(clock).minusMillis(lossDelay);

        // https://www.rfc-editor.org/rfc/rfc9002.html#section-6.1
        // "A packet is declared lost if it meets all of the following conditions:
        //  o  The packet is unacknowledged, in flight, and was sent prior to an acknowledged packet.
        //  o  The packet was sent kPacketThreshold packets before an acknowledged packet (Section 6.1.1),
        //     or it was sent long enough in the past (Section 6.1.2)."
        // https://www.rfc-editor.org/rfc/rfc9002.html#section-2
        // "Packets are considered in flight when they are ack-eliciting or contain a PADDING frame,
        //  and they have been sent but are not acknowledged, declared lost, or discarded along with old keys."
        Iterator<PacketStatus> iterator = packetSentLog.values().iterator();
        List<PacketStatus> lostPackets = new ArrayList<>();
        Instant earliestSentTime = null;
        while (iterator.hasNext()) {
            PacketStatus p = iterator.next();
            if (p.packet().getPacketNumber() > largestAcked) {
                break;  // No need to check further, because packets are ordered by packet number
            }
            if (p.inFlight()) {
                if (pnTooOld(p) || sentTimeTooLongAgo(p, lostSendTime)) {
                    lostPackets.add(p);
                }
                else {
                    if (earliestSentTime == null || p.timeSent().isBefore(earliestSentTime)) {
                        earliestSentTime = p.timeSent();
                    }
                }
            }
        }

        if (!lostPackets.isEmpty()) {
            declareLost(lostPackets, lossDelay);
        }

        if (earliestSentTime != null && earliestSentTime.isAfter(lostSendTime)) {
            lossTime = earliestSentTime.plusMillis(lossDelay);
        }
        else {
            lossTime = null;
        }
    }

    Instant getLossTime() {
        return lossTime;
    }

    Instant getLastAckElicitingSent() {
        return lastAckElicitingSent;
    }

    boolean ackElicitingInFlight() {
        int actualAckElicitingInFlight = ackElicitingInFlight.get();
        assert actualAckElicitingInFlight >= 0;
        return actualAckElicitingInFlight != 0;
    }

    List<QuicPacket> unAcked() {
        return packetSentLog.values().stream()
                .filter(p -> p.inFlight())
                .map(p -> p.packet())
                .collect(Collectors.toList());
    }

    // For debugging only
    List<PacketInfo> getInFlight() {
        return packetSentLog.values().stream()
                .filter(p -> p.inFlight())
                .collect(Collectors.toList());
    }

    private boolean pnTooOld(PacketStatus p) {
        return p.packet().getPacketNumber() <= largestAcked - packetThreshold;
    }

    private boolean sentTimeTooLongAgo(PacketStatus p, Instant lostSendTime) {
        return p.packet().getPacketNumber() <= largestAcked && p.timeSent().isBefore(lostSendTime);
    }

    private void declareLost(List<PacketStatus> lostPacketsInfo, int lossDelayMs) {
        lostPacketsInfo = lostPacketsInfo.stream()
                .filter(packetStatus -> packetStatus.setLost())   // Only keep the ones that actually were set to lost
                .collect(Collectors.toList());

        int lostAckEliciting = (int) lostPacketsInfo.stream().filter(packetStatus -> packetStatus.packet().isAckEliciting()).count();
        assert lostAckEliciting <= ackElicitingInFlight.get();
        ackElicitingInFlight.getAndAdd(-1 * lostAckEliciting);

        lostPacketsInfo.stream()
                .forEach(packetStatus -> {
                    // Retransmitting the frames in the lost packet is delegated to the lost frame callback, because
                    // whether retransmitting the frame is necessary (and in which manner) depends on frame type,
                    // see https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-13.3
                    packetStatus.lostPacketCallback().accept(packetStatus.packet());
                    lost++;
                    qLog.emitPacketLostEvent(packetStatus.packet(), Instant.now());
                });
        postProcessLostCallback.run();

        congestionController.registerLost(filterInFlight(lostPacketsInfo));

        if (adaptiveReordering) {
            long largestAtLoss = largestAcked;
            for (PacketStatus p : lostPacketsInfo) {
                if (p.packet().isInflightPacket()) {
                    recentlyLost.put(p.packet().getPacketNumber(), new LostPacketRecord(largestAtLoss, p.timeSent(), lossDelayMs));
                }
            }
        }

        // Cleanup
        lostPacketsInfo.stream().forEach(p -> packetSentLog.remove(p.packet().getPacketNumber()));
    }

    private List<PacketStatus> filterInFlight(List<PacketStatus> packets) {
        return packets.stream()
                .filter(packetInfo -> packetInfo.packet().isInflightPacket())
                .collect(Collectors.toList());
    }

    public long getLost() {
        return lost;
    }

    public boolean noAckedReceived() {
        return largestAcked < 0;
    }

    private static final class LostPacketRecord {
        private final long largestAckedAtLoss;
        private final Instant timeSent;
        private final int lossDelayMs;

        private LostPacketRecord(long largestAckedAtLoss, Instant timeSent, int lossDelayMs) {
            this.largestAckedAtLoss = largestAckedAtLoss;
            this.timeSent = timeSent;
            this.lossDelayMs = lossDelayMs;
        }
    }

}
