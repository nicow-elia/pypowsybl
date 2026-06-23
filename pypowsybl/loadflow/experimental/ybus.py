# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0

from typing import List, Optional, Tuple

import numpy as np

from pypowsybl import _pypowsybl
from pypowsybl.network import Network
from pypowsybl.loadflow.impl.parameters import Parameters


def get_ybus_matrix(network: Network, parameters: Optional[Parameters] = None,
                    provider: str = '') -> _pypowsybl.Matrix:
    """
    Get the Ybus matrix as a 2D matrix buffer.

    This experimental interface is currently only supported with OpenLoadFlow.

    Args:
        network: a network
        parameters: the load flow parameters
        provider: the load flow implementation provider, default is the default load flow provider

    Returns:
        A matrix-like object exposing Python buffer protocol.
    """
    p = parameters._to_c_parameters() if parameters is not None else _pypowsybl.LoadFlowParameters()
    p.dc = False
    return _pypowsybl.get_ybus_matrix(network._handle, p, provider)


def get_ybus_csr(network: Network, parameters: Optional[Parameters] = None, provider: str = '',
                 zero_threshold: float = 0.0) -> Tuple[np.ndarray, np.ndarray, np.ndarray, Tuple[int, int]]:
    """
    Get the Ybus matrix in CSR representation.

    Args:
        network: a network
        parameters: the load flow parameters
        provider: the load flow implementation provider, default is the default load flow provider
        zero_threshold: values with ``abs(value) <= zero_threshold`` are treated as zeros

    Returns:
        A tuple ``(data, indices, indptr, shape)`` representing a CSR matrix.
    """
    p = parameters._to_c_parameters() if parameters is not None else _pypowsybl.LoadFlowParameters()
    p.dc = False

    if hasattr(_pypowsybl, 'get_ybus_csr_matrix'):
        data, indices, indptr, shape = _pypowsybl.get_ybus_csr_matrix(network._handle, p, provider)
        data = np.asarray(data, dtype=np.float64)
        indices = np.asarray(indices, dtype=np.int64)
        indptr = np.asarray(indptr, dtype=np.int64)
        if zero_threshold > 0.0:
            # Rebuild CSR after thresholding to keep structural consistency.
            rows, cols = shape
            dense = np.zeros((rows, cols), dtype=np.float64)
            for row in range(rows):
                start = indptr[row]
                end = indptr[row + 1]
                row_indices = indices[start:end]
                row_data = data[start:end]
                for i, col in enumerate(row_indices):
                    dense[row, col] += row_data[i]
            return _dense_to_csr(dense, zero_threshold)
        return data, indices, indptr, shape

    dense = np.asarray(get_ybus_matrix(network, parameters, provider), dtype=np.float64)
    return _dense_to_csr(dense, zero_threshold)


def get_ybus_labels(network: Network, parameters: Optional[Parameters] = None,
                    provider: str = '') -> Tuple[List[str], List[str]]:
    """
    Get row and column labels for the Ybus matrix.

    The returned labels follow the exact ordering used by :func:`get_ybus_matrix`
    and :func:`get_ybus_csr`.

    Args:
        network: a network
        parameters: the load flow parameters
        provider: the load flow implementation provider, default is the default load flow provider

    Returns:
        A tuple ``(row_labels, column_labels)``.
    """
    p = parameters._to_c_parameters() if parameters is not None else _pypowsybl.LoadFlowParameters()
    p.dc = False
    row_labels, column_labels = _pypowsybl.get_ybus_labels(network._handle, p, provider)
    return list(row_labels), list(column_labels)


def _dense_to_csr(dense: np.ndarray, zero_threshold: float) -> Tuple[np.ndarray, np.ndarray, np.ndarray, Tuple[int, int]]:
    rows, cols = dense.shape

    data_chunks = []
    index_chunks = []
    indptr = np.zeros(rows + 1, dtype=np.int64)

    for row in range(rows):
        row_values = dense[row]
        if zero_threshold > 0.0:
            nz_cols = np.flatnonzero(np.abs(row_values) > zero_threshold)
        else:
            nz_cols = np.flatnonzero(row_values)
        index_chunks.append(nz_cols.astype(np.int64, copy=False))
        data_chunks.append(row_values[nz_cols])
        indptr[row + 1] = indptr[row] + nz_cols.shape[0]

    if index_chunks:
        indices = np.concatenate(index_chunks)
        data = np.concatenate(data_chunks)
    else:
        indices = np.array([], dtype=np.int64)
        data = np.array([], dtype=np.float64)

    return data, indices, indptr, (rows, cols)