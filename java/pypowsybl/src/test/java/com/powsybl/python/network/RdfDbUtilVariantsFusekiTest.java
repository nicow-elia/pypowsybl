/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.iidm.network.Network;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.powsybl.python.network.RdfDbUtilTest.importParameters;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.timestepFiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The variant bindings of {@link RdfDbUtilVariantsTest}, against a real SPARQL server over HTTP.
 *
 * <p>A bulk load is one multi-side chain query and one statement fetch whatever the number of timesteps, and a
 * per-variant export is a guarded write per variant. Neither can be proved by the in-process backend: the query
 * results and the guards travel through the rdf4j protocol parsers, which are service-loader lookups.</p>
 *
 * <p>Apache Jena is a <strong>test dependency only</strong> and must never reach the native image.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilVariantsFusekiTest {

    private static FusekiServer server;

    @BeforeAll
    static void startServer() {
        server = FusekiServer.create()
                .port(0)
                .verbose(false)
                .enablePing(true)
                .add("/ds", DatasetGraphFactory.createTxnMem(), true)
                .build()
                .start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private static String datasetUrl() {
        return "http://localhost:" + server.getPort() + "/ds";
    }

    private static String scenario(String suffix) {
        return "2014-06-01-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A root plus three steady-state timesteps, ingested from files as a TSO's day arrives. */
    private static void aDay(RdfDbConnection db, String scenario) {
        RdfDbUtil.loadCgmes(db, RdfDbUtilTest.microGridBe(), scenario, "1.0", null, importParameters(), null);
        RdfDbUtil.loadCgmes(db, timestepFiles("f0815", 1.1, "2014-06-01T08:15:00Z", false), scenario, "1.1",
                "8:15", importParameters(), null);
        RdfDbUtil.loadCgmes(db, timestepFiles("f0830", 1.2, "2014-06-01T08:30:00Z", false), scenario, "1.1",
                "8:30", importParameters(), null);
        RdfDbUtil.loadCgmes(db, timestepFiles("f0845", 1.3, "2014-06-01T08:45:00Z", false), scenario, "1.1",
                "8:45", importParameters(), null);
    }

    private static double totalLoad(Network network, String variant) {
        String previous = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variant);
        try {
            return network.getLoadStream().mapToDouble(load -> load.getP0()).sum();
        } finally {
            network.getVariantManager().setWorkingVariant(previous);
        }
    }

    @Test
    void aDayLoadsAsVariantsOverHttp() {
        String scenario = scenario("variants");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            aDay(db, scenario);
            Network day = RdfDbUtil.loadVariants(db, scenario, List.of("", "", ""), List.of("", "", ""),
                    List.of("8:15", "8:30", "8:45"), importParameters(), null, false);

            assertThat(day.getVariantManager().getVariantIds())
                    .containsExactlyInAnyOrder("InitialState", "08:15", "08:30", "08:45");
            for (String label : List.of("8:15", "8:30", "8:45")) {
                Network alone = RdfDbUtil.load(db, scenario, "1.1", label, importParameters(), List.of(), null);
                assertEquals(alone.getLoadStream().mapToDouble(load -> load.getP0()).sum(),
                        totalLoad(day, "0" + label), 1e-6);
            }
            assertThat(RdfDbUtil.variantRows(day))
                    .filteredOn(row -> "bound".equals(row.status()))
                    .hasSize(3)
                    .allMatch(row -> scenario.equals(row.scenario()) && !row.snapshot().isEmpty());
        }
    }

    @Test
    void twoVariantsAreWrittenAsTwoSuccessorsOverHttp() {
        String scenario = scenario("export");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            aDay(db, scenario);
            Network day = RdfDbUtil.loadVariants(db, scenario, List.of("", ""), List.of("", ""),
                    List.of("8:15", "8:45"), importParameters(), null, false);

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getVariantManager().setWorkingVariant("08:15");
            day.getLoads().iterator().next().setP0(11.0);
            day.getVariantManager().setWorkingVariant("08:45");
            day.getLoads().iterator().next().setP0(99.0);
            day.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();

            List<RdfDbUtil.VariantExportRow> rows =
                    RdfDbUtil.exportRecordingPerVariant(recording, db, scenario, "2.0", Map.of());
            assertThat(rows).hasSize(2).allMatch(row -> !row.models().isEmpty());

            // a second connection, i.e. what another process sees
            try (RdfDbConnection reader = RdfDbUtil.open(datasetUrl(), Map.of())) {
                Network early = RdfDbUtil.load(reader, scenario, "2.0", "8:15", importParameters(), List.of(),
                        null);
                Network late = RdfDbUtil.load(reader, scenario, "2.0", "8:45", importParameters(), List.of(),
                        null);
                assertEquals(11.0, early.getLoads().iterator().next().getP0(), 1e-9);
                assertEquals(99.0, late.getLoads().iterator().next().getP0(), 1e-9);
            }
        }
    }

    @Test
    void oneVariantIsCreatedAndMovedOverHttp() {
        String scenario = scenario("update");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            aDay(db, scenario);
            Network network = RdfDbUtil.load(db, scenario, "1.0", null, importParameters(), List.of(), null);
            double base = network.getLoadStream().mapToDouble(load -> load.getP0()).sum();

            RdfDbUtil.UpdateOutcome created = RdfDbUtil.update(network, db, scenario, "1.1", "8:30", List.of(),
                    Map.of(RdfDbUtil.VARIANT, "study"), importParameters(), null);
            assertEquals("diff", RdfDbUtil.updateInfo(created).get(RdfDbUtil.ROUTE));
            assertEquals("study", RdfDbUtil.updateInfo(created).get(RdfDbUtil.VARIANT));
            assertEquals(base * 1.2, totalLoad(network, "study"), 1e-6);
            assertEquals(base, network.getLoadStream().mapToDouble(load -> load.getP0()).sum(), 1e-6);

            Map<String, String> identity = RdfDbUtil.identity(network, db, scenario, "study");
            assertEquals("2014-06-01T08:30:00Z", identity.get(RdfDbUtil.TIMESTEP));
        }
    }
}
