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
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.powsybl.python.network.RdfDbTestSupport.columns;
import static com.powsybl.python.network.RdfDbTestSupport.totalLoad;
import static com.powsybl.python.network.RdfDbUtilTest.importParameters;
import static com.powsybl.python.network.RdfDbUtilTest.memoryUrl;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.BASE;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.BE;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.NL;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.OTHER;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.S;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0815;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0830;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0845;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0930;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.export;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.ingest;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.load;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.timestampFiles;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.twoScenarios;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests of the variant half of the pypowsybl RDF database bindings: a day as the variants of one network.
 *
 * <p>Everything the Python layer can do with variants is one call of {@link RdfDbUtil} away, and this is where
 * those calls are proved on the JVM: the bulk load, the create-or-update of one variant, the refusal a drifted
 * timestamp produces, the table {@code variants_binding()} is built from, the per-variant identity and the two
 * per-variant exports. The C entry points of {@link RdfDbCFunctions} are one liners over exactly these.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilVariantsTest {

    /** A base scenario plus three steady-state timesteps, each scaling the loads a little further. */
    private static Network aDay(RdfDbConnection db) {
        Network sender = twoScenarios(db);
        ingest(db, S, timestampFiles("t0815", 1.1, T0815, false), 1, T0815);
        ingest(db, S, timestampFiles("t0830", 1.2, T0830, false), 1, T0830);
        ingest(db, S, timestampFiles("t0845", 1.3, T0845, false), 1, T0845);
        return sender;
    }

    /** A bulk load at the head of each timestamp, the modelling authority left to the bindings. */
    static Network loadVariants(RdfDbConnection db, String scenario, List<String> variantIds,
                                List<String> timestamps) {
        return RdfDbUtil.loadVariants(db, scenario, variantIds, Collections.nCopies(timestamps.size(), null), false,
                timestamps, Collections.nCopies(timestamps.size(), ""), List.of(), importParameters(), null, false);
    }

    private static Map<String, RdfDbUtil.VariantRow> byVariant(Network network) {
        Map<String, RdfDbUtil.VariantRow> rows = new LinkedHashMap<>();
        RdfDbUtil.variantRows(network).forEach(row -> rows.put(row.variant() + "/" + row.status(), row));
        return rows;
    }

    @Test
    void aDayLoadsAsTheVariantsOfOneNetwork() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", "", ""), List.of(T0815, T0830, T0845));

            assertThat(day.getVariantManager().getVariantIds())
                    .as("the primary stays, and every requested timestamp is a variant of its own, named after its instant")
                    .containsExactlyInAnyOrder("InitialState", T0815, T0830, T0845);
            assertEquals("InitialState", day.getVariantManager().getWorkingVariantId());

            // Every variant is the network a separate load of that snapshot gives
            for (String timestamp : List.of(T0815, T0830, T0845)) {
                Network alone = load(db, S, 1, timestamp);
                assertEquals(alone.getLoadStream().mapToDouble(load -> load.getP0()).sum(),
                        totalLoad(day, timestamp), 1e-6, "variant " + timestamp + " equals a separate load");
            }
        }
    }

    @Test
    void anUpdateCreatesOrMovesOneVariant() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network network = load(db, S, 1, null);
            double base = network.getLoadStream().mapToDouble(load -> load.getP0()).sum();

            RdfDbUtil.UpdateOutcome created = update(network, db, S, 1, T0830, Map.of(RdfDbUtil.VARIANT, "study"));
            Map<String, String> info = RdfDbUtil.updateInfo(created);
            assertEquals("diff", info.get(RdfDbUtil.ROUTE));
            assertEquals("study", info.get(RdfDbUtil.VARIANT));
            assertThat(day(network)).contains("study");
            assertEquals(base, network.getLoadStream().mapToDouble(load -> load.getP0()).sum(), 1e-6,
                    "the working variant of the caller is untouched");
            assertEquals(base * 1.2, totalLoad(network, "study"), 1e-6);

            // The same variant again, at another timestamp: it is moved, not created a second time
            RdfDbUtil.UpdateOutcome moved = update(network, db, S, 1, T0845, Map.of(RdfDbUtil.VARIANT, "study"));
            assertEquals("diff", RdfDbUtil.updateInfo(moved).get(RdfDbUtil.ROUTE));
            assertEquals(base * 1.3, totalLoad(network, "study"), 1e-6);
            assertEquals(2, day(network).size(), "still one 'study' variant next to the primary");

            RdfDbUtil.UpdateOutcome noop = update(network, db, S, 1, T0845, Map.of(RdfDbUtil.VARIANT, "study"));
            assertEquals("noop", RdfDbUtil.updateInfo(noop).get(RdfDbUtil.ROUTE));
        }
    }

    private static List<String> day(Network network) {
        return List.copyOf(network.getVariantManager().getVariantIds());
    }

    /**
     * A timestamp whose equipment model drifted cannot be reached inside a variant: a renamed line is not a
     * per-variant value in IIDM, so writing it would change every variant at once. The binding must hand the
     * refusal and its reasons to Python rather than reloading the network behind the caller's back.
     */
    @Test
    void anEquipmentDriftIsRefusedInVariantMode() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            ingest(db, S, timestampFiles("drift", 1.4, T0930, true), 1, T0930);
            Network network = load(db, S, 1, null);

            RdfDbUtil.UpdateOutcome refused = update(network, db, S, 1, T0930, Map.of(RdfDbUtil.VARIANT, "drifted"));
            Map<String, String> info = RdfDbUtil.updateInfo(refused);
            assertEquals("refused", info.get(RdfDbUtil.ROUTE));
            assertEquals("drifted", info.get(RdfDbUtil.VARIANT));
            assertThat(info.get(RdfDbUtil.REASONS)).isNotEmpty();
            assertThat(day(network)).as("a refused variant is not created").doesNotContain("drifted");
            assertThat(byVariant(network)).containsKey("drifted/refused");
            assertThat(byVariant(network).get("drifted/refused").reasons()).isNotEmpty();
        }
    }

    @Test
    void aBulkLoadRefusesOneTimestepAndBindsTheRest() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            ingest(db, S, timestampFiles("drift", 1.4, T0930, true), 1, T0930);
            Network day = loadVariants(db, S, List.of("", "", ""), List.of(T0815, T0930, T0845));

            assertThat(day.getVariantManager().getVariantIds()).contains(T0815, T0845)
                    .doesNotContain(T0930);
            Map<String, RdfDbUtil.VariantRow> rows = byVariant(day);
            assertThat(rows).containsKeys(T0815 + "/bound", T0845 + "/bound", T0930 + "/refused", "InitialState/primary");
            assertThat(rows.get(T0930 + "/refused").reasons()).isNotEmpty();
        }
    }

    /**
     * The default name of a variant: its instant; {@code version@instant} when two requests share an instant;
     * {@code authority/version@instant} when the requests span several modelling authorities. What triggers the
     * qualified form is the request list, not the scenario: requests of one tree keep the short names when the
     * scenario holds two.
     */
    @Test
    void theDefaultNamesOfABulkLoad() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            ingest(db, S, timestampFiles("t0830-v2", 1.25, T0830, false), 2, T0830);
            assertThat(RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.microGridBaseCaseNL().dataSource(), S, "1",
                    null, NL, List.of(), importParameters(), null)).isNotEmpty();

            Network oneTree = RdfDbUtil.loadVariants(db, S, List.of("", ""), List.of("1", "1"), false, List.of(T0815, T0845),
                    List.of(BE, BE), List.of(), importParameters(), null, false);
            assertThat(oneTree.getVariantManager().getVariantIds()).containsExactlyInAnyOrder("InitialState",
                    T0815, T0845);

            Network oneInstant = RdfDbUtil.loadVariants(db, S, List.of("", ""), List.of("1", "2"), false, List.of(T0830, T0830),
                    List.of(BE, BE), List.of(), importParameters(), null, false);
            assertThat(oneInstant.getVariantManager().getVariantIds()).containsExactlyInAnyOrder("InitialState",
                    "1@" + T0830, "2@" + T0830);

            Network twoTrees = RdfDbUtil.loadVariants(db, S, List.of("", ""), List.of("1", "1"), false, List.of(BASE, BASE),
                    List.of(NL, BE), List.of(), importParameters(), null, false);
            assertThat(byVariant(twoTrees)).as("the second tree is out of reach of a difference, so it is refused")
                    .containsKeys(NL + "/1@" + BASE + "/bound", BE + "/1@" + BASE + "/refused");
        }
    }

    @Test
    void theVariantTableDescribesEveryVariant() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("morning", ""), List.of(T0815, T0830));
            day.getVariantManager().cloneVariant("InitialState", "scratch");

            assertEquals(List.of("variant", "scenario", "snapshot", "modelling_authority", "timestamp", "version",
                            "cloned_from",
                            "eq", "ssh", "case_date", "status", "reasons"),
                    columns(RdfDbUtil.variantsMapper(), RdfDbUtil.variantRows(day)));
            Map<String, RdfDbUtil.VariantRow> rows = byVariant(day);
            assertThat(rows).as("a clone of a bound variant inherits its binding - its state is that snapshot")
                    .containsKeys("InitialState/primary", "morning/bound", T0830 + "/bound", "scratch/bound");
            assertEquals("InitialState", rows.get("scratch/bound").clonedFrom());
            RdfDbUtil.VariantRow morning = rows.get("morning/bound");
            assertEquals(S, morning.scenario());
            assertEquals("1", morning.version());
            assertEquals(T0815, morning.timestamp());
            assertEquals(BE, morning.modellingAuthority());
            assertThat(morning.snapshot()).contains(S);
            assertThat(morning.ssh()).isNotEmpty();
            assertThat(morning.caseDate()).isNotEmpty();
        }
    }

    @Test
    void theIdentityOfOneVariantIsThatVariantsSnapshot() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));

            Map<String, String> early = RdfDbUtil.identity(day, db, S, T0815);
            Map<String, String> late = RdfDbUtil.identity(day, db, S, T0845);
            assertEquals(S, early.get(RdfDbUtil.SCENARIO));
            assertThat(early.get(RdfDbUtil.SNAPSHOT)).isNotEqualTo(late.get(RdfDbUtil.SNAPSHOT));
            assertEquals("2014-06-01T08:15:00Z", early.get(RdfDbUtil.TIMESTAMP));
            assertEquals("2014-06-01T08:45:00Z", late.get(RdfDbUtil.TIMESTAMP));
            assertEquals(early.get(RdfDbUtil.SNAPSHOT), RdfDbUtil.identity(day, db, S, null).get(RdfDbUtil.SNAPSHOT),
                    "without a variant the answer is the network's own - the primary, which a bulk load leaves at"
                            + " the first requested snapshot");

            assertThatThrownBy(() -> RdfDbUtil.identity(day, db, S, "nope"))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("has no variant 'nope'");
        }
    }

    @Test
    void changesRecordedOnTwoVariantsBecomeTwoSuccessors() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getVariantManager().setWorkingVariant(T0815);
            day.getLoads().iterator().next().setP0(111.0);
            day.getVariantManager().setWorkingVariant(T0845);
            day.getLoads().iterator().next().setP0(222.0);
            day.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();

            List<RdfDbUtil.VariantExportRow> rows =
                    RdfDbUtil.exportRecordingPerVariant(recording, db, S, "2", Map.of());
            assertEquals(List.of("variant", "snapshot", "modelling_authority", "timestamp", "version", "rank",
                    "models", "exported_events", "rejected"), columns(RdfDbUtil.variantExportMapper(), rows));
            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(row -> "2".equals(row.version()) && BE.equals(row.modellingAuthority()));
            assertThat(rows.stream().map(RdfDbUtil.VariantExportRow::variant).toList())
                    .containsExactlyInAnyOrder(T0815, T0845);
            assertThat(rows.stream().map(RdfDbUtil.VariantExportRow::timestamp).toList())
                    .containsExactlyInAnyOrder("2014-06-01T08:15:00Z", "2014-06-01T08:45:00Z");

            // A second reader, i.e. what another process would see
            Network early = load(db, S, 2, T0815);
            Network late = load(db, S, 2, T0845);
            assertEquals(111.0, early.getLoads().iterator().next().getP0(), 1e-9);
            assertEquals(222.0, late.getLoads().iterator().next().getP0(), 1e-9);
        }
    }

    @Test
    void oneVariantExportWritesTheSuccessorOfItsOwnSnapshot() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getVariantManager().setWorkingVariant(T0845);
            day.getLoads().iterator().next().setP0(333.0);
            day.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();

            List<String> ids = export(recording, db, S, 3, null,
                    Map.of(RdfDbUtil.VARIANT, T0845));
            assertThat(ids).isNotEmpty();
            Network reader = load(db, S, 3, T0845);
            assertEquals(333.0, reader.getLoads().iterator().next().getP0(), 1e-9);

            assertThatThrownBy(() -> export(recording, db, S, 3, T0815,
                    Map.of(RdfDbUtil.VARIANT, T0845)))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("drop them");
            assertThatThrownBy(() -> export(recording, db, OTHER, 3, null,
                    Map.of(RdfDbUtil.VARIANT, T0845)))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("never crosses scenarios");
            assertThatThrownBy(() -> RdfDbUtil.exportRecordingPerVariant(recording, db, S, "3",
                    Map.of(RdfDbUtil.VARIANT, T0845)))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("takes no 'variant' option");
        }
    }

    @Test
    void theArgumentsOfABulkLoadAreChecked() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            assertThatThrownBy(() -> loadVariants(db, S, List.of(), List.of()))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("at least one snapshot");
            assertThatThrownBy(() -> loadVariants(db, S, List.of(""), List.of(T0815, T0830)))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("must be as many");
            assertThatThrownBy(() -> RdfDbUtil.loadVariants(db, S, List.of(""), List.of("1"), false, List.of(T0815),
                    List.of("", ""), List.of(), importParameters(), null, false))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("must be as many");
            assertThatThrownBy(() -> loadVariants(db, " ", List.of(""), List.of(T0815)))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> RdfDbUtil.loadVariants(db, S, List.of(""), List.of("1"), false, List.of(T0815),
                    List.of(""), List.of("XX"), importParameters(), null, false))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("Unknown CGMES profile 'XX'");
        }
    }

    /**
     * Cloning a variant is the ordinary IIDM idiom and must not turn the classic routes into variant operations:
     * it is the explicit opt-in - a named target variant, a bulk load or a variant export - that does.
     *
     * <p>The clone is tracked while nothing has written across the variants yet, and the classic update that
     * follows <em>forgets</em> it: such an update may write values shared by every variant, so the library stops
     * claiming the clone still stands for a snapshot it can no longer vouch for. The primary row survives.</p>
     */
    @Test
    void aUserCloneDoesNotSwitchTheNetworkIntoVariantMode() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network network = load(db, S, 1, null);
            network.getVariantManager().cloneVariant("InitialState", "what-if");
            assertThat(byVariant(network))
                    .as("a clone inherits the binding of its source - its state is that snapshot")
                    .containsKey("what-if/bound");

            RdfDbUtil.UpdateOutcome outcome = update(network, db, S, 1, T0830, Map.of());
            Map<String, String> info = RdfDbUtil.updateInfo(outcome);
            assertEquals("diff", info.get(RdfDbUtil.ROUTE));
            assertEquals("", info.get(RdfDbUtil.VARIANT), "a classic update names no variant");
            assertThat(byVariant(network))
                    .as("the classic update forgot what the clone stood for, and kept the primary row")
                    .containsKeys("InitialState/primary", "what-if/unbound")
                    .doesNotContainKey("what-if/bound");
            assertThatThrownBy(() -> RdfDbUtil.identity(network, db, S, "what-if"))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("is not bound to a snapshot");
        }
    }

    /**
     * Naming a variant on the way <em>out</em> is an opt-in too: after it, the classic update of a drifted
     * timestamp is refused instead of reloading the network behind the caller's back.
     */
    @Test
    void aVariantExportOptsTheNetworkIntoVariantMode() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            ingest(db, S, timestampFiles("drift2", 1.4, T0930, true), 1, T0930);
            Network network = load(db, S, 1, T0830);
            network.getVariantManager().cloneVariant("InitialState", "study");

            NetworkEventRecording recording = new NetworkEventRecording(network);
            recording.start();
            network.getVariantManager().setWorkingVariant("study");
            network.getLoads().iterator().next().setP0(55.0);
            network.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();
            assertThat(export(recording, db, S, 4, null,
                    Map.of(RdfDbUtil.VARIANT, "study"))).isNotEmpty();

            RdfDbUtil.UpdateOutcome outcome = update(network, db, S, 1, T0930, Map.of());
            assertEquals("refused", RdfDbUtil.updateInfo(outcome).get(RdfDbUtil.ROUTE),
                    "variant mode is sticky once a variant export has named one");
        }
    }

    /**
     * In variant mode every export is a variant export, {@code variant} or not: a change IIDM does not store per
     * variant belongs to all of them and to none of their snapshots.
     */
    @Test
    void aClassicExportRefusesASharedChangeInVariantMode() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));
            day.getVariantManager().setWorkingVariant(T0815);

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getLoads().iterator().next().setP0(77.0);
            day.getLines().iterator().next().setR(0.42);
            recording.stop();

            assertThatThrownBy(() -> export(recording, db, S, 5, T0815, Map.of()))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("not stored per variant");
            assertThat(export(recording, db, S, 5, T0815,
                    Map.of(NetworkEventRecording.UNSUPPORTED, "ignore"))).isNotEmpty();
            day.getVariantManager().setWorkingVariant("InitialState");
        }
    }

    @Test
    void variantsAndTheLegacyRouteDoNotMix() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            ingest(db, "plain", RdfDbUtilTest.microGridBe(), null, null);
            Network network = load(db, "plain", null, null);
            assertThatThrownBy(() -> RdfDbUtil.update(network, db, "plain", null, false, null, null, List.of("SSH"),
                    Map.of(RdfDbUtil.VARIANT, "v"), importParameters(), null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("holds no snapshot, so there is nothing for variant 'v' to stand for");
        }
    }

    /** On a versioned scenario the profiles are the projection of the update, variant or not. */
    @Test
    void profilesProjectAVariantUpdate() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network network = load(db, S, 1, null);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, S, "1", false, T0830, null,
                    List.of("EQ", "SSH"), Map.of(RdfDbUtil.VARIANT, "study"), importParameters(), null);
            assertEquals("diff", RdfDbUtil.updateInfo(outcome).get(RdfDbUtil.ROUTE));
            assertThatThrownBy(() -> RdfDbUtil.update(network, db, S, "1", false, T0845, null, List.of("XX"), Map.of(),
                    importParameters(), null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("Unknown CGMES profile 'XX'");
        }
    }

    @Test
    void aNetworkWithoutAProvenanceStillListsItsVariants() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            ingest(db, "plain", RdfDbUtilTest.microGridBe(), null, null);
            Network network = load(db, "plain", null, null);
            network.removeExtension(com.powsybl.cgmes.rdfdb.RdfDbProvenance.class);
            network.getVariantManager().cloneVariant("InitialState", "b");
            assertThat(RdfDbUtil.variantRows(network))
                    .allMatch(row -> "unbound".equals(row.status()))
                    .hasSize(2);
        }
    }

    /**
     * A file export describes one variant, and its <em>header</em> has to say so: the difference supersedes the
     * model that variant stands for and is dated at that variant's moment. That is true when the caller names the
     * variant and - the natural gesture of the whole feature - when the caller merely works on it.
     */
    @Test
    void aFileExportCarriesTheIdentityOfTheVariantItDescribes() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));
            Map<String, RdfDbUtil.VariantRow> rows = byVariant(day);
            String sshLate = rows.get(T0845 + "/bound").ssh();
            String sshPrimary = rows.get("InitialState/primary").ssh();
            assertThat(sshLate).isNotEqualTo(sshPrimary);

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getVariantManager().setWorkingVariant(T0845);
            day.getLoads().iterator().next().setP0(123.0);
            day.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();

            String named = recording.toPartialSsh(Map.of(NetworkEventRecording.VARIANT, T0845));
            assertThat(named).contains(sshLate).doesNotContain(sshPrimary)
                    .contains("08:45:00Z</md:Model.scenarioTime>")
                    .contains("<cim:EnergyConsumer.p>123</cim:EnergyConsumer.p>");

            // the same, without naming anything: the working variant is bound, so it is the one being described
            day.getVariantManager().setWorkingVariant(T0845);
            String implicit = recording.toPartialSsh(Map.of());
            assertEquals(T0845, day.getVariantManager().getWorkingVariantId());
            day.getVariantManager().setWorkingVariant("InitialState");
            assertThat(implicit).contains(sshLate).doesNotContain(sshPrimary)
                    .contains("08:45:00Z</md:Model.scenarioTime>")
                    .contains("<cim:EnergyConsumer.p>123</cim:EnergyConsumer.p>");

            // and on the primary the identity is the network-level one, as it always was
            String primary = recording.toPartialSsh(Map.of(NetworkEventRecording.UNSUPPORTED, "ignore"));
            assertThat(primary).contains(sshPrimary).doesNotContain(sshLate)
                    .contains("08:15:00Z</md:Model.scenarioTime>");
        }
    }

    /**
     * The opt-in rule for the exports, on the network where it is easiest to get wrong.
     *
     * <p>A variant a user cloned from a database-loaded network <em>is</em> tracked - its state is the snapshot
     * its source stood for - but tracking is not an opt-in. An unnamed export while such a clone is the working
     * variant must therefore behave exactly as it did before variants existed, and in particular must still
     * write a change IIDM shares between variants: a line impedance belongs to the equipment model, which is
     * what an ordinary clone is usually about.</p>
     */
    @Test
    void anUnnamedExportOnAUserCloneStillWritesSharedChanges() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network network = load(db, S, 1, T0830);
            network.getVariantManager().cloneVariant("InitialState", "what-if");
            assertThat(byVariant(network)).containsKey("what-if/bound");
            network.getVariantManager().setWorkingVariant("what-if");

            NetworkEventRecording recording = new NetworkEventRecording(network);
            recording.start();
            network.getLoads().iterator().next().setP0(31.0);
            network.getLines().iterator().next().setR(0.31);
            recording.stop();

            assertThat(recording.toCgmesDiffs(Map.of()).keySet())
                    .as("no opt-in, so the shared change is written exactly as before")
                    .containsExactlyInAnyOrder("EQ", "SSH");
            assertThat(recording.toPartialSsh(Map.of(NetworkEventRecording.UNSUPPORTED, "ignore")))
                    .contains("<cim:EnergyConsumer.p>31</cim:EnergyConsumer.p>");
            network.getVariantManager().setWorkingVariant("InitialState");
        }
    }

    /**
     * And in variant mode the same gesture refuses the shared change whichever variant is selected - the primary
     * included, because a value shared by every variant belongs to no single snapshot.
     */
    @Test
    void anUnnamedExportOnThePrimaryRefusesASharedChangeInVariantMode() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            aDay(db);
            Network day = loadVariants(db, S, List.of("", ""), List.of(T0815, T0845));
            assertEquals("InitialState", day.getVariantManager().getWorkingVariantId());

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getLoads().iterator().next().setP0(41.0);
            day.getLines().iterator().next().setR(0.41);
            recording.stop();

            assertThatThrownBy(() -> recording.toCgmesDiffs(Map.of()))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("not stored per variant");
            assertThat(recording.toCgmesDiffs(Map.of(NetworkEventRecording.UNSUPPORTED, "ignore")).keySet())
                    .containsExactly("SSH");
        }
    }

    /**
     * A network that never met a database has no bound variant, so an unnamed export must behave exactly as it
     * did before variants existed - including for a variant the user created.
     */
    @Test
    void anUnnamedExportOfAnUnboundVariantIsUnchanged() {
        Network network = com.powsybl.iidm.network.Network.read(RdfDbUtilTest.microGridBe());
        network.getVariantManager().cloneVariant("InitialState", "scratch");
        network.getVariantManager().setWorkingVariant("scratch");
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        network.getLoads().iterator().next().setP0(64.0);
        recording.stop();

        assertThat(recording.toPartialSsh(Map.of()))
                .contains("<cim:EnergyConsumer.p>64</cim:EnergyConsumer.p>");
        assertEquals("scratch", network.getVariantManager().getWorkingVariantId());
    }
}
