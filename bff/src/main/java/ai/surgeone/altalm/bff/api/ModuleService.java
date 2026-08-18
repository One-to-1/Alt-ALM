package ai.surgeone.altalm.bff.api;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ALM's navigation rail, with an honest reachability verdict against each entry.
 *
 * <h2>Why the server answers this and not the SPA</h2>
 *
 * <p>The rail is a <strong>capability claim</strong>. Rendering the full stock rail as live links
 * would advertise six modules Alt-ALM cannot open, and one of them — Libraries — is not merely
 * unbuilt but unreachable over the documented API at all. Each verdict rests on probe evidence, and
 * that evidence belongs beside the code it is about; in the SPA it would be a list of assertions
 * about the API sitting where nobody would think to re-check them against the API.
 *
 * <h2>The rail's shape is a product fact, not per-project customization</h2>
 *
 * <p>Unlike the detail pane's tab strip — derived per project from {@code relations}, because a
 * project genuinely defines its own — the module rail is ALM's own product structure and is the same
 * everywhere. So it is written down here rather than discovered, and that is not a violation of
 * ADR 0005: what ADR 0005 forbids is hardcoding a project's <em>schema</em>.
 *
 * <p>What is emphatically not hardcoded is which entries work. {@link #item} refuses to let an entry
 * claim {@code READABLE} unless the collection it names is on the same allowlist the rest of the BFF
 * enforces, so the rail cannot drift into advertising a read path that was removed.
 */
@Service
public class ModuleService {

    /** The rail, in ALM's own order and grouping. */
    public ModuleDto.Rail rail() {
        return new ModuleDto.Rail(List.of(
                new ModuleDto.Group("", List.of(
                        item("homepage", "My Homepage", "", ModuleDto.Reach.BUILDABLE,
                                "An Alt-ALM construct rather than an ALM read: there is no homepage "
                                        + "resource to fetch, so this is a page we would compose, "
                                        + "not one we can open"))),

                new ModuleDto.Group("Dashboard", List.of(
                        item("analysis-view", "Analysis View", "", ModuleDto.Reach.NEEDS_SIDECAR,
                                "Business views and graphs are OTA-readable only: probe 12 found 37 "
                                        + "BusinessViews plus GraphBuilder, with no REST surface"),
                        item("dashboard-view", "Dashboard View", "", ModuleDto.Reach.NEEDS_SIDECAR,
                                "Report templates are OTA-readable only: probe 12 found 79 "
                                        + "ReportProjectTemplates, with no REST surface"))),

                new ModuleDto.Group("Management", List.of(
                        item("releases", "Releases", "releases", ModuleDto.Reach.BUILDABLE,
                                "The read path exists and is verified — release-folders root id 1, "
                                        + "probe 15 — it simply has no screen yet"),
                        item("libraries", "Libraries", "", ModuleDto.Reach.NO_API,
                                "Libraries and baselines are absent from REST entirely: every "
                                        + "documented path 404s, and a 1,111-operation inventory of "
                                        + "the API has zero hits for them"))),

                new ModuleDto.Group("Requirements", List.of(
                        item("requirements", "Requirements", "requirements",
                                ModuleDto.Reach.READABLE, ""),
                        item("business-models", "Business Models", "bpm-folders",
                                ModuleDto.Reach.BUILDABLE,
                                "The folder read path exists, but the components inside are "
                                        + "REST-403 and OTA-only (probe 8), so this would be half a "
                                        + "module"))),

                new ModuleDto.Group("Testing", List.of(
                        item("test-resources", "Test Resources", "resource-folders",
                                ModuleDto.Reach.BUILDABLE,
                                "Untried: the collection is on the allowlist, but nothing has "
                                        + "probed it and nothing renders it"),
                        item("tests", "Test Plan", "tests", ModuleDto.Reach.READABLE, ""),
                        item("test-sets", "Test Lab", "test-sets", ModuleDto.Reach.READABLE, ""),
                        item("runs", "Test Runs", "runs", ModuleDto.Reach.READABLE, ""))),

                new ModuleDto.Group("Defects", List.of(
                        item("defects", "Defects", "defects", ModuleDto.Reach.READABLE, "")))));
    }

    /**
     * Builds an entry, refusing to let it claim more reach than the allowlist actually grants.
     *
     * <p>The check runs in one direction only. An entry marked {@code READABLE} whose collection this
     * BFF will not serve is downgraded, because that pairing is a claim the app cannot honour. The
     * reverse is left alone: an entry may name a perfectly readable collection and still be
     * {@code BUILDABLE}, which just means the read works and nothing renders it.
     */
    private static ModuleDto.Item item(String key, String label, String collection,
                                       ModuleDto.Reach reach, String reason) {
        if (reach == ModuleDto.Reach.READABLE
                && (collection.isEmpty() || !AlmCollections.isModule(collection))) {
            return new ModuleDto.Item(key, label, collection, ModuleDto.Reach.BUILDABLE,
                    "Marked readable, but '" + collection + "' is not on this build's collection "
                            + "allowlist — the rail entry got ahead of the read path");
        }
        return new ModuleDto.Item(key, label, collection, reach, reason);
    }
}
