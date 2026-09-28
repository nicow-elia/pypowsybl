/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.DataframeMapper;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.iidm.network.Network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers the RDF database tests share.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class RdfDbTestSupport {

    private RdfDbTestSupport() {
    }

    /** The sum of the active power setpoints of the loads of one variant; the working variant is left as it was. */
    static double totalLoad(Network network, String variant) {
        String previous = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variant);
        try {
            return network.getLoadStream().mapToDouble(load -> load.getP0()).sum();
        } finally {
            network.getVariantManager().setWorkingVariant(previous);
        }
    }

    /** The column names, index included, of the dataframe a mapper makes of some rows. */
    static <T> List<String> columns(DataframeMapper<T, Void> mapper, T rows) {
        List<Series> series = new ArrayList<>();
        mapper.createDataframe(rows, new DefaultDataframeHandler(series::add), new DataframeFilter());
        Map<String, Series> byName = new LinkedHashMap<>();
        series.forEach(s -> byName.put(s.getName(), s));
        return List.copyOf(byName.keySet());
    }
}
