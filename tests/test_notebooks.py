#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
A guard that the notebooks of ``examples/notebooks`` still run.

It is slow by the standards of this suite - a kernel per notebook, a grid model loaded several times - so it is
written to be easy to leave out (``pytest tests/ --deselect tests/test_notebooks.py``, or simply ``-k "not
notebook"``), and it skips itself when ``nbconvert`` is not installed. The assertions live in the other test
modules; what this one checks is that the narrative in the notebooks is still true of the code.
"""
import os
import subprocess
import sys
from pathlib import Path

import pytest

NOTEBOOKS = Path(__file__).parent.parent / 'examples' / 'notebooks'
CHECKOUT = Path(__file__).parent.parent


def _names():
    return sorted(path.name for path in NOTEBOOKS.glob('0*.ipynb'))


@pytest.mark.parametrize('name', _names())
def test_notebook_runs_on_the_in_process_backend(name: str, tmp_path: Path) -> None:
    pytest.importorskip('nbconvert', reason='nbconvert is not installed')
    pytest.importorskip('ipykernel', reason='ipykernel is not installed')

    environment = dict(os.environ)
    environment['PYPOWSYBL_RDF_DB'] = f'memory:{name}'
    environment['PYTHONPATH'] = os.pathsep.join(filter(None, [str(CHECKOUT), environment.get('PYTHONPATH')]))
    completed = subprocess.run(
        [sys.executable, '-m', 'nbconvert', '--to', 'notebook', '--execute',
         '--ExecutePreprocessor.timeout=900', '--ExecutePreprocessor.kernel_name=python3',
         '--output-dir', str(tmp_path), str(NOTEBOOKS / name)],
        cwd=str(NOTEBOOKS), env=environment, capture_output=True, text=True, check=False)
    assert completed.returncode == 0, completed.stderr[-4000:]
    assert (tmp_path / name).exists()
