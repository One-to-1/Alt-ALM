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
 *   <li><strong>One tab per entity read; one TABLE per far end.</strong> This is the rule that
 *       dissolves what looked like an unsolvable trade-off, and it came from looking at the stock
 *       client rather than at the payload. ALM's Requirement Traceability tab holds
 *       <em>two</em> grids — "Trace From (Requirements that affect X)" and "Trace To (Requirements
 *       affected by X)" — not two tabs. So a group of relations sharing a read entity becomes one
 *       tab, and each distinct far end within it becomes its own table.</li>
 *   <li><strong>Then merge groups that share a label.</strong> {@code requirement} reaches defects
 *       two ways — a {@code defect} connection through {@code defect-link}, and a direct
 *       {@code defect-link} link — and labels both "Linked Defects". They are one tab.</li>
 * </ol>
 *
 * <h2>Why tables-within-tabs, and what it fixed</h2>
 *
 * <p>An earlier version made one tab per <em>pair</em> of (far end, entity read), which produced 8
 * tabs for {@code requirement} where the stock dialog has 5 — {@code req-trace},
 * {@code requirement-coverage} and {@code bpm-link} each appearing twice, once reached directly and
 * once through an association. Merging them by read entity fixed that and simultaneously folded
 * {@code defect}'s nine genuinely distinct {@code defect-link} tabs into one, so the trade-off
 * looked forced, and it was documented as a limit.
 *
 * <p>It was not a limit; it was an assumption that one tab means one query. Grouping by read entity
 * for the <em>tab</em> and by far end for the <em>table</em> gets both right at once: requirement's
 * duplicates collapse, and defect's nine far ends survive as nine tables under one heading — which
 * is closer to ALM, where a defect has a single Linked Entities grid, than nine tabs ever were.
 *
 * <p>What remains genuinely underivable is ALM's <em>ordering and naming</em> of these tabs, which
 * lives in workflow scripts no API serves (probe 21.8). {@link Selection#dropped()} still records
 * every discarded candidate with its reason, so absences stay explainable.
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
    public record Tab(String key, String label, String readEntity, List<Table> tables) {

        public Tab {
            tables = List.copyOf(tables);
            if (tables.isEmpty()) {
                throw new IllegalArgumentException("a tab must hold at least one table");
            }
        }

        /** Every relation behind this tab, across all its tables. */
        public List<AlmRelation> relations() {
            return tables.stream().flatMap(t -> t.relations().stream()).toList();
        }

        /** True when ALM classifies this as the attachment relation — it reads as a sub-resource. */
        public boolean isAttachment() {
            return relations().stream()
                    .anyMatch(r -> AlmRelation.TYPE_ATTACHMENT.equals(r.type()));
        }
    }

    /**
     * One grid within a tab: the rows reaching one particular far end.
     *
     * <p>ALM's Requirement Traceability tab is exactly this shape — "Trace From" and "Trace To" are
     * two grids under one heading, each with its own caption.
     *
     * @param key         stable id within the tab, derived from the far end
     * @param label       the caption above this grid, from the relation's own label
     * @param targetEntity the entity on the far end — what clicking a row navigates to
     * @param relations   the relations merged into this grid, never empty
     */
    public record Table(String key, String label, String targetEntity, List<AlmRelation> relations) {

        public Table {
            relations = List.copyOf(relations);
            if (relations.isEmpty()) {
                throw new IllegalArgumentException("a table must be backed by at least one relation");
            }
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
        // read entity -> far end -> relations
        Map<String, Map<String, List<AlmRelation>>> groups = new LinkedHashMap<>();

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
            // Tab grouping is by what gets READ. Table grouping, one level down, is by the QUERY a
            // relation implies — not by far end.
            //
            // ⚠️ Far end looks right and produces duplicate tables. A requirement reaches its
            // coverage twice: `requirementCoverageToRequirementLink` (a reference, far end
            // requirement-coverage) and `requirementToTestConnection` (an association, far end
            // test). Different far ends, but both filter `requirement-coverages` by
            // `requirement-id` — the same rows, listed twice, one of them navigable and one not.
            // Keying on the filter collapses them, and still keeps defect's nine apart, because
            // each carries a different type discriminator.
            groups.computeIfAbsent(r.readEntity(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(queryKey(r), k -> new ArrayList<>())
                    .add(r);
        }

        List<Tab> tabs = new ArrayList<>(groups.size());
        for (Map.Entry<String, Map<String, List<AlmRelation>>> group : groups.entrySet()) {
            String readEntity = group.getKey();
            List<Table> tables = new ArrayList<>(group.getValue().size());
            for (Map.Entry<String, List<AlmRelation>> byQuery : group.getValue().entrySet()) {
                List<AlmRelation> members = byQuery.getValue();
                // The far end is whichever member knows one: a reference relation cannot say, so a
                // group holding both takes the association's answer.
                String target = members.stream()
                        .filter(AlmRelation::navigable)
                        .map(AlmRelation::targetEntity)
                        .findFirst()
                        .orElseGet(() -> members.getFirst().targetEntity());
                tables.add(new Table(byQuery.getKey(), tableLabel(members), target, members));
            }
            // The tab takes the shortest of its tables' labels: for req-trace that is "Trace" over
            // "Traced To Requirements", and the per-direction detail stays on each table's caption.
            String label = tables.stream()
                    .map(Table::label)
                    .min(Comparator.comparingInt(String::length).thenComparing(l -> l))
                    .orElseThrow();
            tabs.add(new Tab(readEntity, label, readEntity, tables));
        }

        // Rule 5: fold tabs that share a label, concatenating their tables.
        Map<String, Tab> byLabel = new LinkedHashMap<>();
        for (Tab tab : tabs) {
            Tab existing = byLabel.get(tab.label());
            if (existing == null) {
                byLabel.put(tab.label(), tab);
                continue;
            }
            List<Table> merged = new ArrayList<>(existing.tables());
            merged.addAll(tab.tables());
            byLabel.put(tab.label(),
                    new Tab(existing.key(), existing.label(), existing.readEntity(), merged));
        }

        return new Selection(List.copyOf(byLabel.values()), dropped);
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
    /**
     * Two relations share a table when they would issue the same query — same filter column, same
     * discriminator. That, not the far end, is what makes their rows identical.
     */
    private static String queryKey(AlmRelation r) {
        return r.filterIdField() + "|" + r.filterTypeField() + "|" + r.filterTypeValue();
    }

    /**
     * The caption above one grid.
     *
     * <p>Prefers the descriptive label over the qualified one. ALM's own captions are "Test
     * Coverage", "Traced From Requirements", "Linked Defects" — never "Requirement to Tests that
     * cover Requirement", which is the relation's formal name and reads as machinery. Those
     * qualified forms all begin "&lt;source entity&gt; to ", so they are skipped when the group
     * offers anything else, and among what remains the shortest matches ALM's phrasing — its grids
     * say "Test Coverage", not "Requirement Coverage".
     */
    private static String tableLabel(List<AlmRelation> group) {
        String qualified = group.getFirst().sourceEntity() + " to ";
        List<AlmRelation> preferred = group.stream()
                .filter(r -> !r.label().toLowerCase().startsWith(qualified.toLowerCase()))
                .toList();
        return bestLabel(preferred.isEmpty() ? group : preferred);
    }

    private static String bestLabel(List<AlmRelation> group) {
        return group.stream()
                .min(Comparator.comparing(AlmRelation::mirrored)
                        .thenComparingInt((AlmRelation r) -> r.label().length())
                        .thenComparing(AlmRelation::label))
                .map(AlmRelation::label)
                .orElseThrow();
    }
}
