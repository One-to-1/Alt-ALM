package ai.surgeone.altalm.bff.alm.metadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Reduces the raw relation list to the related-entity tabs a detail pane should offer.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Probe 21.6 concluded that {@code customization/entities/{e}/relations/} "enumerates the
 * related-entity tabs". That is true in the sense that every tab is in there, and misleading in the
 * sense that so is a great deal else: the sandbox returns <strong>22</strong> relations for
 * {@code requirement} where the stock dialog shows <strong>6</strong> related tabs, 27 for
 * {@code test}, and 17 for {@code defect}. Rendering the raw list would produce a detail pane with a
 * "Requirement to Requirement Target Cycle" tab next to "Attachments".
 *
 * <p>So this is a <strong>reduction with stated rules</strong>, exactly parallel to the field-set
 * finding in probe 21.3: the API tells us the candidates; it does not tell us the form. The rules
 * below are chosen to be derivable from the payload rather than from a list of entity names, so they
 * keep working on a project whose customization we have never seen (ADR 0005).
 *
 * <h2>The rules</h2>
 *
 * <ol>
 *   <li><strong>An unlabelled relation is not a tab.</strong> Probe 22 found 5 of {@code defect}'s 17
 *       relations carry no {@code Label}, and every one is a field-backed reference — target release,
 *       detected-in cycle, environment. Those belong on the Details form, where they already are.
 *       (This corrects probe 21.6's "each carrying a human-readable Label", which was measured on
 *       {@code requirement} only.)</li>
 *   <li><strong>A self-referential containment is the hierarchy, not a tab.</strong>
 *       {@code requirementToRequirementContainment} is the parent/child tree the TreeGrid already
 *       renders. Only {@code containment} is dropped this way — a self-referential {@code connection}
 *       such as defect-to-defect linking is a genuine tab.</li>
 *   <li><strong>A tab that cannot be filled is not shown.</strong> The caller supplies the predicate
 *       for what this BFF can actually read, so a relation to {@code deleted-asset-info} or
 *       {@code user-asset} drops out because nothing can populate it — not because its name was
 *       blocklisted here.</li>
 *   <li><strong>Group by the pair (far end, entity read).</strong> Both halves are needed, and probe
 *       22 is why. The obvious rule — group by what gets read — collapses the forward/mirrored pairs
 *       correctly, but {@code defect-link} and {@code assets-relation} are <strong>polymorphic join
 *       tables</strong>: nine of {@code defect}'s relations read {@code defect-link} to nine
 *       different far ends, so that rule would merge "Linked Runs" and "Linked Tests" into a single
 *       tab. Grouping by the far end alone fails the other way, merging Business Models Linkage into
 *       Trace because both end at {@code requirement}. The pair keeps both apart.</li>
 *   <li><strong>Then merge groups that share a label.</strong> {@code requirement} reaches defects
 *       two ways — a {@code defect} connection through {@code defect-link}, and a direct
 *       {@code defect-link} link — and labels both "Linked Defects". They are one tab.</li>
 * </ol>
 *
 * <h2>What this gets wrong, on purpose</h2>
 *
 * <p>It <strong>over-shows</strong>. The sandbox's {@code requirement} reduces to 8 related tabs
 * where the stock dialog has 5. All three extras have one shape: a join entity reachable both
 * directly and through an association, which ALM presents as a single tab —
 * {@code req-trace} + {@code req-trace:requirement}, {@code requirement-coverage} +
 * {@code requirement-coverage:test}, {@code bpm-link} + {@code bpm-link:requirement}.
 *
 * <p><strong>The rule that would merge those is the rule that breaks {@code defect}.</strong>
 * Collapsing groups that share a read entity fixes all three, and simultaneously folds defect's nine
 * legitimately distinct {@code defect-link} tabs — Linked Runs, Linked Tests, Linked Requirements —
 * into one. No rule derivable from this payload gets both right, because ALM's tab organisation is
 * per-entity and lives in workflow scripts no API serves (probe 21.8). This is the documented limit
 * of the approach, not a to-do.
 *
 * <p>So the error runs in the deliberate direction. The two failure modes are not symmetric: an
 * extra tab is visible and dismissible, while a wrong merge — Business Models Linkage folded into
 * Trace — silently shows one relation's rows under another's name. This class prefers the visible
 * error, and {@link Selection#dropped()} keeps the discarded candidates inspectable so the invisible
 * one stays auditable too.
 */
public final class AlmRelationSelector {

    private AlmRelationSelector() {
    }

    /**
     * One related-entity tab: a label, and the relations that back it.
     *
     * @param key       stable identifier for the tab, derived from the read entity so it survives a
     *                  label change (labels are per-project customization)
     * @param label     what to show; picked from the backing relations by {@link #bestLabel}
     * @param readEntity the entity whose rows fill the tab
     * @param relations every relation merged into this tab, server order, never empty
     */
    public record Tab(String key, String label, String readEntity, List<AlmRelation> relations) {

        public Tab {
            relations = List.copyOf(relations);
            if (relations.isEmpty()) {
                throw new IllegalArgumentException("a tab must be backed by at least one relation");
            }
        }

        /** True when ALM classifies this as the attachment relation — it reads as a sub-resource. */
        public boolean isAttachment() {
            return relations.stream()
                    .anyMatch(r -> AlmRelation.TYPE_ATTACHMENT.equals(r.type()));
        }
    }

    /**
     * The outcome of a reduction: what became a tab, and what did not.
     *
     * @param tabs    the tabs to render, in ALM's own relation order
     * @param dropped every candidate that did not survive, paired with the rule that discarded it —
     *                so "why is there no Comments tab" is answerable without re-running a probe
     */
    public record Selection(List<Tab> tabs, Map<String, String> dropped) {

        public Selection {
            tabs = List.copyOf(tabs);
            dropped = Map.copyOf(dropped);
        }
    }

    /**
     * @param relations the parsed relation list for one entity
     * @param readable  whether this BFF can populate a tab backed by the given entity name. Injected
     *                  rather than hardcoded: the set of readable collections is a security boundary
     *                  that lives in the api layer, and duplicating it here would let the two drift.
     */
    public static Selection select(List<AlmRelation> relations, Predicate<String> readable) {
        Map<String, String> dropped = new LinkedHashMap<>();
        Map<String, List<AlmRelation>> groups = new LinkedHashMap<>();

        for (AlmRelation r : relations) {
            if (r.unlabelled()) {
                dropped.put(r.name(), "no label — field-backed reference, not a tab");
                continue;
            }
            if (r.selfReferential() && AlmRelation.TYPE_CONTAINMENT.equals(r.type())) {
                dropped.put(r.name(), "self-referential containment — this is the hierarchy tree");
                continue;
            }
            if (!readable.test(r.readEntity())) {
                dropped.put(r.name(), "nothing can read '" + r.readEntity() + "' — tab would be empty");
                continue;
            }
            groups.computeIfAbsent(groupKey(r), k -> new ArrayList<>()).add(r);
        }

        // Rule 5: fold groups that share a label, keeping the first group's key so the merged tab
        // has a stable identity across restarts.
        Map<String, Tab> byLabel = new LinkedHashMap<>();
        for (Map.Entry<String, List<AlmRelation>> group : groups.entrySet()) {
            List<AlmRelation> members = group.getValue();
            String label = bestLabel(members);
            Tab existing = byLabel.get(label);
            if (existing == null) {
                byLabel.put(label, new Tab(group.getKey(), label,
                        members.getFirst().readEntity(), members));
                continue;
            }
            List<AlmRelation> merged = new ArrayList<>(existing.relations());
            merged.addAll(members);
            byLabel.put(label, new Tab(existing.key(), label, existing.readEntity(), merged));
        }

        return new Selection(List.copyOf(byLabel.values()), dropped);
    }

    /**
     * Rule 4's key: the far end and the entity actually read, both.
     *
     * <p>Collapses to just the entity name in the common case where they are the same, so a tab key
     * stays readable ({@code attachment}) rather than doubled ({@code attachment:attachment}).
     */
    private static String groupKey(AlmRelation r) {
        return r.readEntity().equals(r.targetEntity())
                ? r.targetEntity()
                : r.readEntity() + ":" + r.targetEntity();
    }

    /**
     * The label to show for a group of relations that became one tab.
     *
     * <p>Prefers a forward relation over a mirrored one — the forward label reads from the record's
     * point of view — and then the shortest, because ALM's long labels are the qualified ones
     * ("Requirement to Requirement Business Models Linkage" vs "Business Models Linkage"). Ties break
     * alphabetically so the choice is deterministic across restarts rather than dependent on map
     * iteration.
     */
    private static String bestLabel(List<AlmRelation> group) {
        return group.stream()
                .min(Comparator.comparing(AlmRelation::mirrored)
                        .thenComparingInt((AlmRelation r) -> r.label().length())
                        .thenComparing(AlmRelation::label))
                .map(AlmRelation::label)
                .orElseThrow();
    }
}
