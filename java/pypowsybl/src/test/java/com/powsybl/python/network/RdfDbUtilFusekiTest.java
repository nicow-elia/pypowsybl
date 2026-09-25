/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.cgmes.rdfdb.RdfDbException;
import com.powsybl.iidm.network.Network;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same bindings as {@link RdfDbUtilTest}, but against a real SPARQL server over HTTP.
 *
 * <p>What the in-process backend cannot show is whether the HTTP path works: the Graph Store Protocol upload, the
 * SPARQL protocol dataset parameters of the remote query mode, and the parsers and writers the rdf4j client picks
 * through its service loaders. That last point is why this class is also the target of the native-image tracing
 * agent (see the report of this step): every reflective lookup the client does happens here.</p>
 *
 * <p>Apache Jena is a <strong>test dependency only</strong>. It must never reach the native image, which is also
 * why the Python tests start a Fuseki <em>subprocess</em> rather than binding one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUtilFusekiTest {

    private static final String SCENARIO = "2021-02-09";

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

    @ParameterizedTest(name = "query_mode={0}")
    @ValueSource(strings = {"local", "remote"})
    void theNetworkFromTheServerIsTheNetworkFromTheFiles(String queryMode) {
        Network fromFiles = Network.read(RdfDbUtilTest.microGridBe(), RdfDbUtilTest.importProperties());
        String scenario = SCENARIO + "-" + queryMode;
        try (RdfDbConnection db = RdfDbUtil.open(datasetUrl(), Map.of("query_mode", queryMode))) {
            List<String> graphs = RdfDbUtil.loadCgmes(db, scenario, RdfDbUtilTest.microGridBe(),
                    RdfDbUtilTest.importParameters(), null);
            assertThat(graphs).hasSizeGreaterThanOrEqualTo(4);
            assertThat(RdfDbUtil.scenarios(db)).contains(scenario);

            Network fromDb = RdfDbUtil.load(db, scenario, RdfDbUtilTest.importParameters(), List.of(), null);
            assertEquals(RdfDbUtilTest.summary(fromFiles), RdfDbUtilTest.summary(fromDb));

            assertThat(RdfDbUtil.graphs(db, scenario))
                    .allSatisfy(g -> assertThat(g.remoteGraph()).startsWith("contexts:" + scenario + "/"));

            RdfDbUtil.clear(db, scenario);
            assertThat(RdfDbUtil.graphs(db, scenario)).isEmpty();
        }
    }

    @Test
    void anUnreachableEndpointIsRefusedWithItsUrlInTheMessage() {
        assertThatThrownBy(() -> RdfDbUtil.open("http://127.0.0.1:1/ds",
                Map.of("connect_timeout_ms", "500", "read_timeout_ms", "500")))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("127.0.0.1:1");
    }
}
