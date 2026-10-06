#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
The versioned half of the RDF database binding: snapshots, timestamps, routes and the catalogue views.

Every test runs twice, once on the in-process ``memory:`` backend and once against a real Fuseki server started as
a subprocess (see ``conftest.py``); the two never share data because every test names its own scenario.

**The two ways a snapshot is written**, both covered here: from a recorder
(:meth:`NetworkEventRecorder.to_rdf_updates`, which is how one client tells another what it changed) and from a
set of CGMES instance files (:meth:`RdfDatabase.load_cgmes` with a version, which is how a TSO's day of timestamps
gets in). They end up in the same chain and are read back the same way.

**The address** is ``(scenario, version, timestamp, modelling_authority)``: an ``int`` version, a timezone-aware
datetime and the modelling authority the snapshot is stored under. ``CGMES_Full.zip`` states one per profile, so
every write here names :data:`AUTHORITY`; reads leave it open (``None``), which resolves to the only authority of
the scenario.
"""
import io
import pickle
import re
import zipfile
from datetime import datetime, timedelta, timezone
from typing import Optional, Tuple
from uuid import uuid4

import pandas as pd
import pytest

import pypowsybl as pp
from pypowsybl import PyPowsyblError
from rdf_db_fixtures import (AUTHORITY, BASE, CGMES_ZIP, NEXT_DAY, at, drifted_name, eq_drift, next_day_zip,
                             ssh_variant)
from test_network_event_recorder import (apply_five_changes, assert_same_setpoints, first_id,
                                         other_tap)

PARAMS = {'iidm.import.cgmes.create-cgmes-export-mapping': 'true'}


def _root(db: pp.network.RdfDatabase, scenario: str) -> pp.network.Network:
    """Store the base grid model as the root of a scenario and return the network of that root."""
    ids = db.load_cgmes(CGMES_ZIP, scenario, 1, modelling_authority=AUTHORITY, parameters=PARAMS)
    assert len(ids) >= 4, f'the root of {scenario} should hold one model per instance file, got {ids}'
    return pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)


def _change_a_load(network: pp.network.Network, value: float) -> str:
    """Move the active power of one load, which is an SSH-only change and therefore a fast-route difference."""
    load = first_id(network.get_loads())
    network.update_loads(id=load, p0=value)
    return load


def _all_names(network: pp.network.Network) -> set:
    """Every equipment name of a network, so that a rename can be spotted wherever the element landed."""
    names = set()
    for frame in (network.get_lines(), network.get_boundary_lines(), network.get_2_windings_transformers(),
                  network.get_loads(), network.get_generators()):
        if 'name' in frame.columns:
            names.update(str(n) for n in frame['name'])
    return names


def _record(db: pp.network.RdfDatabase, network: pp.network.Network, scenario: str, version: Optional[int],
            timestamp: Optional[datetime], value: float) -> Tuple[str, list]:
    """Change one load on ``network`` and store the change as the snapshot ``(scenario, version, timestamp)``."""
    with network.event_recorder() as recorder:
        load = _change_a_load(network, value)
        ids = recorder.to_rdf_updates(db, scenario, version, timestamp)
    return load, ids


_SNAPSHOT_COLUMNS = ['scenario', 'modelling_authority', 'timestamp', 'version', 'profiles', 'kind', 'parent',
                     'edge', 'depth', 'has_full', 'fast', 'members', 'created', 'description']


def _other_tso_zip(suffix: str) -> io.BytesIO:
    """``CGMES_Full.zip`` as the files of another TSO of the same day: other model ids, the same boundary."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if '_BD_' not in entry:
                content = re.sub(r'(<md:FullModel[^>]*rdf:about=")([^"]*)(")', r'\g<1>\g<2>' + suffix + r'\g<3>',
                                 content)
                content = re.sub(r'(<md:Model\.DependentOn[^>]*rdf:resource=")([^"]*)(")',
                                 r'\g<1>\g<2>' + suffix + r'\g<3>', content)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


def test_followup_usage_pattern(rdf_db_url: str, scenario: str) -> None:
    """The snippet of the follow-up specification, verbatim except for the scenario argument."""
    database = rdf_db_url
    with pp.network.connect(database) as db:
        network = _root(db, scenario)
        assert db.versioned(scenario)

        with network.event_recorder() as recorder:
            load = _change_a_load(network, 321.0)
            ids = recorder.to_rdf_updates(db, scenario, 1, datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc))
        assert ids, 'the export should have stored at least the SSH difference'
        assert len(recorder) == 0, 'a successful export clears the recorder by default'

        # A second reader sees exactly the sender's state at that address - however the moment is written
        reader = pp.network.from_rdf_db(db, scenario, 1, datetime(2021, 2, 9, 21, 30,
                                                                  tzinfo=timezone(timedelta(hours=1))),
                                        parameters=PARAMS)
        assert_same_setpoints(network, reader)
        assert reader.get_loads().loc[load, 'p0'] == pytest.approx(321.0)

        # Two more versions of the same timestamp, from the same sender
        _record(db, network, scenario, 2, at('20:30'), 322.0)
        _record(db, network, scenario, 3, at('20:30'), 323.0)

        network2 = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        assert network2.update_from_rdf_db(db, scenario, 3, at('20:30')) == 'diff'
        assert_same_setpoints(network, network2)

        identity = network2.rdf_db_identity()
        assert identity['scenario'] == scenario
        assert identity['modelling_authority'] == AUTHORITY
        assert identity['timestamp'] == '2021-02-09T20:30:00Z'
        snapshots = db.snapshots(scenario)
        assert snapshots.loc[identity['snapshot'], 'version'] == 3


def test_catalog_dataframes(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        _record(db, network, scenario, None, at('20:30'), 301.0)
        _record(db, network, scenario, None, at('20:30'), 302.0)

        snapshots = db.snapshots(scenario)
        assert snapshots.index.name == 'snapshot'
        assert list(snapshots.columns) == _SNAPSHOT_COLUMNS
        assert snapshots['scenario'].unique().tolist() == [scenario]
        assert snapshots['modelling_authority'].unique().tolist() == [AUTHORITY]
        assert 'int' in str(snapshots['depth'].dtype)
        assert snapshots['has_full'].dtype == bool and snapshots['fast'].dtype == bool
        assert sorted(snapshots['version']) == [1, 1, 2], 'a new timestamp starts its chain at 1'
        assert sorted(snapshots['kind']) == ['diff', 'diff', 'full']
        assert sorted(snapshots['timestamp'].unique()) == [BASE, at('20:30')]
        assert all({'EQ', 'SSH'} <= set(profiles.split(';')) for profiles in snapshots['profiles'])

        timestamps = db.timestamps(scenario)
        assert timestamps.index.name == 'timestamp'
        assert list(timestamps.columns) == ['modelling_authority', 'root', 'head', 'version_count', 'pinned_base']
        assert list(timestamps.index) == [BASE, at('20:30')]
        assert sorted(timestamps['version_count']) == [1, 2]
        assert timestamps.loc[BASE, 'pinned_base'] == ''

        versions = db.versions(scenario, at('20:30'))
        assert versions['version'].tolist() == [1, 2]
        assert db.versions(scenario)['version'].tolist() == [1]
        assert db.versions(scenario, at('20:30'), AUTHORITY)['version'].tolist() == [1, 2]
        assert db.modelling_authorities(scenario) == [AUTHORITY]

        models = db.models(scenario)
        assert models.index.name == 'id'
        assert models['scenario'].unique().tolist() == [scenario]
        diffs = models[models['kind'] == 'diff']
        assert not diffs.empty and bool(diffs['fast'].all()), 'an SSH-only change is a fast-route difference'

        scenarios = db.scenarios()
        assert scenarios.index.name == 'scenario'
        assert list(scenarios.columns) == ['modelling_authorities', 'versioned', 'snapshot_count']
        assert scenarios.loc[scenario, 'modelling_authorities'] == AUTHORITY
        assert bool(scenarios.loc[scenario, 'versioned'])
        assert int(scenarios.loc[scenario, 'snapshot_count']) == 3


def test_snapshots_dataframe_types(rdf_db_url: str, scenario: str) -> None:
    """The address columns carry Python types: an aware UTC timestamp and an int64 version, empty or not."""
    with pp.network.connect(rdf_db_url) as db:
        empty = db.snapshots(scenario)
        assert str(empty['timestamp'].dt.tz) == 'UTC' and empty['version'].dtype == 'int64'
        network = _root(db, scenario)
        _record(db, network, scenario, 5, at('20:30'), 303.0)
        for frame in (db.snapshots(scenario), db.versions(scenario, at('20:30'))):
            assert str(frame['timestamp'].dt.tz) == 'UTC'
            assert frame['version'].dtype == 'int64'
        assembly = db.assembly(scenario, BASE)
        assert str(assembly['timestamp'].dt.tz) == 'UTC'
        assert assembly['version'].dtype == 'Int64', 'nullable: an authority may have no snapshot at that moment'
        assert str(db.timestamps(scenario).index.tz) == 'UTC'
        assert db.snapshots(scenario)['version'].max() == 5, 'an explicit version may leave a gap'


def test_naive_datetime_is_refused(rdf_db_url: str, scenario: str) -> None:
    """A naive datetime names no instant; every call refuses it before anything reaches the database."""
    naive = datetime(2021, 2, 9, 20, 30)
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        calls = [
            lambda: pp.network.from_rdf_db(db, scenario, 1, naive),
            lambda: pp.network.from_rdf_db(db, scenario, timestamps=[naive]),
            lambda: network.update_from_rdf_db(db, scenario, 1, naive),
            lambda: db.load_cgmes(CGMES_ZIP, scenario, 2, naive, modelling_authority=AUTHORITY),
            lambda: db.versions(scenario, naive),
            lambda: db.checkpoint(scenario, 1, naive),
            lambda: db.assembly(scenario, naive),
            lambda: pp.network.from_rdf_db(db, scenario, 1, '2021-02-09T20:30:00Z'),  # type: ignore[arg-type]
        ]
        for call in calls:
            with pytest.raises(TypeError, match='timezone-aware'):
                call()
        with network.event_recorder() as recorder:
            _change_a_load(network, 304.0)
            with pytest.raises(TypeError, match='timezone-aware'):
                recorder.to_rdf_updates(db, scenario, 2, naive)
        assert len(db.snapshots(scenario)) == 1, 'nothing was written'


def test_version_must_be_int(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        with pytest.raises(TypeError, match='must be an int'):
            pp.network.from_rdf_db(db, scenario, '1.1')  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='must be an int'):
            pp.network.from_rdf_db(db, scenario, True)
        with pytest.raises(TypeError, match='must be an int'):
            db.checkpoint(scenario, 1.0)  # type: ignore[arg-type]
        with pytest.raises(ValueError, match='at least 1'):
            pp.network.from_rdf_db(db, scenario, 0)


def test_profiles_literal(rdf_db_url: str, scenario: str) -> None:
    """``profiles`` takes :data:`pypowsybl.network.Profile` names; anything else is refused by name."""
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        with pytest.raises(ValueError, match="'XX'"):
            pp.network.from_rdf_db(db, scenario, 1, profiles=['EQ', 'XX'])  # type: ignore[list-item]
        with pytest.raises(ValueError, match="'XX'"):
            network.update_from_rdf_db(db, scenario, 1, profiles=['XX'])  # type: ignore[list-item]
        with pytest.raises(TypeError, match='not one string'):
            pp.network.from_rdf_db(db, scenario, 1, profiles='SSH')  # type: ignore[arg-type]
        # a projection that names the default changes nothing
        assert network.update_from_rdf_db(db, scenario, 1, profiles=['EQ', 'SSH']) == 'noop'


def test_files_of_several_authorities_need_one_named(rdf_db_url: str, scenario: str) -> None:
    """
    ``CGMES_Full.zip`` states one modelling authority per profile, and a snapshot is stored under exactly one: the
    equipment and steady state hypothesis files disagree, so a write that leaves the authority open is refused with
    the authority of every profile, and nothing is stored. Naming one stores the files under it.
    """
    with pp.network.connect(rdf_db_url) as db:
        with pytest.raises(PyPowsyblError, match='state the modelling authorities') as root:
            db.load_cgmes(CGMES_ZIP, scenario, 1, parameters=PARAMS)
        assert not db.versioned(scenario) and db.modelling_authorities(scenario) == []

        _root(db, scenario)
        with pytest.raises(PyPowsyblError, match='state the modelling authorities') as further:
            db.load_cgmes_from_binary_buffers([ssh_variant(1, at('20:00'))], scenario, None, at('20:00'),
                                              parameters=PARAMS)
        assert db.modelling_authorities(scenario) == [AUTHORITY]
        assert db.timestamps(scenario).index.tolist() == [BASE], 'the refused timestamp is not stored'

    for refusal in (root, further):
        message = str(refusal.value)
        for stated in ('EQ=powsybl.org', f'SSH={AUTHORITY}', 'SV=http://tennet.nl/CGMES',
                       'do not agree on one: pass the modelling authority in the address'):
            assert stated in message, f'{stated!r} is missing from: {message}'


def test_two_modelling_authorities(rdf_db_url: str, scenario: str) -> None:
    """A second TSO's files in the same scenario: a tree of its own, the same boundary, and no more guessing."""
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([_other_tso_zip('-' + scenario)], scenario, 1, None, other,
                                          parameters=PARAMS)
        assert db.modelling_authorities(scenario) == [AUTHORITY, other]
        assert db.scenarios().loc[scenario, 'modelling_authorities'] == f'{AUTHORITY};{other}'

        with pytest.raises(PyPowsyblError, match='cannot be left open'):
            pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        with pytest.raises(PyPowsyblError, match='cannot be left open'):
            db.timestamps(scenario)
        assert len(db.timestamps(scenario, other)) == 1

        named = pp.network.from_rdf_db(db, scenario, 1, None, AUTHORITY, parameters=PARAMS)
        assert named.rdf_db_identity()['modelling_authority'] == AUTHORITY

        assembly = db.assembly(scenario, BASE)
        assert assembly.index.name == 'modelling_authority'
        assert list(assembly.index) == [AUTHORITY, other]
        assert assembly['version'].tolist() == [1, 1]
        missing = db.assembly(scenario, BASE, 2)
        assert list(missing.index) == [AUTHORITY, other], 'every authority of the scenario is a row'
        assert missing['version'].isna().all() and (missing['snapshot'] == '').all(), \
            'no authority has a version 2 at that moment'


def test_unknown_scenario_gives_empty_frames(rdf_db_url: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        missing = f'no-such-day-{uuid4().hex[:8]}'
        assert db.snapshots(missing).empty
        assert list(db.snapshots(missing).columns) == _SNAPSHOT_COLUMNS
        assert db.timestamps(missing).empty
        assert db.versions(missing).empty
        assert db.models(missing).empty
        assert db.modelling_authorities(missing) == []
        assert not db.versioned(missing)
        with pytest.raises(PyPowsyblError, match='holds no snapshot'):
            pp.network.from_rdf_db(db, missing, 1)
        # Without a version the un-versioned route answers, and it says the scenario holds nothing
        with pytest.raises(PyPowsyblError, match='the scenario is empty'):
            pp.network.from_rdf_db(db, missing)


def test_routes_noop_diff_full(rdf_db_url: str, scenario: str) -> None:
    """The three routes of one scenario, including the fall back to the full grid model."""
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        _record(db, sender, scenario, 1, at('20:30'), 311.0)

        receiver = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        handle = receiver._handle  # pylint: disable=protected-access
        assert receiver.update_from_rdf_db(db, scenario, 1) == 'noop'
        assert receiver.update_from_rdf_db(db, scenario, 1, at('20:30')) == 'diff'
        assert receiver._handle is handle, 'the fast route applies in place'  # pylint: disable=protected-access
        assert_same_setpoints(sender, receiver)

        # Backwards is a difference too: the stored change is undone
        assert receiver.update_from_rdf_db(db, scenario, 1) == 'diff'
        assert_same_setpoints(pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS), receiver)

        # A timestamp whose equipment drifted cannot be applied in place, so the loader falls back to a reload -
        # inside the same scenario, and without anybody asking for it
        db.load_cgmes_from_binary_buffers([eq_drift(2, at('21:00'))], scenario, None, at('21:00'),
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        models = db.models(scenario)
        assert not bool(models[(models['kind'] == 'diff') & (models['subset'] == 'EQ')]['fast'].all()), \
            'an equipment rename is not a fast-route difference'

        assert receiver.update_from_rdf_db(db, scenario, 1, at('21:00')) == 'full'
        assert receiver._handle is not handle  # pylint: disable=protected-access
        assert drifted_name(2) in _all_names(receiver), 'the renamed equipment reached the reloaded network'
        assert receiver.case_date.strftime('%H:%M') == '21:00'


def test_multiple_scenarios(rdf_db_url: str, scenario: str, scenario2: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        monday = _root(db, scenario)
        db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, 1,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)

        scenarios = db.scenarios()
        assert {scenario, scenario2}.issubset(set(scenarios.index))

        # A write into one day leaves the other alone
        before = db.snapshots(scenario2)
        _record(db, monday, scenario, 2, None, 331.0)
        pd.testing.assert_frame_equal(before, db.snapshots(scenario2))

        # The same wall time of two days is two instants: each scenario's base timestamp is its own day's
        assert db.timestamps(scenario).index[0] == BASE
        assert db.timestamps(scenario2).index[0] == at('19:30', NEXT_DAY)

        # Walking to another day is a full reload, decided without a query
        walker = pp.network.from_rdf_db(db, scenario, 2, parameters=PARAMS)
        handle = walker._handle  # pylint: disable=protected-access
        assert walker.update_from_rdf_db(db, scenario2, 1) == 'full'
        assert walker._handle is not handle  # pylint: disable=protected-access
        assert walker.rdf_db_identity()['scenario'] == scenario2

        # And a sender may not write its difference into the other day
        sender = pp.network.from_rdf_db(db, scenario, 2, parameters=PARAMS)
        with sender.event_recorder() as recorder:
            _change_a_load(sender, 341.0)
            with pytest.raises(PyPowsyblError, match='never cross scenarios'):
                recorder.to_rdf_updates(db, scenario2, 2)


@pytest.mark.parametrize('drift', ['eq_drift', 'other_scenario'])
def test_full_route_swaps_the_handle(rdf_db_url: str, scenario: str, scenario2: str, drift: str) -> None:
    """Both ways to reach the full route, and what it does to the Python object either way."""
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        if drift == 'eq_drift':
            db.load_cgmes_from_binary_buffers([eq_drift(3, at('21:00'))], scenario, 1, at('21:00'),
                                              modelling_authority=AUTHORITY, parameters=PARAMS)
            target = (scenario, 1, at('21:00'))
        else:
            db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, 1,
                                              modelling_authority=AUTHORITY, parameters=PARAMS)
            target = (scenario2, 1, None)

        network.clone_variant('InitialState', 'v2')
        network.per_unit = True
        recorder = network.event_recorder()
        recorder.start()
        network_id = network.id
        sub = network.get_sub_network(network.id) if network.id in network.get_sub_networks().index else None
        handle = network._handle  # pylint: disable=protected-access

        assert network.update_from_rdf_db(db, *target) == 'full'
        assert network._handle is not handle  # pylint: disable=protected-access
        assert network.per_unit is True
        assert network.get_variant_ids() == ['InitialState'], 'variants are not carried over by a reload'
        assert not network.get_loads().empty
        assert pp.loadflow.run_ac(network)[0].status == pp.loadflow.ComponentStatus.CONVERGED

        if drift == 'eq_drift':
            assert network.id == network_id, 'the same grid model, one timestamp later'
            assert drifted_name(3) in _all_names(network)
            assert network.case_date.strftime('%H:%M') == '21:00'
        else:
            assert network.rdf_db_identity()['scenario'] == scenario2
        if sub is not None:
            # A sub-network taken before the reload still points at the Java network that was replaced
            assert not sub.get_loads().empty

        with pytest.raises(PyPowsyblError, match='reloaded'):
            recorder.to_ssh()
        recorder.stop()  # still works: it is the old Java network that is being detached

        fresh = network.event_recorder()
        with fresh:
            _change_a_load(network, 351.0)
        assert len(fresh) >= 1

        restored = pickle.loads(pickle.dumps(network))
        assert restored.id == network.id


def test_timestamps_walk(rdf_db_url: str, scenario: str, scenario2: str) -> None:
    moments = [at('20:30'), at('20:45'), at('21:00')]
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        for i, moment in enumerate(moments, start=1):
            # Every timestamp root hangs off the base chain, so the sender goes back to the base head each time
            sender.update_from_rdf_db(db, scenario, 1)
            _record(db, sender, scenario, None, moment, 400.0 + i)

        timestamps = db.timestamps(scenario)
        assert list(timestamps.index) == [BASE] + moments, 'timestamps come back in time order'

        walker = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        for moment in moments:
            assert walker.update_from_rdf_db(db, scenario, None, moment) == 'diff'
            assert_same_setpoints(pp.network.from_rdf_db(db, scenario, None, moment, parameters=PARAMS), walker)

        iri = db.checkpoint(scenario, None, moments[-1])
        assert bool(db.snapshots(scenario).loc[iri, 'has_full'])
        after = pp.network.from_rdf_db(db, scenario, None, moments[-1], parameters=PARAMS)
        assert_same_setpoints(walker, after)

        # Crossing midnight: the next day is another scenario, so the first step into it is a reload and the
        # steps inside it are differences again - on the replacement network, which is the same Python object
        after_midnight = at('00:15', NEXT_DAY)
        db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, 1,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        db.load_cgmes_from_binary_buffers([ssh_variant(9, after_midnight)], scenario2, None, after_midnight,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        assert walker.update_from_rdf_db(db, scenario2, 1) == 'full'
        assert walker.update_from_rdf_db(db, scenario2, 1, after_midnight) == 'diff'
        assert_same_setpoints(pp.network.from_rdf_db(db, scenario2, 1, after_midnight, parameters=PARAMS), walker)
        assert walker.rdf_db_identity()['scenario'] == scenario2


def test_timestamps_ingested_from_files(rdf_db_url: str, scenario: str) -> None:
    """A day as a TSO produces it: one set of instance files per timestamp, ingested as a difference."""
    moments = [at('20:00'), at('20:15'), at('20:30')]
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        for i, moment in enumerate(moments, start=1):
            members = db.load_cgmes_from_binary_buffers([ssh_variant(i, moment)], scenario, None, moment,
                                                        modelling_authority=AUTHORITY, parameters=PARAMS)
            assert members, f'{moment} should have stored at least the SSH difference'

        timestamps = db.timestamps(scenario)
        assert list(timestamps.index) == [BASE] + moments, 'timestamps come back in time order'
        snapshots = db.snapshots(scenario)
        assert sorted(snapshots['version']) == [1, 1, 1, 1]
        assert sorted(snapshots['kind']) == ['diff', 'diff', 'diff', 'full']

        # An ingested timestamp is read back like any other snapshot, and equals the file it came from
        for i, moment in enumerate(moments, start=1):
            from_db = pp.network.from_rdf_db(db, scenario, None, moment, parameters=PARAMS)
            from_file = pp.network.load_from_binary_buffer(ssh_variant(i, moment), PARAMS)
            pd.testing.assert_frame_equal(from_file.get_loads()[['p0', 'q0']].sort_index(),
                                          from_db.get_loads()[['p0', 'q0']].sort_index(),
                                          check_exact=False, rtol=1e-6)

        # And one network walks the whole day, every step a difference
        walker = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        for moment in moments:
            assert walker.update_from_rdf_db(db, scenario, None, moment) == 'diff'

        iri = db.checkpoint(scenario, None, moments[-1])
        assert bool(db.snapshots(scenario).loc[iri, 'has_full'])

        # The un-versioned upload has no place in a versioned scenario and core says so
        with pytest.raises(PyPowsyblError, match='is versioned'):
            db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)


def test_export_errors(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)

        with network.event_recorder() as recorder:
            with pytest.raises(PyPowsyblError):
                recorder.to_rdf_updates(db, scenario, 2)

        with network.event_recorder() as recorder:
            _change_a_load(network, 361.0)
            with pytest.raises(PyPowsyblError, match='decided by the database'):
                recorder.to_rdf_updates(db, scenario, 2, supersedes='urn:uuid:nope')
            with pytest.raises(ValueError):
                recorder.to_rdf_updates(db, scenario, 2, unsupported='nope')
            with pytest.raises(ValueError):
                recorder.to_rdf_updates(db, '   ', 2)
            with pytest.raises(TypeError):
                recorder.to_rdf_updates(db, None, 2)  # type: ignore[arg-type]
            with pytest.raises(PyPowsyblError, match='not greater than the head version 1'):
                recorder.to_rdf_updates(db, scenario, 1, clear=False)
            recorder.to_rdf_updates(db, scenario, 2)

        # A sender that stayed behind is refused: the head moved on
        stale = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        with stale.event_recorder() as recorder:
            _change_a_load(stale, 362.0)
            with pytest.raises(PyPowsyblError, match='successor|head|re-record'):
                recorder.to_rdf_updates(db, scenario, 3)


def test_load_and_update_errors(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)

        with pytest.raises(PyPowsyblError, match='holds no snapshot'):
            pp.network.from_rdf_db(db, scenario, 9)
        with pytest.raises(PyPowsyblError):
            pp.network.from_rdf_db(db, scenario, 1, None, 'http://nobody/CGMES')
        with pytest.raises(ValueError):
            network.update_from_rdf_db(db, scenario, max_diff_chain=0)
        with pytest.raises(TypeError):
            network.update_from_rdf_db(db, scenario, 1, subsets=['SSH'])  # type: ignore[call-arg]
        with pytest.raises(TypeError):
            pp.network.from_rdf_db(db, scenario, 1, 42)  # type: ignore[arg-type]

    with pytest.raises(PyPowsyblError, match='closed'):
        db.snapshots(scenario)

    # Nothing is listening there; the connection says so at once rather than on the first query
    with pytest.raises(PyPowsyblError, match='Cannot reach'):
        pp.network.connect('http://127.0.0.1:1/ds')


def test_identity(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        identity = network.rdf_db_identity()
        assert identity['scenario'] == scenario
        assert 'SSH' in identity

        from_files = pp.network.load(CGMES_ZIP, PARAMS)
        file_identity = from_files.rdf_db_identity()
        assert 'scenario' not in file_identity and 'SSH' in file_identity
        resolved = from_files.rdf_db_identity(db, scenario)
        assert resolved['version'] == '1' and resolved['scenario'] == scenario
        assert resolved['modelling_authority'] == AUTHORITY and resolved['timestamp'] == '2021-02-09T19:30:00Z'
        with pytest.raises(ValueError):
            from_files.rdf_db_identity(db)

        _record(db, network, scenario, None, None, 371.0)
        snapshots = db.snapshots(scenario)
        assert snapshots.loc[network.rdf_db_identity()['snapshot'], 'version'] == 2


def test_report_node_carries_the_route(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        _record(db, sender, scenario, 2, None, 381.0)
        receiver = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        report = pp.report.ReportNode()
        assert receiver.update_from_rdf_db(db, scenario, 2, report_node=report) == 'diff'
        assert str(report), 'the update should have reported the route it took'


_CHANGE_KINDS = ['switch', 'load', 'generator', 'ratio_tap_changer', 'phase_tap_changer', 'shunt', 'all_five']


def _apply_change(network: pp.network.Network, kind: str) -> None:
    """One change of each kind the recorder can export, so that each travels through the database once."""
    if kind == 'switch':
        network.update_switches(id=first_id(network.get_switches()), open=True)
    elif kind == 'load':
        network.update_loads(id=first_id(network.get_loads()), p0=11.0, q0=3.0)
    elif kind == 'generator':
        network.update_generators(id=first_id(network.get_generators()), target_p=42.0)
    elif kind == 'ratio_tap_changer':
        rtc = network.get_ratio_tap_changers()
        network.update_ratio_tap_changers(id=first_id(rtc), tap=other_tap(rtc.loc[first_id(rtc)]))
    elif kind == 'phase_tap_changer':
        ptc = network.get_phase_tap_changers()
        network.update_phase_tap_changers(id=first_id(ptc), tap=other_tap(ptc.loc[first_id(ptc)]))
    elif kind == 'shunt':
        shunts = network.get_shunt_compensators()
        current = int(shunts.loc[first_id(shunts), 'section_count'])
        network.update_shunt_compensators(id=first_id(shunts), section_count=0 if current else 1)
    else:
        apply_five_changes(network)


@pytest.mark.parametrize('kind', _CHANGE_KINDS)
def test_every_change_kind_travels_through_the_database(rdf_db_url: str, scenario: str, kind: str) -> None:
    """Whatever the recorder can export reaches a second network unchanged, one version per kind."""
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        receiver = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)

        with sender.event_recorder() as recorder:
            _apply_change(sender, kind)
            ids = recorder.to_rdf_updates(db, scenario, 2)

        assert ids, f'the {kind} change should have been stored'
        assert set(ids).issubset(set(db.models(scenario).index))
        assert len(recorder) == 0, 'a successful export clears the recorder by default'
        assert receiver.update_from_rdf_db(db, scenario, 2) == 'diff'
        assert_same_setpoints(sender, receiver)


def test_clear_false_keeps_the_events(rdf_db_url: str, scenario: str) -> None:
    """Documented behaviour: without ``clear`` the same events can be stored again as another version."""
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        with sender.event_recorder() as recorder:
            _change_a_load(sender, 391.0)
            first = recorder.to_rdf_updates(db, scenario, clear=False)
            assert len(recorder) >= 1, 'clear=False keeps what was recorded'
            second = recorder.to_rdf_updates(db, scenario, clear=False)

        assert first != second, 'the second write is another version, with models of its own'
        versions = db.snapshots(scenario)['version']
        assert sorted(versions) == [1, 2, 3], 'no version given: the head plus one, each time'
        receiver = pp.network.from_rdf_db(db, scenario, 3, parameters=PARAMS)
        assert_same_setpoints(sender, receiver)


def test_two_connections_share_one_memory_store(scenario: str) -> None:
    """Two ``RdfDatabase`` objects on one ``memory:`` name are two views of the same store."""
    name = f'memory:{uuid4().hex}'
    with pp.network.connect(name) as writer, pp.network.connect(name) as reader:
        writer.load_cgmes(CGMES_ZIP, scenario, 1, modelling_authority=AUTHORITY, parameters=PARAMS)
        assert scenario in reader.scenarios().index
        assert len(reader.snapshots(scenario)) == 1
        assert reader.versioned(scenario)
        network = pp.network.from_rdf_db(reader, scenario, 1, parameters=PARAMS)

        with network.event_recorder() as recorder:
            _change_a_load(network, 401.0)
            recorder.to_rdf_updates(writer, scenario, 2)
        assert sorted(reader.snapshots(scenario)['version']) == [1, 2]


def test_an_unversioned_scenario_answers_update(rdf_db_url: str, scenario: str) -> None:
    """The fourth answer: naming an un-versioned scenario addresses no snapshot, so the profiles are replaced."""
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        assert not db.versioned(scenario)
        network = pp.network.from_rdf_db(db, scenario, parameters=PARAMS)
        handle = network._handle  # pylint: disable=protected-access

        assert network.update_from_rdf_db(db, scenario) == 'update'
        assert network.update_from_rdf_db(db, scenario, profiles=['SSH']) == 'update'
        assert network._handle is handle, 'the profile replacement is in place'  # pylint: disable=protected-access
