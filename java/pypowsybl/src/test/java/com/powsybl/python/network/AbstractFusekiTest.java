/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * An embedded Apache Jena Fuseki server with one in-memory dataset, started once per test class.
 *
 * <p>Apache Jena is a <strong>test dependency only</strong> and must never reach the native image, which is also why
 * the Python tests start a Fuseki <em>subprocess</em> rather than binding one. The tests of a class share the server
 * and keep out of each other's way by using a scenario name of their own.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
abstract class AbstractFusekiTest {

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

    /** The SPARQL endpoint URL of the dataset. */
    static String datasetUrl() {
        return "http://localhost:" + server.getPort() + "/ds";
    }
}
