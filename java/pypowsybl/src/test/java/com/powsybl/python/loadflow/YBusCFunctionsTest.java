/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.loadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.SparseMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YBusCFunctionsTest {

    @Test
    void testComputeYbusSparseOpenLoadFlow() {
        Network network = IeeeCdfNetworkFactory.create9();

        SparseMatrix ybusSparse = YBusCFunctions.computeYbusSparse(network, new LoadFlowParameters(), "OpenLoadFlow");

        assertThat(ybusSparse.getRowCount()).isGreaterThan(0);
        assertThat(ybusSparse.getColumnCount()).isEqualTo(ybusSparse.getRowCount());
        assertThat(ybusSparse.getValueCount()).isGreaterThan(0);
    }

    @Test
    void testComputeYbusCsrMatchesDense() {
        Network network = IeeeCdfNetworkFactory.create9();

        SparseMatrix ybusSparse = YBusCFunctions.computeYbusSparse(network, new LoadFlowParameters(), "OpenLoadFlow");
        DenseMatrix dense = ybusSparse.toDense();
        YBusCFunctions.CsrArrays csr = YBusCFunctions.computeYbusCsr(network, new LoadFlowParameters(), "OpenLoadFlow");

        assertThat(csr.indptr()).hasSize(csr.rowCount() + 1);
        assertThat(csr.indptr()[0]).isEqualTo(0);
        assertThat(csr.indptr()[csr.indptr().length - 1]).isEqualTo(csr.data().length);
        assertThat(csr.indices()).hasSize(csr.data().length);
        assertThat(csr.columnCount()).isEqualTo(csr.rowCount());

        double[][] reconstructed = new double[csr.rowCount()][csr.columnCount()];
        for (int row = 0; row < csr.rowCount(); row++) {
            int start = csr.indptr()[row];
            int end = csr.indptr()[row + 1];
            for (int i = start; i < end; i++) {
                reconstructed[row][csr.indices()[i]] = csr.data()[i];
            }
        }

        for (int row = 0; row < csr.rowCount(); row++) {
            for (int col = 0; col < csr.columnCount(); col++) {
                assertThat(reconstructed[row][col]).isEqualTo(dense.get(row, col));
            }
        }
    }

    @Test
    void testComputeYbusUnsupportedProvider() {
        Network network = IeeeCdfNetworkFactory.create9();

        assertThatThrownBy(() -> YBusCFunctions.computeYbusSparse(network, new LoadFlowParameters(), "DynaFlow"))
                .hasMessageContaining("Ybus extraction is only supported with OpenLoadFlow provider");
    }

    @Test
    void testComputeYbusLabels() {
        Network network = IeeeCdfNetworkFactory.create9();

        List<String> rowLabels = YBusCFunctions.computeYbusRowLabels(network, new LoadFlowParameters(), "OpenLoadFlow");
        List<String> columnLabels = YBusCFunctions.computeYbusColumnLabels(network, new LoadFlowParameters(), "OpenLoadFlow");

        assertThat(rowLabels).isNotEmpty();
        assertThat(columnLabels).isNotEmpty();
        assertThat(rowLabels).hasSameSizeAs(columnLabels);
        assertThat(rowLabels).allMatch(label -> label != null && !label.isBlank());
        assertThat(columnLabels).allMatch(label -> label != null && !label.isBlank());
    }
}
