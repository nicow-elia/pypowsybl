#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
What the versioned RDF database costs from Python, measured.

Run it as a test (``pytest tests/test_rdf_db_benchmark.py -s -q``) or as a script
(``python tests/test_rdf_db_benchmark.py``); ``-s`` is what makes the numbers visible. Every number is the median
of five runs after two warm-ups, taken with ``time.perf_counter`` around the Python call, so it includes the
binding layer - the string copies of ``CTypeUtil``, the dataframe marshalling and the GIL hand-over - on top of
whatever the Java side does. The Java split of an update is printed next to it, read from
``get_rdf_db_update_info`` (``plan_ms``, ``fetch_ms``, ``compose_ms``, ``apply_ms``).

Measured on 2026-09-21 against the final step-12 core, ``data/CGMES_Full.zip`` (~1 MB, 6 loads), 8 cores,
in-process store and a Fuseki subprocess on the loopback interface, with two scenarios in the store
(``scratchpad/logs/s12pp-fix-bench.log``; the run of 2026-09-18 is in ``scratchpad/logs/f5-bench.log``):

=====================================================  ==========  ==========
 what                                                   memory:     Fuseki
=====================================================  ==========  ==========
 (a)      file import, ``pp.network.load``              82 ms       78 ms
 (u)      upload of the root snapshot                   51 ms      183 ms
 (b)      cold load of a snapshot (no cache)            35 ms       92 ms
 (c)      warm load of a snapshot (cache on)            34 ms       56 ms
 (e1)     export of 1 change, ``to_rdf_updates``       1.9 ms       37 ms
 (e500)   export of 500 changes, ``to_rdf_updates``    2.3 ms       56 ms
 (e500r)  500 ``update_loads`` **and** the export      110 ms      156 ms
 (d1)     update over 1 difference                     8.6 ms       52 ms
 (d10)    update over 10 differences                   7.8 ms       53 ms
 (f)      full route inside one scenario (EQ drift)     40 ms       96 ms
 (g)      full route across scenarios                   34 ms       61 ms
 (v)      a day of 24 timesteps as **variants**        111 ms      161 ms
 (vsep)   the same day as 24 separate networks         832 ms     1192 ms
 (vwalk)  the same day walked by one network           115 ms      412 ms
 (v1)     one variant moved to another snapshot        5.1 ms       17 ms
=====================================================  ==========  ==========

**The variant rows.** ``(v)`` is a median of five runs like the rest; ``(vsep)`` and ``(vwalk)`` are **single
runs** over the whole day, because they take the better part of a second and repeating them five times would not
change the conclusion. Loading a day as variants is **7.3x** (in process) / **7.8x** (Fuseki) faster than loading
every timestep as a network of its own, which is the comparison the feature exists for: the equipment is
converted once and every further timestep is a clone plus its stored difference. Against a single network *walked*
through the day - which keeps no history at all, so it is not a substitute - the variants come out level in
process (111 against 115 ms) and **2.6x faster over HTTP**, where the walk pays one plan query per step while the
bulk load sends one for the whole day.

**What day this is.** ``_build_day`` writes a *one-value* steady-state difference per timestep on a six-load
model, so the per-variant apply is as small as it can be and the bulk load is shown at its best. Core measures
the same feature on a thin and on a **rich** day (six loads, two generators and a tap changer per timestep) over
96 timesteps and reports about 25 % more for the rich one; the ratio against the separate loads, which is what
the feature is about, is dominated by the single conversion either way. ``(v1)`` is listed without an
explanation on purpose: what makes it cheaper or dearer than ``(d1)`` (a different chain, a different clone
source) is not measured here.

The two export rows measure different things on purpose. **(e1) and (e500) time ``to_rdf_updates`` alone**: the
changes are recorded once, outside the timed region, and every timed call stores the same events again as another
version (``clear=False``). **(e500r)** is the whole gesture a user makes - five hundred one-row ``update_loads``
calls, each a dataframe round trip through the binding, and then the export - and it is dominated by the five
hundred calls, not by the export.

Targets of architecture §5.7 (reported, never asserted): ``b <= a`` **met** on both backends, ``c < a`` **met**,
``d1 < 100 ms`` **met**. ``e500 < 50 ms`` is **met in process** (2.3 ms) and **straddles the target over HTTP**:
four runs of this benchmark on this machine measured 46, 49, 51.6 and 56.4 ms for it, the last of them the run
above, so it is reported as **MISSED** here. The call is essentially one round trip carrying a 500-change
document; the target sits inside the run-to-run spread of that round trip rather than above or below it, and
nothing in this step changed the code path. The guards the test does assert are ``e500 < 1 s``, ``d10 < 5 s``,
``c <= 2 x a`` and ``v < vsep``, all comfortably met.

**Reading the numbers.** The split pays off where it was meant to: a warm load of a snapshot is faster than
importing the same model from files (34 ms against 82 ms in process, 56 ms against 78 ms over HTTP), and moving a
network from one state to another costs 8-53 ms instead of a full import. Storing a difference is cheap in
absolute terms and, over HTTP, is one round trip whether it carries one change or five hundred - which is the
property that makes a day of timesteps affordable.

**(d10) is not ten times (d1).** Walking ten differences costs the same as walking one, on both backends. That is
the shape of the design rather than a measurement error: the planner answers with **one** query whatever the chain
length, and every difference on the path is fetched in **one** request, so the only thing that grows with the chain
is the number of statements folded - and on an SSH-only change of six loads that is nothing. Core measured the
planner's own growth separately (report 08: 49 ms at depth 50 on loopback Fuseki), which is what
:meth:`RdfDatabase.checkpoint` is for.

**The two full routes** cost about what a load costs, which is what they are: (f) rebuilds inside the scenario
after an equipment drift the in-place update cannot apply, (g) rebuilds from another scenario. (f) is the dearer one
here, because the drifted timestep has to be materialised from the base plus the chain while the other scenario's
root is a full state already.

**Where the time goes, and whose fault it is.** The Java split printed next to (d1)/(d10) accounts for nearly all
of the wall time - 45 ms of the 52 ms on Fuseki, dominated by ``plan_ms`` (the round trip of the planning query),
not by the binding. In process the same call is 8.6 ms wall against ~6 ms of Java, so about 1-2 ms is the binding
layer: the option and parameter maps copied through ``CTypeUtil``, the outcome handle, and the string map of
``get_rdf_db_update_info``. That is the price of one update and it does not grow with the model. The upload row
(u) is dearer over HTTP for a simpler reason: the archive has to be parsed *and* its graphs transferred to the
server, which the in-process store does not have to do.
"""
import sys
import time
from pathlib import Path
from statistics import median
from typing import Callable, Dict, List, Optional, Tuple
from uuid import uuid4

import pytest

import pypowsybl as pp
import pypowsybl._pypowsybl as _pp

TEST_DIR = Path(__file__).parent
sys.path.insert(0, str(TEST_DIR))

from rdf_db_fixtures import CGMES_ZIP, eq_drift, next_day_zip  # noqa: E402  pylint: disable=wrong-import-position

PARAMS = {'iidm.import.cgmes.create-cgmes-export-mapping': 'true'}
WARMUPS = 2
RUNS = 5

# Loose guards, meant to catch a collapse rather than a slowdown; the targets below are reported, never asserted
GUARD_E500_S = 1.0
GUARD_D10_S = 5.0
GUARD_WARM_OVER_FILE = 2.0

# How long a day the variant rows use. A production day is 96 quarter-hours; 24 keeps the *build* of the fixture
# (one export plus one update back per timestep) inside a test run, and the shape of the answer does not change
DAY_TIMESTEPS = 24

TARGETS_MS = {'c': None, 'e500': 50.0, 'd1': 100.0}


def _median_ms(call: Callable[[], None]) -> float:
    """Median of :data:`RUNS` timings in milliseconds, after :data:`WARMUPS` untimed runs."""
    for _ in range(WARMUPS):
        call()
    samples = []
    for _ in range(RUNS):
        start = time.perf_counter()
        call()
        samples.append((time.perf_counter() - start) * 1000.0)
    return median(samples)


def _report(name: str, what: str, millis: float, extra: str = '') -> None:
    print(f'  {name:<24} {millis:8.1f} ms   {what}{extra}')


def _change_loads(network: pp.network.Network, count: int, base: float) -> None:
    """Move active powers until ``count`` changes have been recorded, cycling over the loads of the network."""
    ids = list(network.get_loads().index)
    for i in range(count):
        network.update_loads(id=ids[i % len(ids)], p0=base + i)


def _java_split(info: Dict[str, str]) -> str:
    keys = ('plan_ms', 'fetch_ms', 'compose_ms', 'apply_ms')
    return ' (java ' + ', '.join(f'{k[:-3]} {info.get(k, "?")}' for k in keys) + ')'


def _update_info(network: pp.network.Network, db: pp.network.RdfDatabase, scenario: str,
                 version: str) -> Dict[str, str]:
    """
    Run one update through the raw binding so that the Java timings of that very call can be read back.

    :meth:`Network.update_from_rdf_db` drops the outcome handle after reading the route, which is the right thing
    for an API and the wrong thing for a benchmark; this repeats the call one level lower.
    """
    outcome = _pp.update_network_from_rdf_db(
        network._handle, db._check_open(), scenario, version, '', [],  # pylint: disable=protected-access
        {'max_diff_chain': '200'}, PARAMS, None)
    return _pp.get_rdf_db_update_info(outcome)


def measure(url: str, label: str) -> Dict[str, float]:  # pylint: disable=too-many-locals,too-many-statements
    """Take every number on one backend and print them; returns the numbers in milliseconds."""
    print(f'\n=== {label} ({url}) ===')
    numbers: Dict[str, float] = {}
    scenario = f'bench-{uuid4().hex[:8]}'
    other = f'bench-other-{uuid4().hex[:8]}'

    numbers['a'] = _median_ms(lambda: pp.network.load(CGMES_ZIP, PARAMS))
    _report('(a) file import', 'pp.network.load(zip)', numbers['a'])

    # (u) upload: a root can be written once per scenario, so every run needs a scenario of its own
    def upload() -> None:
        pp.network.connect(url).load_cgmes(CGMES_ZIP, f'{scenario}-u-{uuid4().hex[:8]}', '1.0', parameters=PARAMS)

    numbers['u'] = _median_ms(upload)
    _report('(u) upload root', 'db.load_cgmes(zip, s, "1.0")', numbers['u'])

    with pp.network.connect(url) as db:
        # The store the rest of the run reads from, with a second day in it so that nothing is measured on an
        # unrealistically empty database
        db.load_cgmes(CGMES_ZIP, scenario, '1.0', parameters=PARAMS)
        db.load_cgmes_from_binary_buffers([next_day_zip()], other, '1.0', parameters=PARAMS)

        def cold() -> None:
            with pp.network.connect(url, cache=False) as fresh:
                pp.network.from_rdf_db(fresh, scenario, '1.0', parameters=PARAMS)

        numbers['b'] = _median_ms(cold)
        _report('(b) db load, cold', 'fresh connection, cache off', numbers['b'])

        numbers['c'] = _median_ms(lambda: pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS))
        _report('(c) db load, warm', 'same connection, cache on', numbers['c'])

        # (e1) / (e500): the export alone. The changes are recorded once, outside the timed region, and every
        # timed call stores the very same events again as a new version - which clear=False makes possible and
        # which the database allows. What is measured is to_rdf_updates and nothing else
        sender = pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS)
        counter = [0]

        def export(change_count: int) -> Callable[[], None]:
            recorder = sender.event_recorder()
            recorder.start()
            _change_loads(sender, change_count, 100.0 + change_count)
            recorder.stop()

            def run() -> None:
                counter[0] += 1
                recorder.to_rdf_updates(db, scenario, f'2.{counter[0]}', clear=False)
            return run

        numbers['e1'] = _median_ms(export(1))
        _report('(e1) export 1 change', 'to_rdf_updates alone', numbers['e1'])
        numbers['e500'] = _median_ms(export(500))
        _report('(e500) export 500 changes', 'to_rdf_updates alone', numbers['e500'])

        # And the whole gesture a user makes, for comparison: the 500 one-row update_loads calls that produce the
        # changes, plus the export. This is the number the first version of this benchmark reported as (e500)
        def record_and_export() -> None:
            counter[0] += 1
            with sender.event_recorder() as recorder:
                _change_loads(sender, 500, 700.0 + counter[0])
                recorder.to_rdf_updates(db, scenario, f'2.{counter[0]}')

        numbers['e500r'] = _median_ms(record_and_export)
        _report('(e500r) 500 changes + export', '500 update_loads, then to_rdf_updates', numbers['e500r'])

        head = f'2.{counter[0]}'
        versions = [f'2.{counter[0] - i}' for i in range(10, 0, -1)]

        # One difference: a network sitting one version behind the head, toggled back and forth
        walker = pp.network.from_rdf_db(db, scenario, versions[-2], parameters=PARAMS)
        toggle = [0]

        def single() -> None:
            toggle[0] += 1
            target = head if toggle[0] % 2 else versions[-2]
            walker.update_from_rdf_db(db, scenario, target)

        numbers['d1'] = _median_ms(single)
        walker.update_from_rdf_db(db, scenario, versions[-2])
        _report('(d1) update, 1 diff', 'update_from_rdf_db', numbers['d1'],
                _java_split(_update_info(walker, db, scenario, head)))

        far = pp.network.from_rdf_db(db, scenario, versions[0], parameters=PARAMS)
        step = [0]

        def ten() -> None:
            step[0] += 1
            target = head if step[0] % 2 else versions[0]
            far.update_from_rdf_db(db, scenario, target)

        numbers['d10'] = _median_ms(ten)
        far.update_from_rdf_db(db, scenario, versions[0])
        _report('(d10) update, 10 diffs', 'update_from_rdf_db', numbers['d10'],
                _java_split(_update_info(far, db, scenario, head)))

        # (f) the full route inside one scenario: a timestep whose equipment drifted cannot be applied in place
        db.load_cgmes_from_binary_buffers([eq_drift(7, '23:00')], scenario, '9.9', '23:00', parameters=PARAMS)
        drifter = pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS)
        step = [0]

        def drift() -> None:
            step[0] += 1
            if step[0] % 2:
                drifter.update_from_rdf_db(db, scenario, '9.9', '23:00')
            else:
                drifter.update_from_rdf_db(db, scenario, '1.0')

        numbers['f'] = _median_ms(drift)
        _report('(f) full, same scenario', 'EQ drift -> update_from_rdf_db reloads', numbers['f'])

        # (g) the other way to the full route: a snapshot of another scenario, decided without a plan query
        crosser = pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS)
        side = [0]

        def full() -> None:
            side[0] += 1
            target = other if side[0] % 2 else scenario
            crosser.update_from_rdf_db(db, target, '1.0')

        numbers['g'] = _median_ms(full)
        _report('(g) full, cross-scenario', 'update_from_rdf_db -> full reload', numbers['g'])

        numbers.update(_measure_day_as_variants(db, url))

    for name, target in TARGETS_MS.items():
        if target is not None and numbers[name] > target:
            print(f'  TARGET MISSED: {name} = {numbers[name]:.1f} ms, target {target:.0f} ms')
    if numbers['c'] >= numbers['a']:
        print(f'  TARGET MISSED: c = {numbers["c"]:.1f} ms is not below a = {numbers["a"]:.1f} ms')
    return numbers


def _build_day(db: pp.network.RdfDatabase, scenario: str, labels: List[str]) -> None:
    """
    A day of steady-state timesteps, written the cheap way: record one load move, export it as that timestep, and
    bring the sender back to the head of the base timestep for the next one.

    Ingesting the same day from files would be more realistic and an order of magnitude dearer, and what is
    measured below reads the stored differences either way.
    """
    db.load_cgmes(CGMES_ZIP, scenario, '1.0', parameters=PARAMS)
    sender = pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS)
    load_id = sorted(sender.get_loads().index)[0]
    for i, label in enumerate(labels, start=1):
        with sender.event_recorder() as recorder:
            sender.update_loads(id=load_id, p0=100.0 + i)
            recorder.to_rdf_updates(db, scenario, '1.1', label)
        sender.update_from_rdf_db(db, scenario, '1.0')


def _measure_day_as_variants(db: pp.network.RdfDatabase, url: str) -> Dict[str, float]:
    """
    The three ways to have a day in memory, on one store.

    ``(v)`` is one network whose variants are the timesteps, ``(vsep)`` is one network per timestep and
    ``(vwalk)`` is one network walked through the day - which keeps no history at all and is here to show what
    the variants cost on top of the cheapest thing that exists. ``(vsep)`` and ``(vwalk)`` are **single runs**
    over the whole day rather than medians: they take seconds, and repeating them five times would not change the
    conclusion.
    """
    numbers: Dict[str, float] = {}
    scenario = f'bench-day-{uuid4().hex[:8]}'
    labels = [f'{hour:02d}:00' for hour in range(DAY_TIMESTEPS)]
    _build_day(db, scenario, labels)

    numbers['v'] = _median_ms(
        lambda: pp.network.from_rdf_db(db, scenario, '1.1', timesteps=labels, parameters=PARAMS))
    _report(f'(v) day of {DAY_TIMESTEPS} as variants', 'from_rdf_db(timesteps=[...])', numbers['v'])

    start = time.perf_counter()
    for label in labels:
        pp.network.from_rdf_db(db, scenario, '1.1', label, parameters=PARAMS)
    numbers['vsep'] = (time.perf_counter() - start) * 1000.0
    _report(f'(vsep) {DAY_TIMESTEPS} separate loads', 'one network per timestep, single run', numbers['vsep'])

    walker = pp.network.from_rdf_db(db, scenario, '1.0', parameters=PARAMS)
    start = time.perf_counter()
    for label in labels:
        walker.update_from_rdf_db(db, scenario, '1.1', label)
    numbers['vwalk'] = (time.perf_counter() - start) * 1000.0
    _report(f'(vwalk) {DAY_TIMESTEPS} updates', 'one network walking the day, single run', numbers['vwalk'])

    day = pp.network.from_rdf_db(db, scenario, '1.1', timesteps=labels, parameters=PARAMS)
    toggle = [0]

    def move_one() -> None:
        toggle[0] += 1
        day.update_from_rdf_db(db, scenario, '1.1', labels[toggle[0] % 2], variant='study')

    numbers['v1'] = _median_ms(move_one)
    _report('(v1) one variant moved', 'update_from_rdf_db(..., variant=)', numbers['v1'])

    binding = day.variants_binding()
    bound = int((binding['status'] == 'bound').sum())
    assert bound == DAY_TIMESTEPS + 1, f'expected {DAY_TIMESTEPS} timesteps plus the study variant, got {bound}'
    print(f'  ratio v / vsep = {numbers["v"] / numbers["vsep"]:.2f}, '
          f'v / vwalk = {numbers["v"] / numbers["vwalk"]:.2f}   (on {url})')
    return numbers


def _backends(fuseki: Optional[str]) -> List[Tuple[str, str]]:
    backends = [(f'memory:{uuid4().hex}', 'in-process store')]
    if fuseki is not None:
        backends.append((fuseki, 'Apache Jena Fuseki, subprocess'))
    return backends


def test_rdf_db_performance(capsys: pytest.CaptureFixture) -> None:
    """Take every number on both backends and guard against a collapse."""
    pp.set_config_read(False)
    import fuseki_server  # pylint: disable=import-outside-toplevel
    reason = fuseki_server.unavailable_reason()
    server = None if reason else fuseki_server.start()
    try:
        url = None if server is None else server.dataset_url
        if server is None:
            with capsys.disabled():
                print(f'\nFuseki skipped: {reason}')
        for backend, label in _backends(url):
            numbers = measure(backend, label)
            assert numbers['e500'] < GUARD_E500_S * 1000, f'{label}: 500 changes took {numbers["e500"]:.0f} ms'
            assert numbers['d10'] < GUARD_D10_S * 1000, f'{label}: 10 diffs took {numbers["d10"]:.0f} ms'
            assert numbers['c'] <= GUARD_WARM_OVER_FILE * numbers['a'], \
                f'{label}: a warm database load took {numbers["c"]:.0f} ms against {numbers["a"]:.0f} ms of a file'
            assert numbers['v'] < numbers['vsep'], \
                (f'{label}: a day as {DAY_TIMESTEPS} variants took {numbers["v"]:.0f} ms against '
                 f'{numbers["vsep"]:.0f} ms for {DAY_TIMESTEPS} separate loads; the whole point of the bulk load '
                 'is that it converts the model once')
    finally:
        if server is not None:
            server.stop()


if __name__ == '__main__':
    sys.exit(pytest.main([__file__, '-s', '-q']))
