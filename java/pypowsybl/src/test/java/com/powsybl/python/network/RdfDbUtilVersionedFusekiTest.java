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
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The versioned bindings of {@link RdfDbUtilVersionedTest}, against a real SPARQL server over HTTP.
 *
 * <p>What the in-process backend cannot show is whether the <em>guarded</em> writes of the versioning layer survive
 * the HTTP path: a snapshot is written by one SPARQL UPDATE whose guard enforces the linear chain, and a planning
 * step is one SPARQL SELECT whose results come back through the rdf4j tuple-result parsers. Both are service-loader
 * lookups, which is what makes this class the target of the native-image tracing agent as well.</p>
 *
 * <p>Apache Jena is a <strong>test dependency only</strong> and must never reach the native image.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilVersionedFusekiTest {

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

    /** A scenario name of its own per test, so that the tests of this class share one server without sharing data. */
    private static String scenario(String suffix) {
        return "2016-01-01-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Network root(RdfDbConnection db, String scenario) {
        assertThat(RdfDbUtil.loadCgmes(db, RdfDbUtilTest.microGridBe(), scenario, "1.0", null,
                RdfDbUtilTest.importParameters(), null)).isNotEmpty();
        return RdfDbUtil.load(db, scenario, "1.0", null, RdfDbUtilTest.importParameters(), List.of(), null);
    }

    private static List<String> record(RdfDbConnection db, Network network, String scenario, String version,
                                       String timestep, double value) {
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = network.getLoads().iterator().next();
        load.setP0(value);
        recording.stop();
        return RdfDbUtil.exportRecording(recording, db, scenario, version, timestep, Map.of());
    }

    @Test
    void aWholeRoundTripOverHttp() {
        String scenario = scenario("round");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            Network sender = root(db, scenario);
            assertTrue(RdfDbUtil.isVersioned(db, scenario));
            assertThat(record(db, sender, scenario, "1.1", "8:30", 47.0)).isNotEmpty();

            List<SnapshotInfo> snapshots = RdfDbUtil.snapshots(db, scenario);
            assertEquals(2, snapshots.size());
            assertThat(snapshots).anyMatch(info -> info.kind() == SnapshotInfo.Kind.DIFF && info.fast());
            assertEquals(2, RdfDbUtil.timesteps(db, scenario).size());

            Network receiver = RdfDbUtil.load(db, scenario, "1.0", null, RdfDbUtilTest.importParameters(),
                    List.of(), null);
            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(receiver, db, scenario, "1.1", "8:30", List.of(),
                    Map.of(), RdfDbUtilTest.importParameters(), null);
            assertEquals("diff", RdfDbUtil.updateInfo(outcome).get(RdfDbUtil.ROUTE));
            assertEquals(47.0, receiver.getLoads().iterator().next().getP0(), 1e-9);

            // And the snapshot really is addressable on its own, not only reachable by walking
            Network direct = RdfDbUtil.load(db, scenario, "1.1", "8:30", RdfDbUtilTest.importParameters(),
                    List.of(), null);
            assertEquals(47.0, direct.getLoads().iterator().next().getP0(), 1e-9);
        }
    }

    @Test
    void theLinearChainGuardHoldsOverHttp() {
        String scenario = scenario("guard");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            Network first = root(db, scenario);
            Network second = RdfDbUtil.load(db, scenario, "1.0", null, RdfDbUtilTest.importParameters(),
                    List.of(), null);
            record(db, first, scenario, "1.1", null, 48.0);

            // The second writer is still at 1.0; the guard of the write refuses it rather than forking the chain
            assertThatThrownBy(() -> record(db, second, scenario, "1.2", null, 49.0))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageMatching("(?s).*(successor|re-record).*");
        }
    }

    @Test
    void aCheckpointAndACrossScenarioReloadOverHttp() {
        String scenario = scenario("cross");
        String other = scenario("other");
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of())) {
            RdfDbUtil.loadCgmes(db, CgmesConformity1Catalog.miniBusBranch().dataSource(), other, "1.0", null,
                    RdfDbUtilTest.importParameters(), null);
            Network network = root(db, scenario);
            record(db, network, scenario, "1.1", "8:30", 50.0);

            String iri = RdfDbUtil.checkpoint(db, scenario, "1.1", "8:30");
            assertThat(RdfDbUtil.snapshots(db, scenario))
                    .filteredOn(info -> info.iri().equals(iri))
                    .allMatch(SnapshotInfo::hasFull);

            RdfDbUtil.UpdateOutcome outcome = RdfDbUtil.update(network, db, other, "1.0", null, List.of(),
                    Map.of(), RdfDbUtilTest.importParameters(), null);
            Map<String, String> info = RdfDbUtil.updateInfo(outcome);
            assertEquals("full", info.get(RdfDbUtil.ROUTE));
            assertEquals(other, info.get(RdfDbUtil.SCENARIO));
            assertThat(RdfDbUtil.replacement(outcome)).isNotSameAs(network);
        }
    }
}
