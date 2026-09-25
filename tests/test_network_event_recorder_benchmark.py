#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Benchmark of the network event recorder.

Run it as ``pytest tests/test_network_event_recorder_benchmark.py -s -q`` or directly as
``python tests/test_network_event_recorder_benchmark.py``. Every number is the best of five runs after two warm-ups,
measured with ``time.perf_counter``.

Measured on 2026-09-18, Linux x86_64, 8 cores, GraalVM 21.0.11, native image built from `feat/diffstacking`.
Network: ieee300 exported to CGMES and re-imported, 198 loads and 69 generators. The 1000 changes are spread over
those 267 elements, so the compaction leaves 267 distinct attributes to write.

====================================================  =========
Measurement                                           Best of 5
====================================================  =========
1000 ``update_*`` attribute changes, no recorder         3.78 ms
1000 ``update_*`` attribute changes, recorder running    3.78 ms
``to_ssh()`` of those 1000 changes                       1.85 ms
``to_cgmes_diff()`` of those 1000 changes                4.16 ms
``events`` dataframe of those 1000 changes               1.56 ms
``update_from_string`` of that partial SSH              11.31 ms
``update_from_string`` of that difference model         12.35 ms
``to_ssh()`` of a single change                          0.09 ms
``to_cgmes_diff()`` of a single change                   0.11 ms
====================================================  =========

The listener overhead is below the measurement noise of the update itself. Both exports of 1000 changes are an
order of magnitude under the target of the architecture, which is 50 ms for 500 changes, so no profiling of the
transfer layer was needed.

The assertions here are loose CI guards, not the target.
"""
import pathlib
import sys
import time
from typing import Callable, Dict, List, Tuple

import pytest

import pypowsybl as pp

TEST_DIR = pathlib.Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'

# The receiver measurement applies the same document over and over, so the supersedes check has to be off; a
# partial SSH additionally needs the receiver to keep the values it does not mention.
SSH_PARAMS = {'iidm.import.cgmes.use-previous-values-during-update': 'true'}
DIFF_PARAMS = {'iidm.import.cgmes.diff.check-supersedes': 'false'}

ROUNDS = 5
WARMUPS = 2
UPDATES = 1000


def _benchmark_network() -> pp.network.Network:
    """
    A network the CGMES exporters accept, that is one that came from CGMES.

    ieee300 is preferred because it is large, but only if a CGMES round trip of it works; otherwise the CGMES
    conformity fixture is used, which is small but certainly valid.
    """
    try:
        buffer = pp.network.create_ieee300().save_to_binary_buffer(format='CGMES')
        buffer.seek(0)
        return pp.network.load_from_binary_buffer(buffer)
    except Exception:  # pylint: disable=broad-except
        return pp.network.load(DATA_DIR / 'CGMES_Full.zip')


def _best_of(action: Callable[[], None]) -> float:
    for _ in range(WARMUPS):
        action()
    best = float('inf')
    for _ in range(ROUNDS):
        start = time.perf_counter()
        action()
        best = min(best, time.perf_counter() - start)
    return best


def _apply_updates(network: pp.network.Network, count: int, offset: float) -> None:
    """``count`` attribute changes, spread over the loads and the generators, in two dataframe calls."""
    loads = network.get_loads()
    generators = network.get_generators()
    load_ids = list(loads.index)
    generator_ids = list(generators.index)
    done = 0
    round_index = 0
    while done < count:
        ids = load_ids[: min(len(load_ids), count - done)]
        if ids:
            network.update_loads(id=ids, p0=[offset + round_index + i for i in range(len(ids))])
            done += len(ids)
        if done >= count:
            break
        ids = generator_ids[: min(len(generator_ids), count - done)]
        if ids:
            network.update_generators(id=ids, target_p=[offset + round_index + i for i in range(len(ids))])
            done += len(ids)
        round_index += 1
        if not load_ids and not generator_ids:
            raise AssertionError('the benchmark network has neither loads nor generators')


def measure() -> Tuple[Dict[str, float], List[str]]:
    """Every measured number in seconds, plus the notes describing the setup."""
    network = _benchmark_network()
    receiver = _benchmark_network()
    notes = [f'network {network.id!r}: {len(network.get_loads())} loads, '
             f'{len(network.get_generators())} generators, source format {network.source_format}']

    results: Dict[str, float] = {}

    # listener overhead: the same updates with and without an attached recorder
    results['update 1000x without recorder'] = _best_of(lambda: _apply_updates(network, UPDATES, 1.0))

    recorder = network.event_recorder()
    recorder.start()

    def record() -> None:
        recorder.clear()
        _apply_updates(network, UPDATES, 2.0)

    results['update 1000x with recorder'] = _best_of(record)

    recorder.clear()
    _apply_updates(network, UPDATES, 3.0)
    recorder.stop()
    notes.append(f'{len(recorder)} recorded events for the 1000 update measurements')

    results['to_ssh of 1000 updates'] = _best_of(lambda: recorder.to_ssh())
    results['to_cgmes_diff of 1000 updates'] = _best_of(lambda: recorder.to_cgmes_diff())
    results['events dataframe of 1000 updates'] = _best_of(lambda: recorder.events)

    ssh = recorder.to_ssh()
    diff = recorder.to_cgmes_diff()
    results['update_from_string of that partial SSH'] = _best_of(
        lambda: receiver.update_from_string(ssh, 'b_SSH.xml', parameters=SSH_PARAMS))
    results['update_from_string of that difference model'] = _best_of(
        lambda: receiver.update_from_string(diff, 'b_SSH_DIFF.xml', parameters=DIFF_PARAMS))

    # the single change case, which is what an event driven sender does
    single = network.event_recorder()
    single.start()
    load_id = list(network.get_loads().index)[0]
    network.update_loads(id=load_id, p0=42.0)
    single.stop()
    results['to_ssh of 1 update'] = _best_of(lambda: single.to_ssh())
    results['to_cgmes_diff of 1 update'] = _best_of(lambda: single.to_cgmes_diff())

    return results, notes


def _print(results: Dict[str, float], notes: List[str]) -> None:
    for note in notes:
        print(f'# {note}')
    for name, seconds in results.items():
        print(f'{name:50s} {seconds * 1000:9.2f} ms')


@pytest.fixture(autouse=True)
def set_up():
    pp.set_config_read(False)


def test_benchmark():
    results, notes = measure()
    _print(results, notes)

    # loose CI guards, see the module docstring
    assert results['to_ssh of 1000 updates'] < 0.5
    assert results['to_cgmes_diff of 1000 updates'] < 0.5
    assert results['to_ssh of 1 update'] < 0.5


if __name__ == '__main__':
    pp.set_config_read(False)
    _print(*measure())
    sys.exit(0)
