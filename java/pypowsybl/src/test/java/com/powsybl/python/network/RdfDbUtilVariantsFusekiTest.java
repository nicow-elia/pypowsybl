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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.powsybl.python.network.RdfDbTestSupport.totalLoad;
import static com.powsybl.python.network.RdfDbUtilVariantsTest.loadVariants;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0815;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0830;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.T0845;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.ingest;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.load;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.timestampFiles;
import static com.powsybl.python.network.RdfDbUtilVersionedTest.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The variant bindings of {@link RdfDbUtilVariantsTest}, against a real SPARQL server over HTTP.
 *
 * <p>A bulk load is one multi-side chain query and one statement fetch whatever the number of timestamps, and a
 * per-variant export is a guarded write per variant. Neither can be proved by the in-process backend: the query
 * results and the guards travel through the rdf4j protocol parsers, which are service-loader lookups.</p>
 *
 * <p>Apache Jena is a <strong>test dependency only</strong> and must never reach the native image.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilVariantsFusekiTest extends AbstractFusekiTest {

    private static String scenario(String suffix) {
        return "2014-06-01-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A root plus three steady-state timestamps, ingested from files as a TSO's day arrives. */
    private static void aDay(RdfDbConnection db, String scenario) {
        ingest(db, scenario, RdfDbUtilTest.microGridBe(), 1, null);
        ingest(db, scenario, timestampFiles("f0815", 1.1, T0815, false), 1, T0815);
        ingest(db, scenario, timestampFiles("f0830", 1.2, T0830, false), 1, T0830);
        ingest(db, scenario, timestampFiles("f0845", 1.3, T0845, false), 1, T0845);
    }

    @Test
    void aDayLoadsAsVariantsOverHttp() {
        String scenario = scenario("variants");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            aDay(db, scenario);
            Network day = loadVariants(db, scenario, List.of("", "", ""), List.of(T0815, T0830, T0845));

            assertThat(day.getVariantManager().getVariantIds())
                    .containsExactlyInAnyOrder("InitialState", T0815, T0830, T0845);
            for (String timestamp : List.of(T0815, T0830, T0845)) {
                Network alone = load(db, scenario, 1, timestamp);
                assertEquals(alone.getLoadStream().mapToDouble(load -> load.getP0()).sum(),
                        totalLoad(day, timestamp), 1e-6);
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
            Network day = loadVariants(db, scenario, List.of("", ""), List.of(T0815, T0845));

            NetworkEventRecording recording = new NetworkEventRecording(day);
            recording.start();
            day.getVariantManager().setWorkingVariant(T0815);
            day.getLoads().iterator().next().setP0(11.0);
            day.getVariantManager().setWorkingVariant(T0845);
            day.getLoads().iterator().next().setP0(99.0);
            day.getVariantManager().setWorkingVariant("InitialState");
            recording.stop();

            List<RdfDbUtil.VariantExportRow> rows =
                    RdfDbUtil.exportRecordingPerVariant(recording, db, scenario, 2, Map.of());
            assertThat(rows).hasSize(2).allMatch(row -> !row.models().isEmpty());

            // a second connection, i.e. what another process sees
            try (RdfDbConnection reader = RdfDbUtil.open(datasetUrl(), Map.of())) {
                Network early = load(reader, scenario, 2, T0815);
                Network late = load(reader, scenario, 2, T0845);
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
            Network network = load(db, scenario, 1, null);
            double base = network.getLoadStream().mapToDouble(load -> load.getP0()).sum();

            RdfDbUtil.UpdateOutcome created = update(network, db, scenario, 1, T0830,
                    Map.of(RdfDbUtil.VARIANT, "study"));
            assertEquals("diff", RdfDbUtil.updateInfo(created).get(RdfDbUtil.ROUTE));
            assertEquals("study", RdfDbUtil.updateInfo(created).get(RdfDbUtil.VARIANT));
            assertEquals(base * 1.2, totalLoad(network, "study"), 1e-6);
            assertEquals(base, network.getLoadStream().mapToDouble(load -> load.getP0()).sum(), 1e-6);

            Map<String, String> identity = RdfDbUtil.identity(network, db, scenario, "study");
            assertEquals("2014-06-01T08:30:00Z", identity.get(RdfDbUtil.TIMESTAMP));
        }
    }
}
