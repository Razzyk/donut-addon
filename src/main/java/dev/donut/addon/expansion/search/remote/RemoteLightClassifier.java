package dev.donut.addon.expansion.search.remote;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Compact classifier for the sparse sky-light facts carried by chunk/light packets. */
public final class RemoteLightClassifier {
    public static final int MAX_SECTION_SLOTS = 64;

    public enum Status { UNKNOWN, EMPTY_MASK, ZEROED_PRESENT, UNIFORM_MAX, NORMAL }
    public enum Evidence { NONE, BASE_SIGNAL, SUSPECT_DARK, SUSPECT_AIRLESS }

    /** One immutable, bounded packet summary suitable for transfer from the network thread. */
    public record PacketSummary(byte[] skyStatuses, int sentNotDarkBits) {
        public PacketSummary {
            skyStatuses = Arrays.copyOf(skyStatuses, Math.min(skyStatuses.length, MAX_SECTION_SLOTS));
        }
        @Override public byte[] skyStatuses() { return Arrays.copyOf(this.skyStatuses, this.skyStatuses.length); }
    }

    /** Merged state for one chunk. Unknown packet slots leave earlier observations intact. */
    public record Snapshot(byte[] skyStatuses, int sentNotDarkBits, long updatedNanos, boolean observedUnderground) {
        public Snapshot {
            skyStatuses = Arrays.copyOf(skyStatuses, Math.min(skyStatuses.length, MAX_SECTION_SLOTS));
        }
        @Override public byte[] skyStatuses() { return Arrays.copyOf(this.skyStatuses, this.skyStatuses.length); }
    }

    private RemoteLightClassifier() {}

    public static Status classifyPayload(byte[] payload) {
        if (payload == null || payload.length != 2048) return Status.UNKNOWN;
        boolean zero = true;
        boolean maximum = true;
        for (byte value : payload) {
            zero &= value == 0;
            maximum &= value == (byte) 0xff;
            if (!zero && !maximum) return Status.NORMAL;
        }
        if (zero) return Status.ZEROED_PRESENT;
        return maximum ? Status.UNIFORM_MAX : Status.NORMAL;
    }

    /**
     * Summarize the sparse sky track without retaining packet-owned arrays. Payloads correspond
     * to set bits in ascending section order; empty-mask bits carry no payload.
     */
    public static PacketSummary summarize(BitSet present, BitSet empty, List<byte[]> payloads) {
        byte[] statuses = new byte[MAX_SECTION_SLOTS];
        Arrays.fill(statuses, (byte) Status.UNKNOWN.ordinal());
        int notDark = 0;
        int payloadIndex = 0;
        int high = Math.min(MAX_SECTION_SLOTS, Math.max(present.length(), empty.length()));
        for (int index = 0; index < high; index++) {
            if (present.get(index)) {
                byte[] bytes = payloadIndex < payloads.size() ? payloads.get(payloadIndex) : null;
                payloadIndex++;
                Status status = classifyPayload(bytes);
                statuses[index] = (byte) status.ordinal();
                if (status == Status.NORMAL || status == Status.UNIFORM_MAX) notDark |= 1 << index;
            } else if (empty.get(index)) {
                statuses[index] = (byte) Status.EMPTY_MASK.ordinal();
                notDark |= 1 << index;
            }
        }
        return new PacketSummary(statuses, notDark);
    }

    public static Snapshot merge(Snapshot previous, PacketSummary packet, long nowNanos, boolean underground) {
        byte[] next = previous == null ? new byte[MAX_SECTION_SLOTS] : previous.skyStatuses();
        if (previous == null) Arrays.fill(next, (byte) Status.UNKNOWN.ordinal());
        byte[] incoming = packet.skyStatuses;
        for (int i = 0; i < Math.min(next.length, incoming.length); i++) {
            if (decode(incoming[i]) != Status.UNKNOWN) next[i] = incoming[i];
        }
        return new Snapshot(next, (previous == null ? 0 : previous.sentNotDarkBits) | packet.sentNotDarkBits,
            nowNanos, underground || previous != null && previous.observedUnderground);
    }

    /**
     * Classify only complete sections whose top is below Y=62. `airlessSection` is queried only
     * for zeroed sections that were not accompanied by a non-dark light payload/mask.
     */
    public static Evidence classify(Snapshot snapshot, int sectionCount, int bottomSectionY,
                                    AirlessSection airlessSection) {
        if (snapshot == null || sectionCount <= 0) return Evidence.NONE;
        byte[] statuses = snapshot.skyStatuses;
        boolean foundBase = false;
        boolean turnedDark = false;
        boolean airless = false;
        int slots = Math.min(Math.min(sectionCount + 1, statuses.length), MAX_SECTION_SLOTS);
        for (int index = 1; index < slots; index++) {
            int sectionY = bottomSectionY + index - 1;
            if ((sectionY << 4) + 15 >= 62 || decode(statuses[index]) != Status.ZEROED_PRESENT) continue;
            foundBase = true;
            if ((snapshot.sentNotDarkBits & (1 << index)) != 0) {
                turnedDark = true;
            } else if (airlessSection.isAirless(sectionY)) {
                airless = true;
            } else {
                // One ordinary hidden-light section is enough to keep the chunk in the lower-
                // confidence base group even when another section has stronger evidence.
                return Evidence.BASE_SIGNAL;
            }
        }
        if (!foundBase) return Evidence.NONE;
        if (turnedDark) return Evidence.SUSPECT_DARK;
        if (airless) return Evidence.SUSPECT_AIRLESS;
        return Evidence.BASE_SIGNAL;
    }

    public static Status decode(byte value) {
        int ordinal = Byte.toUnsignedInt(value);
        return ordinal < Status.values().length ? Status.values()[ordinal] : Status.UNKNOWN;
    }

    @FunctionalInterface
    public interface AirlessSection { boolean isAirless(int sectionY); }
}
