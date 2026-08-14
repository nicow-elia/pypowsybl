#
# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

import numpy as np
import pytest

import pypowsybl as pp
from pypowsybl.loadflow.experimental import ybus as ybus_exp


@pytest.fixture(autouse=True)
def set_up():
    pp.set_config_read(False)


def test_get_ybus_csr():
    if not hasattr(pp._pypowsybl, 'get_ybus_matrix'):
        pytest.skip('get_ybus_matrix not available in current native extension build')

    n = pp.network.create_ieee14()
    data, indices, indptr, shape = ybus_exp.get_ybus_csr(n, provider='OpenLoadFlow', zero_threshold=1e-12)

    assert shape[0] == shape[1]
    assert shape[0] > 0
    assert indptr.shape[0] == shape[0] + 1
    assert indptr[0] == 0
    assert indptr[-1] == data.shape[0]
    assert data.shape[0] == indices.shape[0]

    dense_from_csr = np.zeros(shape, dtype=np.float64)
    for row in range(shape[0]):
        start = indptr[row]
        end = indptr[row + 1]
        dense_from_csr[row, indices[start:end]] = data[start:end]

    dense_from_api = np.asarray(ybus_exp.get_ybus_matrix(n, provider='OpenLoadFlow'))
    np.testing.assert_allclose(dense_from_csr, dense_from_api, rtol=0.0, atol=1e-9)


def test_get_ybus_labels():
    if not hasattr(pp._pypowsybl, 'get_ybus_labels'):
        pytest.skip('get_ybus_labels not available in current native extension build')

    n = pp.network.create_ieee14()
    row_labels, column_labels = ybus_exp.get_ybus_labels(n, provider='OpenLoadFlow')
    dense = np.asarray(ybus_exp.get_ybus_matrix(n, provider='OpenLoadFlow'))

    assert len(row_labels) == dense.shape[0]
    assert len(column_labels) == dense.shape[1]
    assert all(isinstance(label, str) and label for label in row_labels)
    assert all(isinstance(label, str) and label for label in column_labels)
