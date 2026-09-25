/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.cgmes.rdfdb.RdfDbException;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the JVM side of the pypowsybl RDF database bindings, on the in-process {@code memory:} backend.
 *
 * <p>The native entry points of {@link RdfDbCFunctions} cannot be exercised on the JVM, which is why they are one
 * liners and everything they delegate to is tested here. {@link RdfDbUtilFusekiTest} repeats the essential ones
 * against a real SPARQL server.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilTest {

    private static final String SCENARIO = "2021-02-09";
    private static final String SECOND_SCENARIO = "2021-02-10";

    static String memoryUrl() {
        return "memory:" + UUID.randomUUID().toString().replace("-", "");
    }

    static ReadOnlyDataSource microGridBe() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    static Map<String, String> importParameters() {
        // A CGM is split into subnetworks at file level, above any triple store, so both paths are told not to.
        return Map.of("iidm.import.cgmes.cgm-with-subnetworks", "false");
    }

    static Properties importProperties() {
        Properties p = new Properties();
        p.putAll(importParameters());
        return p;
    }

    /**
     * The whole XIIDM document of a network, sorted so that it does not depend on the order elements were created in.
     *
     * <p>On the in-process {@code memory:} backend this is the comparison to make: the store hands its statements
     * back in insertion order, so the database path walks its query results in the same order as the file path and
     * the two networks agree down to the node numbering. Nothing a graph transfer could break - a lost terminal, a
     * flipped {@code connected} flag, a changed impedance, a missing state variable, a dropped extension - survives
     * it. {@link RdfDbUtilFusekiTest} cannot use it, because a server serialises the rows of a graph in its own
     * index order, and compares {@link #summary} instead.</p>
     */
    static String xiidm(Network network) {
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        NetworkSerDe.write(network, new ExportOptions().setSorted(true), document);
        return document.toString(StandardCharsets.UTF_8);
    }

    /**
     * What both loading paths must agree on even when the node numbering does not: every identified object, and the
     * values a steady state carries.
     *
     * <p>This is the comparison for a real SPARQL server. A CGMES conversion walks over SPARQL query results, whose
     * order is not a property of the data, so a node-breaker network legitimately comes out of a server with its
     * nodes renumbered, and the calculated buses are named after the lowest node of their voltage level. The set of
     * identifiers and the values attached to them are invariant, and that is what these bindings have to preserve.
     * On the {@code memory:} backend the stronger {@link #xiidm} comparison holds and is asserted as well.</p>
     */
    static List<String> summary(Network network) {
        List<String> lines = new ArrayList<>();
        network.getIdentifiables().forEach(i -> lines.add("id " + i.getType() + " " + i.getId()));
        network.getLoadStream().forEach(l -> lines.add(String.format("load %s p0=%.4f q0=%.4f", l.getId(), l.getP0(), l.getQ0())));
        network.getGeneratorStream().forEach(g -> lines.add(String.format("gen %s targetP=%.4f targetQ=%.4f targetV=%.4f",
                g.getId(), g.getTargetP(), g.getTargetQ(), g.getTargetV())));
        network.getSwitchStream().forEach(s -> lines.add("switch " + s.getId() + " open=" + s.isOpen()));
        network.getTwoWindingsTransformerStream()
                .filter(t -> t.getRatioTapChanger() != null)
                .forEach(t -> lines.add("rtc " + t.getId() + " tap=" + t.getRatioTapChanger().getTapPosition()));
        lines.sort(String::compareTo);
        return lines;
    }

    static Map<String, List<String>> dataframe(com.powsybl.dataframe.DataframeMapper<List<com.powsybl.cgmes.rdfdb.GraphInfo>, Void> mapper,
                                               List<com.powsybl.cgmes.rdfdb.GraphInfo> graphs) {
        List<Series> series = new ArrayList<>();
        mapper.createDataframe(graphs, new DefaultDataframeHandler(series::add), new DataframeFilter());
        Map<String, List<String>> columns = new LinkedHashMap<>();
        series.forEach(s -> columns.put(s.getName(), List.of(s.getStrings())));
        return columns;
    }

    @Test
    void aMemoryDatabaseStartsEmptyAndClosesTwiceWithoutComplaining() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            assertThat(RdfDbUtil.scenarios(db)).isEmpty();
            db.close();
        }
    }

    @Test
    void theNetworkFromTheDatabaseIsTheNetworkFromTheFiles() {
        Network fromFiles = Network.read(microGridBe(), importProperties());
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            List<String> graphs = RdfDbUtil.loadCgmes(db, SCENARIO, microGridBe(), importParameters(), null);
            assertThat(graphs).hasSizeGreaterThanOrEqualTo(4);
            Network fromDb = RdfDbUtil.load(db, SCENARIO, importParameters(), List.of(), null);
            assertEquals(summary(fromFiles), summary(fromDb));
            // The in-process store preserves the statement order of the files, so the documents match exactly
            assertEquals(xiidm(fromFiles), xiidm(fromDb));
        }
    }

    @Test
    void theGraphCatalogueNamesTheInstanceFilesAndTheirSubsets() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            RdfDbUtil.loadCgmes(db, SCENARIO, microGridBe(), importParameters(), null);
            Map<String, List<String>> columns = dataframe(RdfDbUtil.graphsMapper(), RdfDbUtil.graphs(db, SCENARIO));
            assertThat(columns.keySet()).containsExactly("name", "subset", "graph");
            assertThat(columns.get("name")).allSatisfy(n -> assertThat(n).startsWith("contexts:"));
            assertThat(columns.get("subset")).contains(CgmesSubset.EQUIPMENT.getIdentifier(),
                    CgmesSubset.STEADY_STATE_HYPOTHESIS.getIdentifier(),
                    CgmesSubset.TOPOLOGY.getIdentifier(),
                    CgmesSubset.STATE_VARIABLES.getIdentifier());
            assertThat(columns.get("graph")).allSatisfy(g -> assertThat(g).contains(SCENARIO));
        }
    }

    @Test
    void twoScenariosInOneDatabaseDoNotSeeEachOther() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            RdfDbUtil.loadCgmes(db, SCENARIO, microGridBe(), importParameters(), null);
            RdfDbUtil.loadCgmes(db, SECOND_SCENARIO, CgmesConformity1Catalog.miniBusBranch().dataSource(),
                    importParameters(), null);
            assertThat(RdfDbUtil.scenarios(db)).containsExactly(SCENARIO, SECOND_SCENARIO);

            // Each scenario yields exactly its own model: neither sees a statement of the other
            Network be = RdfDbUtil.load(db, SCENARIO, importParameters(), List.of(), null);
            Network mini = RdfDbUtil.load(db, SECOND_SCENARIO, importParameters(), List.of(), null);
            assertEquals(xiidm(Network.read(microGridBe(), importProperties())), xiidm(be));
            assertEquals(xiidm(Network.read(CgmesConformity1Catalog.miniBusBranch().dataSource(), importProperties())),
                    xiidm(mini));

            RdfDbUtil.clear(db, SCENARIO);
            assertThat(RdfDbUtil.scenarios(db)).containsExactly(SECOND_SCENARIO);
            assertThat(RdfDbUtil.graphs(db, SCENARIO)).isEmpty();
        }
    }

    @Test
    void anSshFromTheDatabaseUpdatesANetworkInPlace(@TempDir Path tmp) {
        Network network = Network.read(microGridBe(), importProperties());
        Load load = network.getLoads().iterator().next();
        double changed = load.getP0() + 123.0;

        // A steady state exported from a changed copy is the update the database has to be able to hand back
        Network changedNetwork = Network.read(microGridBe(), importProperties());
        changedNetwork.getLoad(load.getId()).setP0(changed);
        Properties exportParams = new Properties();
        exportParams.put("iidm.export.cgmes.profiles", List.of("SSH"));
        changedNetwork.write("CGMES", exportParams, tmp.resolve("changed"));

        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            ReadOnlyDataSource ssh = new DirectoryDataSource(tmp, "changed");
            RdfDbUtil.loadCgmes(db, SCENARIO, ssh, importParameters(), null);
            assertEquals("update", RdfDbUtil.update(network, db, SCENARIO, List.of("SSH"), importParameters(), null));
            assertEquals(changed, network.getLoad(load.getId()).getP0(), 1e-6);
        }
    }

    /**
     * Follow-up WP5 replaced the "not yet implemented" rejection of {@code version} and {@code timestep} of
     * follow-up WP1 by the real versioned addressing; what is left of the old check is that a timestep without a
     * version addresses nothing.
     */
    @Test
    void aTimestepWithoutAVersionIsRejected() {
        assertEquals(null, RdfDbUtil.timestepOrNull(""));
        assertEquals(null, RdfDbUtil.timestepOrNull(null));
        assertEquals("8:30", RdfDbUtil.timestepOrNull("8:30"));
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            assertThatThrownBy(() -> RdfDbUtil.loadCgmes(db, microGridBe(), SCENARIO, null, "8:30", Map.of(), null))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("a timestep addresses a snapshot, so it needs a version");
        }
    }

    @Test
    void aScenarioIsRequiredAndMustNotBeBlank() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            assertThatThrownBy(() -> RdfDbUtil.graphs(db, "  "))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> RdfDbUtil.loadCgmes(db, null, microGridBe(), Map.of(), null))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("must not be blank");
        }
    }

    @Test
    void subsetNamesAreTranslatedAndUnknownOnesAreNamedInTheError() {
        assertEquals(EnumSet.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesSubset.STATE_VARIABLES),
                RdfDbUtil.toSubsets(List.of("ssh", " SV ")));
        assertEquals(EnumSet.of(CgmesSubset.EQUIPMENT_BOUNDARY), RdfDbUtil.toSubsets(List.of("EQ_BD")));
        assertThatThrownBy(() -> RdfDbUtil.toSubsets(List.of("NOPE")))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("Unknown CGMES subset 'NOPE'")
                .hasMessageContaining("SSH");
    }

    @Test
    void theConnectionOptionsOfTheCApiAreUnderstoodAndUnknownOnesRefused() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(),
                Map.of("query_mode", "local", "fetch_parallelism", "2", "upload_parallelism", "3", "cache", "true"))) {
            assertTrue(db.database().isInMemory());
            assertEquals(2, db.database().fetchParallelism());
            assertEquals(3, db.database().uploadParallelism());
            assertThat(db.database().cache()).isNotNull();
        }
        assertThatThrownBy(() -> RdfDbUtil.open(memoryUrl(), Map.of("nonsense", "1")))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    void anEmptyScenarioIsAnErrorThatSaysWhichScenariosExist() {
        try (RdfDbConnection db = RdfDbUtil.open(memoryUrl(), Map.of())) {
            RdfDbUtil.loadCgmes(db, SCENARIO, microGridBe(), importParameters(), null);
            assertThatThrownBy(() -> RdfDbUtil.load(db, "not-a-day", importParameters(), List.of(), null))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("not-a-day")
                    .hasMessageContaining(SCENARIO);
        }
    }
}
