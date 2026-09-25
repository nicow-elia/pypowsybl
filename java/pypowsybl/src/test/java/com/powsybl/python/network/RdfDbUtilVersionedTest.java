/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.cgmes.rdfdb.SnapshotInfo;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.python.network.RdfDbUtilTest.importParameters;
import static com.powsybl.python.network.RdfDbUtilTest.memoryUrl;
import static com.powsybl.python.network.RdfDbUtilTest.microGridBe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the versioned half of the pypowsybl RDF database bindings: snapshots, routes and the catalogue views.
 *
 * <p>The scenario under test always holds a <em>second</em> scenario next to it, because the interesting part of
 * the addressing is that two base grid models live in one database without ever mixing. Both ingestion paths are
 * exercised: a version written from a recording ({@code RdfDbExport}) and one ingested from instance files
 * ({@code SnapshotCatalog.putAsDiff}), which is how a day exported by a TSO reaches the database.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilVersionedTest {

    static final String S = "2016-01-01";
    static final String OTHER = "other";

    /** Store the base grid model as the root of {@code scenario}, and the second model as the root of "other". */
    static Network twoScenarios(RdfDbConnection db) {
        List<String> other = RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.miniBusBranch().dataSource(), OTHER,
                "1.0", null, importParameters(), null);
        assertThat(other).isNotEmpty();
        List<String> root = RdfDbUtil.loadCgmes(db, microGridBe(), S, "1.0", null, importParameters(), null);
        assertThat(root).isNotEmpty();
        return RdfDbUtil.load(db, S, "1.0", null, importParameters(), List.of(), null);
    }

    /** Move one load, which is a steady-state-only change and therefore a fast-route difference. */
    static List<String> record(RdfDbConnection db, Network network, String version, String timestep,
                               double value) {
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = network.getLoads().iterator().next();
        load.setP0(value);
        recording.stop();
        return RdfDbUtil.exportRecording(recording, db, S, version, timestep, Map.of());
    }

    @Test
    void aRootAndThenVersionsFromARecording() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            assertTrue(RdfDbUtil.isVersioned(db, S));
            assertThat(record(db, network, "1.1", "8:30", 42.0)).isNotEmpty();

            List<String> versions = RdfDbUtil.snapshots(db, S).stream()
                    .map(SnapshotInfo::version).sorted().toList();
            assertEquals(List.of("1.0", "1.1"), versions);
            assertEquals(1, RdfDbUtil.snapshots(db, OTHER).size(), "the other scenario is untouched");
            assertEquals(2, RdfDbUtil.timesteps(db, S).size());
            assertEquals(List.of("1.1"), RdfDbUtil.versions(db, S, "8:30").stream()
                    .map(SnapshotInfo::version).toList());
            assertThat(RdfDbUtil.models(db, S)).anyMatch(model -> model.isDiff() && model.fastPredicatesOnly());
        }
    }

    @Test
    void updateRoutesAreNoopThenDiff() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network sender = twoScenarios(db);
            record(db, sender, "1.1", "8:30", 43.0);

            Network receiver = RdfDbUtil.load(db, S, "1.0", null, importParameters(), List.of(), null);
            RdfDbUtil.UpdateOutcome noop = RdfDbUtil.update(receiver, db, S, "1.0", null, List.of(), Map.of(),
                    importParameters(), null);
            Map<String, String> info = RdfDbUtil.updateInfo(noop);
            assertEquals("noop", info.get(RdfDbUtil.ROUTE));
            assertEquals(S, info.get(RdfDbUtil.SCENARIO));
            assertThat(info).containsKeys(RdfDbUtil.DIFF_COUNT, RdfDbUtil.STATEMENT_COUNT, "plan_ms", "apply_ms");
            assertThatThrownBy(() -> RdfDbUtil.replacement(noop))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("no replacement network: route was noop");

            RdfDbUtil.UpdateOutcome diff = RdfDbUtil.update(receiver, db, S, "1.1", "8:30", List.of(), Map.of(),
                    importParameters(), null);
            assertEquals("diff", RdfDbUtil.updateInfo(diff).get(RdfDbUtil.ROUTE));
            assertEquals(43.0, receiver.getLoads().iterator().next().getP0(), 1e-9);
        }
    }

    /**
     * The other half of the "transparently decide" requirement: a difference that <em>cannot</em> be applied in
     * place makes the loader fall back to the full grid model, inside one scenario and without the caller asking.
     *
     * <p>Renaming a line is the cheapest such change: an {@code IdentifiedObject.name} is not among the properties
     * the in-place update knows how to set, so the ingested EQ difference is stored with {@code fast = false} and
     * the planner answers FULL.</p>
     */
    @Test
    void anEquipmentDriftInTheSameScenarioIsAFullReload() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            RdfDbUtil.loadCgmes(db, timestepFiles("drift", 1.2, "2014-06-01T09:30:00Z", true), S, "1.1", "9:30",
                    importParameters(), null);
            assertThat(RdfDbUtil.models(db, S))
                    .anyMatch(model -> model.isDiff() && !model.fastPredicatesOnly());

            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, S, "1.1", "9:30", List.of(), Map.of(),
                    importParameters(), null);
            Map<String, String> info = RdfDbUtil.updateInfo(outcome);
            assertEquals("full", info.get(RdfDbUtil.ROUTE), "an unapplicable difference falls back to a reload");
            assertEquals(S, info.get(RdfDbUtil.SCENARIO), "and it stays inside the scenario");
            Network replacement = RdfDbUtil.replacement(outcome);
            assertThat(replacement).isNotSameAs(network);
            assertThat(replacement.getIdentifiables().stream().map(i -> i.getNameOrId()))
                    .as("the renamed equipment reached the reloaded network")
                    .anyMatch(name -> name.startsWith("drifted-"));
        }
    }

    @Test
    void updateAcrossScenariosIsAFullReload() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, OTHER, "1.0", null, List.of(),
                    Map.of(), importParameters(), null);
            Map<String, String> info = RdfDbUtil.updateInfo(outcome);
            assertEquals("full", info.get(RdfDbUtil.ROUTE));
            assertEquals(OTHER, info.get(RdfDbUtil.SCENARIO));
            assertThat(info.get(RdfDbUtil.REASONS)).contains("never cross scenarios");
            Network replacement = RdfDbUtil.replacement(outcome);
            assertThat(replacement).isNotSameAs(network);
            assertEquals(OTHER, RdfDbUtil.identity(replacement, null, null).get(RdfDbUtil.SCENARIO));
        }
    }

    @Test
    void theLegacySubsetRouteStillAnswersUpdate() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            RdfDbUtil.loadCgmes(db, microGridBe(), S, null, null, importParameters(), null);
            Network network = RdfDbUtil.load(db, S, null, null, importParameters(), List.of(), null);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, S, null, null, List.of("SSH"),
                    Map.of(), importParameters(), null);
            assertEquals(Map.of(RdfDbUtil.ROUTE, "update"), RdfDbUtil.updateInfo(outcome));
        }
    }

    @Test
    void anExportIntoAnotherScenarioIsRefused() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            NetworkEventRecording recording = new NetworkEventRecording(network);
            recording.start();
            network.getLoads().iterator().next().setP0(44.0);
            recording.stop();
            assertThatThrownBy(() -> RdfDbUtil.exportRecording(recording, db, OTHER, "1.1", null, Map.of()))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("never cross scenarios");
        }
    }

    @Test
    void anExportRejectsWhatTheDatabaseDecides() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            NetworkEventRecording recording = new NetworkEventRecording(network);
            for (String decided : List.of("version", "scenario_time", "supersedes", "depends_on")) {
                assertThatThrownBy(() -> RdfDbUtil.exportRecording(recording, db, S, "1.1", null,
                        Map.of(decided, "whatever")))
                        .isInstanceOf(PowsyblException.class)
                        .hasMessageContaining("is decided by the database");
            }
        }
    }

    /**
     * A day does not arrive as recordings but as files: a TSO exports one set of instance files per timestep, and
     * what the database should hold is the base plus what each of them changed. That is what
     * {@code SnapshotCatalog.putAsDiff} does, and what {@link RdfDbUtil#loadCgmes} routes to once a scenario has a
     * root.
     */
    @Test
    void aFurtherSnapshotIsIngestedFromFiles() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            twoScenarios(db);
            Network base = RdfDbUtil.load(db, S, "1.0", null, importParameters(), List.of(), null);
            double before = base.getLoads().iterator().next().getP0();

            List<String> members = RdfDbUtil.loadCgmes(db, timestepFiles("t0830", 1.5, "2014-06-01T08:30:00Z",
                    false), S, "1.1", "8:30", importParameters(), null);
            assertThat(members).isNotEmpty();

            List<SnapshotInfo> snapshots = RdfDbUtil.snapshots(db, S);
            assertEquals(2, snapshots.size());
            assertThat(snapshots).anyMatch(info -> info.kind() == SnapshotInfo.Kind.DIFF
                    && "08:30".equals(info.timestepLabel()) && "1.1".equals(info.version()));
            assertEquals(1, RdfDbUtil.snapshots(db, OTHER).size(), "the other scenario is untouched");
            assertThat(db.snapshots(S).lastIngestStatistics()).isNotNull();

            Network reader = RdfDbUtil.load(db, S, "1.1", "8:30", importParameters(), List.of(), null);
            assertEquals(before * 1.5, reader.getLoads().iterator().next().getP0(), 1e-6);
        }
    }

    /**
     * The daily CGMES export of one timestep: the base case with the steady state file rewritten.
     *
     * <p>The same approach core's own {@code TimestepFixtures} takes, and for the same reason: re-exporting the
     * network through the CGMES exporter would differ from the base in a hundred incidental ways, and the test
     * would be about the exporter rather than about the change. Everything but the steady state file is copied
     * byte for byte, boundary included - {@code putAsDiff} checks that the boundary did not move.</p>
     *
     * @param suffix   what makes the model identifier of this timestep unique
     * @param factor   what the active power of every energy consumer is multiplied by
     * @param instant  the scenario time the files claim
     * @param eqDrift  whether the equipment model drifts too: one line is renamed, which no in-place update can
     *                 apply, so the difference is stored but is not fast-route capable
     */
    static ReadOnlyDataSource timestepFiles(String suffix, double factor, String instant, boolean eqDrift) {
        ReadOnlyDataSource source = microGridBe();
        MemDataSource target = new MemDataSource();
        try {
            for (String name : source.listNames(".*")) {
                String content;
                try (InputStream stream = source.newInputStream(name)) {
                    content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (name.contains("_SSH_")) {
                    content = content.replaceFirst("(?s)(<md:FullModel[^>]*rdf:about=\")[^\"]*(\")",
                            "$1urn:uuid:ssh-" + suffix + "$2");
                    content = content.replaceFirst("(<md:Model\\.scenarioTime>)[^<]*(</md:Model\\.scenarioTime>)",
                            "$1" + instant + "$2");
                    content = scaleConsumers(content, factor);
                } else if (eqDrift && name.contains("_EQ_") && !name.contains("_EQ_BD")) {
                    content = content.replaceFirst("(?s)(<md:FullModel[^>]*rdf:about=\")[^\"]*(\")",
                            "$1urn:uuid:eq-" + suffix + "$2");
                    content = content.replaceFirst("(?s)(<cim:ACLineSegment[^>]*>.*?<cim:IdentifiedObject.name>)"
                            + "[^<]*(</cim:IdentifiedObject.name>)", "$1drifted-" + suffix + "$2");
                }
                try (OutputStream out = target.newOutputStream(name, false)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    private static String scaleConsumers(String ssh, double factor) {
        Matcher matcher = Pattern.compile("(<cim:EnergyConsumer.p>)(-?[0-9.eE+]+)(</cim:EnergyConsumer.p>)")
                .matcher(ssh);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + (Double.parseDouble(matcher.group(2)) * factor) + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Test
    void aBlankScenarioIsRefusedByEveryCall() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            NetworkEventRecording recording = new NetworkEventRecording(network);
            List<Runnable> calls = List.of(
                () -> RdfDbUtil.isVersioned(db, " "),
                () -> RdfDbUtil.snapshots(db, " "),
                () -> RdfDbUtil.timesteps(db, " "),
                () -> RdfDbUtil.models(db, " "),
                () -> RdfDbUtil.versions(db, " ", null),
                () -> RdfDbUtil.graphs(db, " "),
                () -> RdfDbUtil.clear(db, " "),
                () -> RdfDbUtil.checkpoint(db, " ", "1.0", null),
                () -> RdfDbUtil.load(db, " ", "1.0", null, Map.of(), List.of(), null),
                () -> RdfDbUtil.loadCgmes(db, microGridBe(), " ", "1.0", null, Map.of(), null),
                () -> RdfDbUtil.exportRecording(recording, db, " ", "1.1", null, Map.of()),
                () -> RdfDbUtil.identity(network, db, " "),
                () -> RdfDbUtil.update(network, db, " ", "1.0", null, List.of(), Map.of(), Map.of(), null));
            for (Runnable call : calls) {
                assertThatThrownBy(call::run)
                        .isInstanceOf(PowsyblException.class)
                        .hasMessageContaining("must not be blank");
            }
        }
    }

    @Test
    void aCheckpointMaterialisesASnapshot() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            record(db, network, "1.1", "8:30", 45.0);
            String iri = RdfDbUtil.checkpoint(db, S, "1.1", "8:30");
            assertThat(iri).contains(S);
            assertThat(RdfDbUtil.snapshots(db, S))
                    .filteredOn(info -> info.iri().equals(iri))
                    .allMatch(SnapshotInfo::hasFull);
        }
    }

    @Test
    void theCatalogueViewsBecomeDataframes() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            record(db, network, "1.1", "8:30", 46.0);

            assertEquals(List.of("scenario", "base_timestep", "versioned", "snapshot_count"),
                    columns(RdfDbUtil.scenariosMapper(), RdfDbUtil.scenarioRows(db)));
            assertEquals(2, RdfDbUtil.scenarioRows(db).size());
            assertEquals(List.of("snapshot", "scenario", "version", "timestep", "timestep_label", "kind",
                            "parent", "edge", "depth", "has_full", "fast", "members", "created", "description"),
                    columns(RdfDbUtil.snapshotsMapper(), RdfDbUtil.snapshots(db, S)));
            assertEquals(List.of("timestep", "scenario", "label", "root", "head", "version_count", "pinned_base"),
                    columns(RdfDbUtil.timestepsMapper(), RdfDbUtil.timesteps(db, S)));
            assertEquals(List.of("id", "scenario", "subset", "kind", "version", "supersedes", "depends_on", "fast",
                            "triple_count", "chain_depth", "created"),
                    columns(RdfDbUtil.modelsMapper(), RdfDbUtil.models(db, S)));
            assertFalse(RdfDbUtil.snapshots(db, "never-uploaded").iterator().hasNext(),
                    "an unknown scenario lists nothing rather than failing");
        }
    }

    private static <T> List<String> columns(com.powsybl.dataframe.DataframeMapper<T, Void> mapper, T rows) {
        List<Series> series = new ArrayList<>();
        mapper.createDataframe(rows, new DefaultDataframeHandler(series::add), new DataframeFilter());
        Map<String, Series> byName = new LinkedHashMap<>();
        series.forEach(s -> byName.put(s.getName(), s));
        return List.copyOf(byName.keySet());
    }
}
