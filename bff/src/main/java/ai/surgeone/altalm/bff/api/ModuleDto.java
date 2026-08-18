package ai.surgeone.altalm.bff.api;

import java.util.List;

/** Wire shapes for ALM's navigation rail and what Alt-ALM can actually do with each entry. */
public final class ModuleDto {

    private ModuleDto() {
    }

    /**
     * Why an entry is not simply openable. Three distinct kinds of "no", and conflating them is the
     * whole risk this type exists to avoid.
     *
     * <p>"We have not built it" and "no documented API reaches it" are different promises: the first
     * will arrive, the second will not arrive in any deployment without a Windows sidecar. Rendering
     * them identically would quietly commit the project to shipping something it cannot ship.
     */
    public enum Reach {
        /** The BFF can read it today. */
        READABLE,
        /** A documented REST read path exists, but nothing in this build uses it yet. */
        BUILDABLE,
        /** Reachable only over OTA/COM — needs the P6 Windows sidecar (ADR 0003). */
        NEEDS_SIDECAR,
        /** No documented API reaches it at all. */
        NO_API
    }

    /**
     * One rail entry.
     *
     * @param key        stable id
     * @param label      ALM's own name for it
     * @param collection the collection to open, or empty when there is nothing to open
     * @param reach      what stands between the user and this entry
     * @param reason     one sentence naming the evidence, shown on hover. Empty when READABLE
     */
    public record Item(String key, String label, String collection, Reach reach, String reason) {
    }

    /** One rail group, as the stock client groups it. A blank name means an ungrouped entry. */
    public record Group(String name, List<Item> items) {
    }

    public record Rail(List<Group> groups) {
    }
}
