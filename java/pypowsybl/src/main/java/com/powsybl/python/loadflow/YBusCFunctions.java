/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.loadflow;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowProvider;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.adm.AdmittanceEquationSystem;
import com.powsybl.openloadflow.adm.AdmittanceMatrix;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.Directives;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import com.powsybl.python.commons.Util;
import com.powsybl.python.commons.Util.PointerProvider;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CDoublePointer;
import org.graalvm.nativeimage.c.type.CIntPointer;

import java.util.Arrays;
import java.util.List;

/**
 * C functions related to bus-branch matrix interfaces.
 */
@CContext(Directives.class)
public final class YBusCFunctions {

    private YBusCFunctions() {
    }

    @CEntryPoint(name = "getYbusMatrix")
    public static PyPowsyblApiHeader.MatrixPointer getYbusMatrix(IsolateThread thread, ObjectHandle networkHandle,
                                                                  PyPowsyblApiHeader.LoadFlowParametersPointer loadFlowParametersPtr,
                                                                  CCharPointer provider,
                                                                  PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.MatrixPointer get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(provider);
                LoadFlowProvider loadFlowProvider = LoadFlowCUtils.getLoadFlowProvider(providerName);
                LoadFlowParameters parameters = LoadFlowCUtils.createLoadFlowParameters(loadFlowParametersPtr, loadFlowProvider);
                return denseToMatrixPointer(computeYbusSparse(network, parameters, loadFlowProvider.getName()).toDense());
            }
        });
    }

    @CEntryPoint(name = "getYbusCsrMatrix")
    public static PyPowsyblApiHeader.CsrMatrixPointer getYbusCsrMatrix(IsolateThread thread, ObjectHandle networkHandle,
                                                                        PyPowsyblApiHeader.LoadFlowParametersPointer loadFlowParametersPtr,
                                                                        CCharPointer provider,
                                                                        PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.CsrMatrixPointer get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(provider);
                LoadFlowProvider loadFlowProvider = LoadFlowCUtils.getLoadFlowProvider(providerName);
                LoadFlowParameters parameters = LoadFlowCUtils.createLoadFlowParameters(loadFlowParametersPtr, loadFlowProvider);
                CsrArrays csr = computeYbusCsr(network, parameters, loadFlowProvider.getName());

                CDoublePointer dataPtr = UnmanagedMemory.calloc(csr.data.length * SizeOf.get(CDoublePointer.class));
                for (int i = 0; i < csr.data.length; i++) {
                    dataPtr.addressOf(i).write(csr.data[i]);
                }

                CIntPointer indicesPtr = UnmanagedMemory.calloc(csr.indices.length * SizeOf.get(CIntPointer.class));
                for (int i = 0; i < csr.indices.length; i++) {
                    indicesPtr.addressOf(i).write(csr.indices[i]);
                }

                CIntPointer indptrPtr = UnmanagedMemory.calloc(csr.indptr.length * SizeOf.get(CIntPointer.class));
                for (int i = 0; i < csr.indptr.length; i++) {
                    indptrPtr.addressOf(i).write(csr.indptr[i]);
                }

                PyPowsyblApiHeader.CsrMatrixPointer csrPtr = UnmanagedMemory.calloc(SizeOf.get(PyPowsyblApiHeader.CsrMatrixPointer.class));
                csrPtr.setRowCount(csr.rowCount);
                csrPtr.setColumnCount(csr.columnCount);
                csrPtr.setNnz(csr.data.length);
                csrPtr.setData(dataPtr);
                csrPtr.setIndices(indicesPtr);
                csrPtr.setIndptr(indptrPtr);
                return csrPtr;
            }
        });
    }

    @CEntryPoint(name = "getYbusRowLabels")
    public static PyPowsyblApiHeader.ArrayPointer<CCharPointerPointer> getYbusRowLabels(IsolateThread thread,
                                                                                          ObjectHandle networkHandle,
                                                                                          PyPowsyblApiHeader.LoadFlowParametersPointer loadFlowParametersPtr,
                                                                                          CCharPointer provider,
                                                                                          PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.ArrayPointer<CCharPointerPointer> get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(provider);
                LoadFlowProvider loadFlowProvider = LoadFlowCUtils.getLoadFlowProvider(providerName);
                LoadFlowParameters parameters = LoadFlowCUtils.createLoadFlowParameters(loadFlowParametersPtr, loadFlowProvider);
                return Util.createCharPtrArray(computeYbusRowLabels(network, parameters, loadFlowProvider.getName()));
            }
        });
    }

    @CEntryPoint(name = "getYbusColumnLabels")
    public static PyPowsyblApiHeader.ArrayPointer<CCharPointerPointer> getYbusColumnLabels(IsolateThread thread,
                                                                                             ObjectHandle networkHandle,
                                                                                             PyPowsyblApiHeader.LoadFlowParametersPointer loadFlowParametersPtr,
                                                                                             CCharPointer provider,
                                                                                             PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.ArrayPointer<CCharPointerPointer> get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(provider);
                LoadFlowProvider loadFlowProvider = LoadFlowCUtils.getLoadFlowProvider(providerName);
                LoadFlowParameters parameters = LoadFlowCUtils.createLoadFlowParameters(loadFlowParametersPtr, loadFlowProvider);
                return Util.createCharPtrArray(computeYbusColumnLabels(network, parameters, loadFlowProvider.getName()));
            }
        });
    }

    @CEntryPoint(name = "freeYbusCsrMatrix")
    public static void freeYbusCsrMatrix(IsolateThread thread, PyPowsyblApiHeader.CsrMatrixPointer csrMatrixPtr,
                                         PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        Util.doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                if (csrMatrixPtr.isNull()) {
                    return;
                }
                if (csrMatrixPtr.getData().isNonNull()) {
                    UnmanagedMemory.free(csrMatrixPtr.getData());
                }
                if (csrMatrixPtr.getIndices().isNonNull()) {
                    UnmanagedMemory.free(csrMatrixPtr.getIndices());
                }
                if (csrMatrixPtr.getIndptr().isNonNull()) {
                    UnmanagedMemory.free(csrMatrixPtr.getIndptr());
                }
                UnmanagedMemory.free(csrMatrixPtr);
            }
        });
    }

    static CsrArrays computeYbusCsr(Network network, LoadFlowParameters parameters, String providerName) {
        return toCsr(computeYbusSparse(network, parameters, providerName));
    }

    static List<String> computeYbusRowLabels(Network network, LoadFlowParameters parameters, String providerName) {
        return computeYbusEquationSystem(network, parameters, providerName).getEquationSystem().getRowNames();
    }

    static List<String> computeYbusColumnLabels(Network network, LoadFlowParameters parameters, String providerName) {
        return computeYbusEquationSystem(network, parameters, providerName).getEquationSystem().getColumnNames();
    }

    static SparseMatrix computeYbusSparse(Network network, LoadFlowParameters parameters, String providerName) {
        AdmittanceEquationSystem equationSystem = computeYbusEquationSystem(network, parameters, providerName);
        SparseMatrixFactory matrixFactory = new SparseMatrixFactory();

        try (AdmittanceMatrix admittanceMatrix = AdmittanceMatrix.create(equationSystem, matrixFactory)) {
            return admittanceMatrix.getMatrix().toSparse();
        }
    }

    private static AdmittanceEquationSystem computeYbusEquationSystem(Network network, LoadFlowParameters parameters,
                                                                      String providerName) {
        if (!"OpenLoadFlow".equals(providerName)) {
            throw new PowsyblException("Ybus extraction is only supported with OpenLoadFlow provider");
        }

        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(parameters);
        SparseMatrixFactory matrixFactory = new SparseMatrixFactory();

        List<LfNetwork> lfNetworks = LfNetwork.load(
                network,
                new LfNetworkLoaderImpl(),
                new LfTopoConfig(),
                OpenLoadFlowParameters.createAcParameters(
                        network,
                        parameters,
                        openLoadFlowParameters,
                        matrixFactory,
                        new EvenShiloachGraphDecrementalConnectivityFactory<>()
                ).getNetworkParameters(),
                ReportNode.NO_OP);

        if (lfNetworks.isEmpty()) {
            throw new PowsyblException("No load-flow network available to compute Ybus");
        }
        if (lfNetworks.size() > 1) {
            throw new PowsyblException("Ybus extraction currently supports a single connected component only");
        }

        return AdmittanceEquationSystem.create(lfNetworks.get(0), new VariableSet<>());
    }

    private static PyPowsyblApiHeader.MatrixPointer denseToMatrixPointer(DenseMatrix denseMatrix) {
        int rowCount = denseMatrix.getRowCount();
        int columnCount = denseMatrix.getColumnCount();
        CDoublePointer valuePtr = UnmanagedMemory.calloc(rowCount * columnCount * SizeOf.get(CDoublePointer.class));
        int k = 0;
        for (int i = 0; i < rowCount; i++) {
            for (int j = 0; j < columnCount; j++) {
                valuePtr.addressOf(k).write(denseMatrix.get(i, j));
                k++;
            }
        }
        PyPowsyblApiHeader.MatrixPointer matrixPtr = UnmanagedMemory.calloc(SizeOf.get(PyPowsyblApiHeader.MatrixPointer.class));
        matrixPtr.setRowCount(rowCount);
        matrixPtr.setColumnCount(columnCount);
        matrixPtr.setValues(valuePtr);
        return matrixPtr;
    }

    private static CsrArrays toCsr(SparseMatrix sparseMatrix) {
        int rowCount = sparseMatrix.getRowCount();
        int columnCount = sparseMatrix.getColumnCount();
        int[] columnStart = sparseMatrix.getColumnStart();
        int[] rowIndices = sparseMatrix.getRowIndices();
        double[] values = sparseMatrix.getValues();
        int nnz = values.length;

        int[] indptr = new int[rowCount + 1];
        for (int row : rowIndices) {
            indptr[row + 1]++;
        }
        for (int i = 1; i < indptr.length; i++) {
            indptr[i] += indptr[i - 1];
        }

        int[] indices = new int[nnz];
        double[] data = new double[nnz];
        int[] next = Arrays.copyOf(indptr, indptr.length);

        for (int col = 0; col < columnCount; col++) {
            for (int k = columnStart[col]; k < columnStart[col + 1]; k++) {
                int row = rowIndices[k];
                int pos = next[row]++;
                indices[pos] = col;
                data[pos] = values[k];
            }
        }

        return new CsrArrays(rowCount, columnCount, data, indices, indptr);
    }

    static final class CsrArrays {
        private final int rowCount;
        private final int columnCount;
        private final double[] data;
        private final int[] indices;
        private final int[] indptr;

        private CsrArrays(int rowCount, int columnCount, double[] data, int[] indices, int[] indptr) {
            this.rowCount = rowCount;
            this.columnCount = columnCount;
            this.data = data;
            this.indices = indices;
            this.indptr = indptr;
        }

        int rowCount() {
            return rowCount;
        }

        int columnCount() {
            return columnCount;
        }

        double[] data() {
            return data;
        }

        int[] indices() {
            return indices;
        }

        int[] indptr() {
            return indptr;
        }
    }
}
