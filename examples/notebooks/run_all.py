#!/usr/bin/env python3
#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Execute every notebook of this folder with nbconvert, and fail on the first cell that raises.

This is what proves the notebooks still run after a change to pypowsybl; it is not a substitute for the test
suite, which is where the assertions live.

    python examples/notebooks/run_all.py                      # in-process store
    python examples/notebooks/run_all.py --fuseki             # against a Fuseki subprocess
    PYPOWSYBL_RDF_DB=http://localhost:3030/ds python examples/notebooks/run_all.py

The executed copies are written to ``--output-dir`` (a temporary directory by default) and the committed notebooks
are left untouched, outputs and all.
"""
import argparse
import os
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Optional

HERE = Path(__file__).resolve().parent
CHECKOUT = HERE.parents[1]


@contextmanager
def fuseki() -> Iterator[str]:
    """Start the Fuseki subprocess of the test suite for the length of the run."""
    sys.path.insert(0, str(CHECKOUT / 'tests'))
    import fuseki_server  # pylint: disable=import-outside-toplevel

    reason = fuseki_server.unavailable_reason()
    if reason is not None:
        raise SystemExit(f'--fuseki asked for, but no server can be started: {reason}')
    server = fuseki_server.start()
    try:
        print(f'Fuseki at {server.dataset_url}')
        yield server.dataset_url
    finally:
        server.stop()


def execute(notebook: Path, output_dir: Path, timeout: int) -> int:
    """Run one notebook; returns the exit code of nbconvert."""
    print(f'--- {notebook.name}')
    return subprocess.call([sys.executable, '-m', 'nbconvert', '--to', 'notebook', '--execute',
                            f'--ExecutePreprocessor.timeout={timeout}',
                            '--ExecutePreprocessor.kernel_name=python3',
                            '--output-dir', str(output_dir), str(notebook)],
                           cwd=str(HERE))


def run(output_dir: Path, timeout: int, url: Optional[str]) -> int:
    if url is not None:
        os.environ['PYPOWSYBL_RDF_DB'] = url
    # The notebooks import pypowsybl and notebook_utils; an in-place build of the checkout has to be importable
    os.environ['PYTHONPATH'] = os.pathsep.join(filter(None, [str(CHECKOUT), os.environ.get('PYTHONPATH')]))
    output_dir.mkdir(parents=True, exist_ok=True)
    for notebook in sorted(HERE.glob('0*.ipynb')):
        code = execute(notebook, output_dir, timeout)
        if code != 0:
            print(f'{notebook.name} failed', file=sys.stderr)
            return code
    print(f'all notebooks executed into {output_dir}')
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument('--output-dir', type=Path, default=None,
                        help='where the executed copies go (default: a temporary directory)')
    parser.add_argument('--timeout', type=int, default=900, help='per-cell timeout in seconds')
    parser.add_argument('--fuseki', action='store_true',
                        help='start a Fuseki subprocess and point the notebooks at it')
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        output_dir = args.output_dir or Path(tmp)
        if args.fuseki:
            with fuseki() as url:
                return run(output_dir, args.timeout, url)
        return run(output_dir, args.timeout, None)


if __name__ == '__main__':
    sys.exit(main())
