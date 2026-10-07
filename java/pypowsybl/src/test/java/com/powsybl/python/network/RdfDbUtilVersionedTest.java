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
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.python.network.RdfDbTestSupport.columns;
import static com.powsybl.python.network.RdfDbUtilTest.importParameters;
import static com.powsybl.python.network.RdfDbUtilTest.importProperties;
import static com.powsybl.python.network.RdfDbUtilTest.memoryUrl;
import static com.powsybl.python.network.RdfDbUtilTest.microGridBe;
import static com.powsybl.python.network.RdfDbUtilTest.xiidm;
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
    /** The modelling authority of the MicroGrid BE files. */
    static final String BE = "http://elia.be/CGMES/2.4.15";
    /** The modelling authority of the MicroGrid NL files, which share the boundary of BE. */
    static final String NL = "http://tennet.nl/CGMES/2.4.15";
    /** The scenario time of the MicroGrid files: the base timestamp of their trees. */
    static final String BASE = "2014-06-01T10:30:00Z";
    static final String T0815 = "2014-06-01T08:15:00Z";
    static final String T0830 = "2014-06-01T08:30:00Z";
    static final String T0845 = "2014-06-01T08:45:00Z";
    static final String T0930 = "2014-06-01T09:30:00Z";
    /** The modelling authority the steady state hypothesis of {@link #disagreeing} files claims. */
    static final String ELSEWHERE = "http://elsewhere.eu/CGMES";

    /** Store the base grid model as the root of {@code scenario}, and the second model as the root of "other". */
    static Network twoScenarios(RdfDbConnection db) {
        List<String> other = RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.miniBusBranch().dataSource(), OTHER,
                "1", null, null, List.of(), importParameters(), null);
        assertThat(other).isNotEmpty();
        List<String> root = RdfDbUtil.loadCgmes(db, microGridBe(), S, "1", null, null, List.of(), importParameters(),
                null);
        assertThat(root).isNotEmpty();
        return load(db, S, 1, null);
    }

    /** Load one snapshot of a scenario, all profiles, the modelling authority left to the bindings. */
    static Network load(RdfDbConnection db, String scenario, Integer version, String timestamp) {
        return RdfDbUtil.load(db, scenario, name(version), false, timestamp, null, List.of(), importParameters(), List.of(),
                null);
    }

    /** Bring a network to a snapshot of a scenario, all defaults. */
    static RdfDbUtil.UpdateOutcome update(Network network, RdfDbConnection db, String scenario, Integer version,
                                          String timestamp, Map<String, String> options) {
        return RdfDbUtil.update(network, db, scenario, name(version), false, timestamp, null, List.of(), options,
                importParameters(), null);
    }

    /** Ingest the instance files of one timestamp as a further snapshot of {@link #S}. */
    static List<String> ingest(RdfDbConnection db, String scenario, ReadOnlyDataSource files, Integer version,
                               String timestamp) {
        return RdfDbUtil.loadCgmes(db, files, scenario, name(version), timestamp, null, List.of(), importParameters(),
                null);
    }

    /** Store recorded changes as a snapshot of {@code scenario}. */
    static List<String> export(NetworkEventRecording recording, RdfDbConnection db, String scenario,
                               Integer version, String timestamp, Map<String, String> options) {
        return RdfDbUtil.exportRecording(recording, db, scenario, name(version), timestamp, null, List.of(),
                options);
    }

    /** Move one load, which is a steady-state-only change and therefore a fast-route difference. */
    static List<String> record(RdfDbConnection db, Network network, Integer version, String timestamp,
                               double value) {
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = network.getLoads().iterator().next();
        load.setP0(value);
        recording.stop();
        return export(recording, db, S, version, timestamp, Map.of());
    }

    /**
     * The version name of a version number: the tests write the numeric names "1", "2", ... that a scenario's
     * permissive registry appends in order, so that they read like the integer versions they replaced.
     */
    static String name(Integer version) {
        return version == null ? null : String.valueOf(version);
    }

    /** The timestamp and the version of a snapshot, for compact assertions. */
    static String address(SnapshotInfo info) {
        return info.timestamp() + "/" + info.version();
    }

    /**
     * The modelling authority is required by every read of core; the bindings resolve an open one when the scenario
     * holds exactly one tree, which is every scenario of a single TSO.
     */
    @Test
    void anOpenModellingAuthorityIsTheOnlyOneOfTheScenario() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            record(db, network, 2, T0830, 42.0);
            assertEquals(List.of(BE), RdfDbUtil.modellingAuthorities(db, S));

            Network open = RdfDbUtil.load(db, S, "2", false, "2014-06-01T08:30:00Z", null, List.of(), importParameters(),
                    List.of(), null);
            Network named = RdfDbUtil.load(db, S, "2", false, "2014-06-01T08:30:00+00:00", BE, List.of(),
                    importParameters(), List.of(), null);
            assertEquals(42.0, open.getLoads().iterator().next().getP0(), 1e-9);
            assertEquals(42.0, named.getLoads().iterator().next().getP0(), 1e-9);
            assertEquals(BE, RdfDbUtil.identity(open, db, S).get(RdfDbUtil.MODELLING_AUTHORITY));
            assertEquals(T0830, RdfDbUtil.identity(open, db, S).get(RdfDbUtil.TIMESTAMP));
            assertEquals("2", RdfDbUtil.identity(open, db, S).get(RdfDbUtil.VERSION));
            // the network alone knows its address too: it is read off the snapshot IRI, without a request
            Map<String, String> own = RdfDbUtil.identity(open, null, null);
            assertEquals(BE, own.get(RdfDbUtil.MODELLING_AUTHORITY));
            assertEquals(T0830, own.get(RdfDbUtil.TIMESTAMP));
            assertEquals("2", own.get(RdfDbUtil.VERSION));
        }
    }

    /**
     * A write into a scenario that holds exactly one tree resolves an open modelling authority to that tree, as a
     * read does - an ingestion ({@code putAsDiff}), an export ({@code putDiff}) and a checkpoint alike, even when the
     * files' headers disagree or the network is at no snapshot of the database - but not files whose headers agree on
     * another authority, which are refused. The first root of a scenario has no
     * tree to resolve to, and a scenario of several trees no single one: there the files (or the network) decide.
     *
     * @param scenario a scenario of its own, empty
     * @param fresh    another empty scenario
     */
    static void anOpenAuthorityOfAWriteIsTheOnlyTreeOfTheScenario(RdfDbConnection db, String scenario, String fresh) {
        assertThatThrownBy(() -> ingest(db, fresh, disagreeing(microGridBe()), 1, null))
                .as("a first root: the files decide, and these disagree")
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("do not agree on one");
        assertFalse(RdfDbUtil.isVersioned(db, fresh));

        assertThat(ingest(db, scenario, microGridBe(), 1, null)).isNotEmpty();
        double before = load(db, scenario, 1, null).getLoads().iterator().next().getP0();
        assertThat(ingest(db, scenario, disagreeing(timestampFiles("open", 1.5, T0830, false)), null, T0830))
                .isNotEmpty();
        Network fromFiles = Network.read(microGridBe(), importProperties());
        assertThat(exportLoad(db, fromFiles, scenario, T0845, 44.0)).isNotEmpty();
        String checkpoint = RdfDbUtil.checkpoint(db, scenario, null, T0845, null);

        assertThatThrownBy(() -> ingest(db, scenario, CgmesConformity1Catalog.microGridBaseCaseNL().dataSource(),
                null, T0930))
                .as("files that agree on another authority are another TSO's: neither diffed into BE's tree nor a"
                        + " second tree unless named")
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("state modelling authority " + NL + " but the scenario's only tree is " + BE
                        + ": pass " + BE + " in the address to store them under it, or " + NL
                        + " to open a second tree");
        assertThat(RdfDbUtil.snapshots(db, scenario)).hasSize(3)
                .allMatch(info -> BE.equals(info.modellingAuthority()))
                .anyMatch(info -> info.iri().equals(checkpoint) && info.hasFull());
        assertEquals(before * 1.5, load(db, scenario, null, T0830).getLoads().iterator().next().getP0(), 1e-6);
        assertEquals(44.0, load(db, scenario, null, T0845).getLoads().iterator().next().getP0(), 1e-9);

        assertThat(RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.microGridBaseCaseNL().dataSource(), scenario, "1",
                null, NL, List.of(), importParameters(), null)).isNotEmpty();
        assertThatThrownBy(() -> ingest(db, scenario, disagreeing(timestampFiles("two", 2.0, T0930, false)), null,
                T0930))
                .as("two trees: the files decide again")
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("do not agree on one");
        Network again = Network.read(microGridBe(), importProperties());
        assertThatThrownBy(() -> exportLoad(db, again, scenario, T0930, 45.0))
                .as("two trees: the network decides again")
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("is at no snapshot");
        assertThatThrownBy(() -> RdfDbUtil.checkpoint(db, scenario, null, T0845, null))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("cannot be left open");
        assertThat(RdfDbUtil.snapshots(db, scenario)).hasSize(4);
    }

    /** Move one load of {@code network} and store the change at {@code timestamp}, the authority left open. */
    private static List<String> exportLoad(RdfDbConnection db, Network network, String scenario, String timestamp,
                                           double value) {
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        network.getLoads().iterator().next().setP0(value);
        recording.stop();
        return export(recording, db, scenario, null, timestamp, Map.of());
    }

    @Test
    void anOpenAuthorityOfAWriteIsTheOnlyTreeOfTheScenario() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            anOpenAuthorityOfAWriteIsTheOnlyTreeOfTheScenario(db, S, "fresh");
        }
    }

    /**
     * A second TSO's tree in the same scenario: from then on an open authority is ambiguous and is refused, and the
     * named one loads the second TSO's network as its files do.
     */
    @Test
    void twoModellingAuthoritiesMustBeNamed() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            twoScenarios(db);
            assertThat(RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.microGridBaseCaseNL().dataSource(), S, "1",
                    null, NL, List.of(), importParameters(), null)).isNotEmpty();
            assertEquals(List.of(BE, NL), RdfDbUtil.modellingAuthorities(db, S));

            List<Runnable> reads = List.of(
                () -> load(db, S, 1, BASE),
                () -> RdfDbUtil.checkpoint(db, S, "1", null, null),
                () -> RdfDbUtil.versions(db, S, null, null),
                () -> RdfDbUtil.timestamps(db, S, null));
            for (Runnable read : reads) {
                assertThatThrownBy(read::run)
                        .isInstanceOf(PowsyblException.class)
                        .hasMessageContaining("[" + BE + ", " + NL + "]")
                        .hasMessageContaining("cannot be left open");
            }
            // named, every read works - including the network of the second authority, whose boundary is the one
            // the first stored
            Network nl = RdfDbUtil.load(db, S, "1", false, null, NL, List.of(), importParameters(), List.of(), null);
            assertEquals(xiidm(Network.read(CgmesConformity1Catalog.microGridBaseCaseNL().dataSource(),
                    importProperties())), xiidm(nl));
            assertEquals(NL, RdfDbUtil.identity(nl, db, S).get(RdfDbUtil.MODELLING_AUTHORITY));
            assertEquals(1, RdfDbUtil.versions(db, S, BASE, NL).size());
            assertEquals(1, RdfDbUtil.timestamps(db, S, NL).size());
            assertThat(RdfDbUtil.checkpoint(db, S, "1", null, BE)).contains("snapshot");

            // the CGM question - every authority at one moment - is one query
            List<RdfDbUtil.AssemblyRow> assembly = RdfDbUtil.assembly(db, S, BASE, null);
            assertEquals(List.of(BE, NL), assembly.stream().map(RdfDbUtil.AssemblyRow::modellingAuthority).toList());
            assertThat(assembly).allMatch(row -> row.snapshot() != null && row.snapshot().version().equals("1"));
            assertThat(RdfDbUtil.assembly(db, S, T0830, null))
                    .as("an authority without a snapshot at that moment is a row without one, not an absent row")
                    .hasSize(2).allMatch(row -> row.snapshot() == null);
            assertEquals(List.of("modelling_authority", "snapshot", "timestamp", "version", "rank", "profiles",
                            "kind", "depth", "has_full", "fast", "members"),
                    columns(RdfDbUtil.assemblyMapper(), assembly));
            assertThatThrownBy(() -> RdfDbUtil.assembly(db, S, "", null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("needs a timestamp");
        }
    }

    /** What crosses the C API: an ISO instant with an offset; a version is a name and crosses as text. */
    @Test
    void theCArgumentsAreReadStrictly() {
        assertEquals(Instant.parse(T0830), RdfDbUtil.toInstant("2014-06-01T10:30:00+02:00"));
        assertEquals(null, RdfDbUtil.toInstant(""));
        assertThatThrownBy(() -> RdfDbUtil.toInstant("2014-06-01T08:30:00"))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("is not a timestamp");
        assertThatThrownBy(() -> RdfDbUtil.toInstant("8:30"))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("is not a timestamp");
    }

    /**
     * A version is a name the registry of the scenario ranks: a read at a name takes the highest ranking version at
     * or below it, an exact read that version or nothing.
     */
    @Test
    void aReadAtANameTakesTheHighestRankAtOrBelowIt() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            for (String name : List.of("DA", "ID", "RT")) {
                RdfDbUtil.editRegistry(db, S, "add", name, null, List.of(), List.of(), false);
            }
            NetworkEventRecording recording = new NetworkEventRecording(network);
            recording.start();
            network.getLoads().iterator().next().setP0(41.0);
            recording.stop();
            RdfDbUtil.exportRecording(recording, db, S, "DA", T0830, null, List.of(), Map.of());
            recording.start();
            network.getLoads().iterator().next().setP0(42.0);
            recording.stop();
            RdfDbUtil.exportRecording(recording, db, S, "ID", T0830, null, List.of(), Map.of());

            assertEquals(List.of("DA", "ID"), RdfDbUtil.versions(db, S, T0830, null).stream()
                    .map(SnapshotInfo::version).toList());
            assertEquals(List.of(20, 30), RdfDbUtil.versions(db, S, T0830, null).stream()
                    .map(SnapshotInfo::rank).toList());
            Function<Network, Double> p0 = n -> n.getLoads().iterator().next().getP0();
            assertEquals(42.0, p0.apply(RdfDbUtil.load(db, S, "RT", false, T0830, null, List.of(),
                    importParameters(), List.of(), null)), 1e-9, "RT is not written: ID is the highest below it");
            assertEquals(41.0, p0.apply(RdfDbUtil.load(db, S, "DA", true, T0830, null, List.of(),
                    importParameters(), List.of(), null)), 1e-9);
            assertThatThrownBy(() -> RdfDbUtil.load(db, S, "RT", true, T0830, null, List.of(), importParameters(),
                    List.of(), null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("=RT)");
            assertThatThrownBy(() -> RdfDbUtil.load(db, S, null, true, T0830, null, List.of(), importParameters(),
                    List.of(), null))
                    .as("the head is never exact")
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("an exact address needs a version name");

            Network reader = RdfDbUtil.load(db, S, "DA", true, T0830, null, List.of(), importParameters(), List.of(),
                    null);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(reader, db, S, "RT", false, T0830, null, List.of(),
                    Map.of(), importParameters(), null);
            assertEquals("diff", RdfDbUtil.updateInfo(outcome).get(RdfDbUtil.ROUTE));
            assertEquals("ID", RdfDbUtil.identity(reader, null, null).get(RdfDbUtil.VERSION));
            assertEquals("ID", RdfDbUtil.assembly(db, S, T0830, "RT").get(0).snapshot().version());
            assertEquals(30, RdfDbUtil.assembly(db, S, T0830, "RT").get(0).snapshot().rank());
        }
    }

    /** The registry is read as rows and edited through one entry point whose answer is the registry's state. */
    @Test
    void theRegistryIsEditedThroughOneEntryPoint() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            String fresh = "2014-06-01-registry";
            assertThat(RdfDbUtil.registry(db, fresh)).isEmpty();
            assertEquals(Map.of(RdfDbUtil.RANK, "", "permissive", "true", "rev", "0"),
                    RdfDbUtil.editRegistry(db, fresh, "refresh", null, null, List.of(), List.of(), false));
            Map<String, String> created = RdfDbUtil.editRegistry(db, fresh, "create", null, null, List.of("DA", "ID"),
                    List.of(), false);
            assertEquals("false", created.get("permissive"));
            assertThatThrownBy(() -> ingest(db, fresh, microGridBe(), 7, null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("version '7' is not registered in scenario '" + fresh + "'");
            assertThat(RdfDbUtil.loadCgmes(db, microGridBe(), fresh, null, null, BE, List.of(), importParameters(),
                    null)).isNotEmpty();
            assertEquals("DA", RdfDbUtil.snapshots(db, fresh).get(0).version(), "a root takes the lowest name");

            assertEquals("30", RdfDbUtil.editRegistry(db, fresh, "add", "RT", null, List.of(), List.of(), false)
                    .get(RdfDbUtil.RANK));
            assertEquals("25", RdfDbUtil.editRegistry(db, fresh, "insert", "IDA", "ID", List.of(), List.of(), false)
                    .get(RdfDbUtil.RANK));
            RdfDbUtil.editRegistry(db, fresh, "rename", "IDA", "ID2", List.of(), List.of(), false);
            RdfDbUtil.editRegistry(db, fresh, "rerank", null, null, List.of("ID2", "RT"), List.of(40, 35), false);
            RdfDbUtil.editRegistry(db, fresh, "mark_transient", "ID2", null, List.of(), List.of(), true);
            assertEquals(List.of(new RdfDbUtil.RegistryRow("DA", 10, false), new RdfDbUtil.RegistryRow("ID", 20, false),
                            new RdfDbUtil.RegistryRow("RT", 35, false), new RdfDbUtil.RegistryRow("ID2", 40, true)),
                    RdfDbUtil.registry(db, fresh));
            RdfDbUtil.editRegistry(db, fresh, "delete", "ID2", null, List.of(), List.of(), false);
            assertEquals(List.of("name", "rank", "transient"),
                    columns(RdfDbUtil.registryMapper(), RdfDbUtil.registry(db, fresh)));
            assertThatThrownBy(() -> RdfDbUtil.editRegistry(db, fresh, "rerank", null, null, List.of("DA"),
                    List.of(35), false))
                    .as("two names never share a rank")
                    .isInstanceOf(PowsyblException.class);
            assertThatThrownBy(() -> RdfDbUtil.editRegistry(db, fresh, "rename", "DA", "D1", List.of(), List.of(),
                    false))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("cannot be renamed");
            assertThatThrownBy(() -> RdfDbUtil.editRegistry(db, fresh, "nope", null, null, List.of(), List.of(),
                    false))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("unknown version registry operation 'nope'");
        }
    }

    /**
     * A profile is a name: a custom one is stored whole next to the standard ones, left out of the network and handed
     * back as a graph of statements.
     */
    @Test
    void aCustomProfileIsStoredWholeAndFetchedAsStatements() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            assertThat(RdfDbUtil.loadCgmes(db, withCustomProfile(microGridBe(), "urn:uuid:cfg-1", BASE, "120"), S, "1",
                    null, BE, List.of(), importParameters(), null)).contains("urn:uuid:cfg-1");
            Map<String, String> graphs = RdfDbUtil.profiles(db, S, null, false, null, null);
            assertThat(graphs).containsKey("CFG");
            List<RdfDbUtil.StatementRow> rows = RdfDbUtil.fetchGraph(db, S, graphs.get("CFG"));
            assertThat(rows).anyMatch(row -> row.subject().equals("http://example.org/cfg/setting-1")
                    && row.predicate().equals(CFG_NS + "Setting.value") && row.object().equals("120") && !row.isIri());
            assertThat(rows).anyMatch(row -> row.isIri() && row.object().equals("http://example.org/cfg/setting-1"));
            assertEquals(List.of("index", "subject", "predicate", "object", "is_iri"),
                    columns(RdfDbUtil.statementsMapper(), rows));

            Network network = load(db, S, 1, null);
            assertEquals(xiidm(Network.read(microGridBe(), importProperties())), xiidm(network),
                    "the custom profile never reaches the conversion");
            Network projected = RdfDbUtil.load(db, S, "1", false, null, null, List.of("EQ", "SSH", "TP", "SV", "CFG"),
                    importParameters(), List.of(), null);
            assertEquals(xiidm(RdfDbUtil.load(db, S, "1", false, null, null, List.of("EQ", "SSH", "TP", "SV"),
                    importParameters(), List.of(), null)), xiidm(projected), "naming the custom profile changes nothing");

            // a further timestamp that names the custom profile stores the changed file whole
            ReadOnlyDataSource later = withCustomProfile(timestampFiles("cfg", 1.5, T0830, false), "urn:uuid:cfg-2",
                    T0830, "130");
            RdfDbUtil.loadCgmes(db, later, S, null, T0830, null, List.of("EQ", "SSH", "CFG"), importParameters(),
                    null);
            String laterGraph = RdfDbUtil.profiles(db, S, null, false, T0830, null).get("CFG");
            assertThat(laterGraph).isNotEqualTo(graphs.get("CFG"));
            assertThat(RdfDbUtil.fetchGraph(db, S, laterGraph)).anyMatch(row -> row.object().equals("130"));

            assertThatThrownBy(() -> RdfDbUtil.load(db, S, "1", false, null, null, List.of("1X"), importParameters(),
                    List.of(), null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("'1X' is not a profile name");
            assertThatThrownBy(() -> RdfDbUtil.fetchGraph(db, S, " "))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("a graph IRI is required");
            NetworkEventRecording recording = new NetworkEventRecording(network);
            recording.start();
            network.getLoads().iterator().next().setP0(12.0);
            recording.stop();
            assertThatThrownBy(() -> RdfDbUtil.exportRecording(recording, db, S, null, null, null, List.of("CFG"),
                    Map.of()))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("'CFG' is a custom one");
        }
    }

    @Test
    void aRootAndThenVersionsFromARecording() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network network = twoScenarios(db);
            assertTrue(RdfDbUtil.isVersioned(db, S));
            assertThat(record(db, network, null, T0830, 42.0)).isNotEmpty();
            assertThat(record(db, network, 3, T0830, 43.0)).as("versions may leave gaps").isNotEmpty();

            assertThat(RdfDbUtil.snapshots(db, S).stream().map(RdfDbUtilVersionedTest::address))
                    .containsExactlyInAnyOrder(BASE + "/1", T0830 + "/1", T0830 + "/3");
            assertEquals(1, RdfDbUtil.snapshots(db, OTHER).size(), "the other scenario is untouched");
            assertEquals(2, RdfDbUtil.timestamps(db, S, null).size());
            assertEquals(List.of("1", "3"), RdfDbUtil.versions(db, S, T0830, null).stream()
                    .map(SnapshotInfo::version).toList());
            assertEquals(List.of(10, 20), RdfDbUtil.versions(db, S, T0830, null).stream()
                    .map(SnapshotInfo::rank).toList(), "the permissive registry appended 3 after 1");
            assertThatThrownBy(() -> record(db, network, 1, T0830, 44.0))
                    .as("a new version ranks above the head it is written on")
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("version '1' (rank 10) is not above the parent '3' (rank 20)");
            assertThat(RdfDbUtil.models(db, S)).anyMatch(model -> model.isDiff() && model.fastPredicatesOnly());
        }
    }

    @Test
    void updateRoutesAreNoopThenDiff() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            Network sender = twoScenarios(db);
            record(db, sender, 1, T0830, 43.0);

            Network receiver = load(db, S, 1, null);
            RdfDbUtil.UpdateOutcome noop = update(receiver, db, S, 1, null, Map.of());
            Map<String, String> info = RdfDbUtil.updateInfo(noop);
            assertEquals("noop", info.get(RdfDbUtil.ROUTE));
            assertEquals(S, info.get(RdfDbUtil.SCENARIO));
            assertThat(info).containsKeys(RdfDbUtil.DIFF_COUNT, RdfDbUtil.STATEMENT_COUNT, "plan_ms", "apply_ms");
            assertThatThrownBy(() -> RdfDbUtil.replacement(noop))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("no replacement network: route was noop");

            RdfDbUtil.UpdateOutcome diff = update(receiver, db, S, 1, T0830, Map.of());
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
            ingest(db, S, timestampFiles("drift", 1.2, T0930, true), 1, T0930);
            assertThat(RdfDbUtil.models(db, S))
                    .anyMatch(model -> model.isDiff() && !model.fastPredicatesOnly());

            RdfDbUtil.UpdateOutcome outcome = update(network, db, S, 1, T0930, Map.of());
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
            RdfDbUtil.UpdateOutcome outcome = update(network, db, OTHER, 1, null, Map.of());
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
    void theLegacyProfileRouteStillAnswersUpdate() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            ingest(db, S, microGridBe(), null, null);
            Network network = load(db, S, null, null);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, S, null, false, null, null,
                    List.of("SSH"),
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
            assertThatThrownBy(() -> export(recording, db, OTHER, 2, null, Map.of()))
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
                assertThatThrownBy(() -> export(recording, db, S, 2, null, Map.of(decided, "whatever")))
                        .isInstanceOf(PowsyblException.class)
                        .hasMessageContaining("is decided by the database");
            }
        }
    }

    /**
     * A day does not arrive as recordings but as files: a TSO exports one set of instance files per timestamp, and
     * what the database should hold is the base plus what each of them changed. That is what
     * {@code SnapshotCatalog.putAsDiff} does, and what {@link RdfDbUtil#loadCgmes} routes to once a scenario has a
     * root.
     */
    @Test
    void aFurtherSnapshotIsIngestedFromFiles() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            twoScenarios(db);
            Network base = load(db, S, 1, null);
            double before = base.getLoads().iterator().next().getP0();

            List<String> members = ingest(db, S, timestampFiles("t0830", 1.5, T0830, false), null, T0830);
            assertThat(members).isNotEmpty();

            List<SnapshotInfo> snapshots = RdfDbUtil.snapshots(db, S);
            assertEquals(2, snapshots.size());
            assertThat(snapshots).anyMatch(info -> info.kind() == SnapshotInfo.Kind.DIFF
                    && (T0830 + "/1").equals(address(info)) && BE.equals(info.modellingAuthority()));
            assertEquals(1, RdfDbUtil.snapshots(db, OTHER).size(), "the other scenario is untouched");
            assertThat(db.snapshots(S).lastIngestStatistics()).isNotNull();

            Network reader = load(db, S, 1, T0830);
            assertEquals(before * 1.5, reader.getLoads().iterator().next().getP0(), 1e-6);
        }
    }

    /**
     * The daily CGMES export of one timestamp: the base case with the steady state file rewritten.
     *
     * <p>The same approach core's own {@code TimestepFixtures} takes, and for the same reason: re-exporting the
     * network through the CGMES exporter would differ from the base in a hundred incidental ways, and the test
     * would be about the exporter rather than about the change. Everything but the steady state file is copied
     * byte for byte, boundary included - {@code putAsDiff} checks that the boundary did not move.</p>
     *
     * @param suffix   what makes the model identifier of this timestamp unique
     * @param factor   what the active power of every energy consumer is multiplied by
     * @param instant  the scenario time the files claim
     * @param eqDrift  whether the equipment model drifts too: one line is renamed, which no in-place update can
     *                 apply, so the difference is stored but is not fast-route capable
     */
    static ReadOnlyDataSource timestampFiles(String suffix, double factor, String instant, boolean eqDrift) {
        return rewrite(microGridBe(), (name, content) -> {
            if (name.contains("_SSH_")) {
                String ssh = content.replaceFirst("(?s)(<md:FullModel[^>]*rdf:about=\")[^\"]*(\")",
                        "$1urn:uuid:ssh-" + suffix + "$2");
                ssh = ssh.replaceFirst("(<md:Model\\.scenarioTime>)[^<]*(</md:Model\\.scenarioTime>)",
                        "$1" + instant + "$2");
                return scaleConsumers(ssh, factor);
            }
            if (eqDrift && name.contains("_EQ_") && !name.contains("_EQ_BD")) {
                String eq = content.replaceFirst("(?s)(<md:FullModel[^>]*rdf:about=\")[^\"]*(\")",
                        "$1urn:uuid:eq-" + suffix + "$2");
                return eq.replaceFirst("(?s)(<cim:ACLineSegment[^>]*>.*?<cim:IdentifiedObject.name>)"
                        + "[^<]*(</cim:IdentifiedObject.name>)", "$1drifted-" + suffix + "$2");
            }
            return content;
        });
    }

    /**
     * The given files with the steady state hypothesis claiming the modelling authority {@link #ELSEWHERE}, so that
     * the equipment and steady state hypothesis headers disagree - as those of {@code CGMES_Full.zip} do - and an
     * open authority cannot be read off the files.
     */
    static ReadOnlyDataSource disagreeing(ReadOnlyDataSource files) {
        return rewrite(files, (name, content) -> name.contains("_SSH_")
                ? content.replaceFirst("(<md:Model\\.modelingAuthoritySet>)[^<]*"
                        + "(</md:Model\\.modelingAuthoritySet>)", "$1" + ELSEWHERE + "$2")
                : content);
    }

    /** The file name of a synthetic custom profile: its last token, {@code CFG}, is its profile. */
    static final String CFG = "MicroGridTestConfiguration_BC_BE_CFG.xml";
    /** The namespace of the synthetic custom profile. */
    static final String CFG_NS = "http://example.org/Configuration/1#";

    /**
     * The MicroGrid BE files plus a custom profile {@code CFG} - three settings an application keeps next to the grid
     * model, which the CGMES conversion knows nothing about.
     */
    static ReadOnlyDataSource withCustomProfile(ReadOnlyDataSource base, String modelId, String instant, String value) {
        MemDataSource files = (MemDataSource) rewrite(base, (name, content) -> content);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:cfg="%s">
                  <md:FullModel rdf:about="%s">
                    <md:Model.scenarioTime>%s</md:Model.scenarioTime>
                    <md:Model.created>2014-06-01T09:00:00Z</md:Model.created>
                    <md:Model.version>1</md:Model.version>
                    <md:Model.profile>http://example.org/Configuration/1</md:Model.profile>
                    <md:Model.modelingAuthoritySet>http://elia.be/CGMES/2.4.15</md:Model.modelingAuthoritySet>
                  </md:FullModel>
                  <cfg:Setting rdf:about="http://example.org/cfg/setting-1">
                    <cfg:Setting.name>ramp limit</cfg:Setting.name>
                    <cfg:Setting.value>%s</cfg:Setting.value>
                  </cfg:Setting>
                  <cfg:Setting rdf:about="http://example.org/cfg/setting-2">
                    <cfg:Setting.next rdf:resource="http://example.org/cfg/setting-1"/>
                  </cfg:Setting>
                </rdf:RDF>
                """.formatted(CFG_NS, modelId, instant, value);
        try (OutputStream out = files.newOutputStream(CFG, false)) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** A copy of every file of {@code source}, each one's content passed through {@code edit(name, content)}. */
    private static ReadOnlyDataSource rewrite(ReadOnlyDataSource source, BinaryOperator<String> edit) {
        MemDataSource target = new MemDataSource();
        try {
            for (String name : source.listNames(".*")) {
                String content;
                try (InputStream stream = source.newInputStream(name)) {
                    content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                try (OutputStream out = target.newOutputStream(name, false)) {
                    out.write(edit.apply(name, content).getBytes(StandardCharsets.UTF_8));
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
                () -> RdfDbUtil.timestamps(db, " ", null),
                () -> RdfDbUtil.modellingAuthorities(db, " "),
                () -> RdfDbUtil.assembly(db, " ", BASE, null),
                () -> RdfDbUtil.models(db, " "),
                () -> RdfDbUtil.versions(db, " ", null, null),
                () -> RdfDbUtil.graphs(db, " "),
                () -> RdfDbUtil.clear(db, " "),
                () -> RdfDbUtil.checkpoint(db, " ", "1", null, null),
                () -> load(db, " ", 1, null),
                () -> ingest(db, " ", microGridBe(), 1, null),
                () -> export(recording, db, " ", 2, null, Map.of()),
                () -> RdfDbUtil.identity(network, db, " "),
                () -> update(network, db, " ", 1, null, Map.of()));
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
            record(db, network, 1, T0830, 45.0);
            String iri = RdfDbUtil.checkpoint(db, S, "1", T0830, null);
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
            record(db, network, 1, T0830, 46.0);

            assertEquals(List.of("scenario", "modelling_authorities", "versioned", "snapshot_count"),
                    columns(RdfDbUtil.scenariosMapper(), RdfDbUtil.scenarioRows(db)));
            assertEquals(2, RdfDbUtil.scenarioRows(db).size());
            assertEquals(BE, RdfDbUtil.scenarioRows(db).stream().filter(row -> row.scenario().equals(S))
                    .findFirst().orElseThrow().modellingAuthorities());
            assertEquals(List.of("snapshot", "scenario", "modelling_authority", "timestamp", "version", "rank",
                            "profiles", "kind", "parent", "edge", "depth", "has_full", "fast", "members", "created",
                            "description"),
                    columns(RdfDbUtil.snapshotsMapper(), RdfDbUtil.snapshots(db, S)));
            assertEquals(List.of("timestamp", "modelling_authority", "root", "head", "version_count",
                            "pinned_base"),
                    columns(RdfDbUtil.timestampsMapper(), RdfDbUtil.timestamps(db, S, null)));
            assertEquals(List.of("id", "scenario", "subset", "kind", "version", "supersedes", "depends_on", "fast",
                            "triple_count", "chain_depth", "created"),
                    columns(RdfDbUtil.modelsMapper(), RdfDbUtil.models(db, S)));
            assertFalse(RdfDbUtil.snapshots(db, "never-uploaded").iterator().hasNext(),
                    "an unknown scenario lists nothing rather than failing");
            assertThat(RdfDbUtil.timestamps(db, "never-uploaded", null)).isEmpty();
            assertThat(RdfDbUtil.versions(db, "never-uploaded", null, null)).isEmpty();
            assertThat(RdfDbUtil.modellingAuthorities(db, "never-uploaded")).isEmpty();
        }
    }
}
