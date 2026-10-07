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

**The address** is ``(scenario, version, timestamp, modelling_authority)``: a version name, a timezone-aware
datetime and the modelling authority the snapshot is stored under. ``CGMES_Full.zip`` states one per profile, so
the first root of a scenario names :data:`AUTHORITY`; after that an open authority (``None``) resolves to the only
tree of the scenario, for a read and a write alike.
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
from test_rdf_db import assert_same_network

PARAMS = {'iidm.import.cgmes.create-cgmes-export-mapping': 'true'}


def _root(db: pp.network.RdfDatabase, scenario: str) -> pp.network.Network:
    """Store the base grid model as the root of a scenario and return the network of that root."""
    ids = db.load_cgmes(CGMES_ZIP, scenario, '1', modelling_authority=AUTHORITY, parameters=PARAMS)
    assert len(ids) >= 4, f'the root of {scenario} should hold one model per instance file, got {ids}'
    return pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)


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


def _record(db: pp.network.RdfDatabase, network: pp.network.Network, scenario: str, version: Optional[str],
            timestamp: Optional[datetime], value: float) -> Tuple[str, list]:
    """Change one load on ``network`` and store the change as the snapshot ``(scenario, version, timestamp)``."""
    with network.event_recorder() as recorder:
        load = _change_a_load(network, value)
        ids = recorder.to_rdf_updates(db, scenario, version, timestamp)
    return load, ids


_SNAPSHOT_COLUMNS = ['scenario', 'modelling_authority', 'timestamp', 'version', 'rank', 'profiles', 'kind', 'parent',
                     'edge', 'depth', 'has_full', 'fast', 'rollover', 'members', 'created', 'description']


def other_tso_zip(suffix: str) -> io.BytesIO:
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


def _authored_by(authority: str, suffix: str) -> io.BytesIO:
    """:func:`other_tso_zip` with every instance file stating ``authority``: another TSO's files that agree on it."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(other_tso_zip(suffix)) as source, \
            zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if '_BD_' not in entry:
                content = re.sub(r'(<md:Model\.modelingAuthoritySet>)[^<]*(</md:Model\.modelingAuthoritySet>)',
                                 r'\g<1>' + authority + r'\g<2>', content)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


def _state_variables_only(suffix: str) -> io.BytesIO:
    """The state variables file of ``CGMES_Full.zip`` alone, under a model identifier of its own."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            if entry.endswith('_SV.xml'):
                content = source.read(entry).decode('utf-8')
                target.writestr(entry, re.sub(r'(<md:FullModel[^>]*rdf:about=")([^"]*)(")',
                                              r'\g<1>\g<2>' + suffix + r'\g<3>', content))
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
            ids = recorder.to_rdf_updates(db, scenario, '1', datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc))
        assert ids, 'the export should have stored at least the SSH difference'
        assert len(recorder) == 0, 'a successful export clears the recorder by default'

        # A second reader sees exactly the sender's state at that address - however the moment is written
        reader = pp.network.from_rdf_db(db, scenario, '1', datetime(2021, 2, 9, 21, 30,
                                                                  tzinfo=timezone(timedelta(hours=1))),
                                        parameters=PARAMS)
        assert_same_setpoints(network, reader)
        assert reader.get_loads().loc[load, 'p0'] == pytest.approx(321.0)

        # Two more versions of the same timestamp, from the same sender
        _record(db, network, scenario, '2', at('20:30'), 322.0)
        _record(db, network, scenario, '3', at('20:30'), 323.0)

        network2 = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        assert network2.update_from_rdf_db(db, scenario, '3', at('20:30')) == 'diff'
        assert_same_setpoints(network, network2)

        identity = network2.rdf_db_identity()
        assert identity['scenario'] == scenario
        assert identity['modelling_authority'] == AUTHORITY
        assert identity['timestamp'] == '2021-02-09T20:30:00Z'
        snapshots = db.snapshots(scenario)
        assert snapshots.loc[identity['snapshot'], 'version'] == '3'


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
        assert sorted(snapshots['version']) == ['1', '1', '2'], 'a new timestamp starts its chain at 1'
        assert sorted(snapshots['kind']) == ['diff', 'diff', 'full']
        assert sorted(snapshots['timestamp'].unique()) == [BASE, at('20:30')]
        assert all({'EQ', 'SSH'} <= set(profiles.split(';')) for profiles in snapshots['profiles'])

        timestamps = db.timestamps(scenario)
        assert timestamps.index.name == 'timestamp'
        assert list(timestamps.columns) == ['modelling_authority', 'root', 'head', 'version_count', 'pin']
        assert list(timestamps.index) == [BASE, at('20:30')]
        assert sorted(timestamps['version_count']) == [1, 2]
        assert timestamps.loc[BASE, 'pin'] == ''
        assert timestamps.loc[at('20:30'), 'pin'] == timestamps.loc[BASE, 'head'], 'the sender was at the root'
        assert snapshots['rollover'].dtype == bool and snapshots['rollover'].tolist().count(True) == 1

        versions = db.versions(scenario, at('20:30'))
        assert versions['version'].tolist() == ['1', '2']
        assert versions['rank'].tolist() == [10, 20]
        assert db.versions(scenario)['version'].tolist() == ['1']
        assert db.versions(scenario, at('20:30'), AUTHORITY)['version'].tolist() == ['1', '2']
        assert db.modelling_authorities(scenario) == [AUTHORITY]

        models = db.models(scenario)
        assert models.index.name == 'id'
        assert models['scenario'].unique().tolist() == [scenario]
        diffs = models[models['kind'] == 'diff']
        assert not diffs.empty and bool(diffs['fast'].all()), 'an SSH-only change is a fast-route difference'
        # the capability version is read from a resource of the core library; the native image must carry it, or
        # every difference reads 'unknown' and every reader re-checks it
        assert diffs['capabilities'].str.fullmatch(r'[0-9a-f]{12}/\d+\.\d+\.\d+(-\w+)?').all(), \
            diffs['capabilities'].tolist()
        assert (models[models['kind'] == 'full']['capabilities'] == '').all()

        scenarios = db.scenarios()
        assert scenarios.index.name == 'scenario'
        assert list(scenarios.columns) == ['modelling_authorities', 'versioned', 'snapshot_count', 'archive_cutoff',
                                           'archive_location']
        assert pd.isna(scenarios.loc[scenario, 'archive_cutoff']) and scenarios.loc[scenario, 'archive_location'] == ''
        assert scenarios.loc[scenario, 'modelling_authorities'] == AUTHORITY
        assert bool(scenarios.loc[scenario, 'versioned'])
        assert int(scenarios.loc[scenario, 'snapshot_count']) == 3


def test_snapshots_dataframe_types(rdf_db_url: str, scenario: str) -> None:
    """
    The address columns carry Python types, empty or not: an aware UTC timestamp, the version name as a str and the
    rank the registry gives it as an int64.
    """
    with pp.network.connect(rdf_db_url) as db:
        empty = db.snapshots(scenario)
        assert str(empty['timestamp'].dt.tz) == 'UTC'
        assert empty['version'].dtype == object and empty['rank'].dtype == 'int64'
        network = _root(db, scenario)
        _record(db, network, scenario, '5', at('20:30'), 303.0)
        for frame in (db.snapshots(scenario), db.versions(scenario, at('20:30'))):
            assert str(frame['timestamp'].dt.tz) == 'UTC'
            assert frame['version'].dtype == object and frame['rank'].dtype == 'int64'
        assembly = db.assembly(scenario, BASE)
        assert str(assembly['timestamp'].dt.tz) == 'UTC'
        assert assembly['version'].tolist() == ['1'] and assembly['rank'].tolist() == [10]
        assert str(db.timestamps(scenario).index.tz) == 'UTC'
        snapshots = db.snapshots(scenario).set_index('version')
        assert snapshots.loc['5', 'rank'] == 20, 'the permissive registry appended the unregistered name on top'


def test_naive_datetime_is_refused(rdf_db_url: str, scenario: str) -> None:
    """A naive datetime names no instant; every call refuses it before anything reaches the database."""
    naive = datetime(2021, 2, 9, 20, 30)
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        calls = [
            lambda: pp.network.from_rdf_db(db, scenario, '1', naive),
            lambda: pp.network.from_rdf_db(db, scenario, timestamps=[naive]),
            lambda: network.update_from_rdf_db(db, scenario, '1', naive),
            lambda: db.load_cgmes(CGMES_ZIP, scenario, '2', naive, modelling_authority=AUTHORITY),
            lambda: db.versions(scenario, naive),
            lambda: db.checkpoint(scenario, '1', naive),
            lambda: db.assembly(scenario, naive),
            lambda: pp.network.from_rdf_db(db, scenario, '1', '2021-02-09T20:30:00Z'),  # type: ignore[arg-type]
        ]
        for call in calls:
            with pytest.raises(TypeError, match='timezone-aware'):
                call()
        with network.event_recorder() as recorder:
            _change_a_load(network, 304.0)
            with pytest.raises(TypeError, match='timezone-aware'):
                recorder.to_rdf_updates(db, scenario, '2', naive)
        assert len(db.snapshots(scenario)) == 1, 'nothing was written'


def test_version_is_a_name(rdf_db_url: str, scenario: str) -> None:
    """A version is a name: an int is refused by name, before anything reaches the database."""
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        with pytest.raises(TypeError, match="version is a name: pass a str, for instance '1'"):
            pp.network.from_rdf_db(db, scenario, 1)  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='version is a name'):
            pp.network.from_rdf_db(db, scenario, True)  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='version is a name'):
            db.checkpoint(scenario, 1.0)  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='version is a name'):
            db.load_cgmes(CGMES_ZIP, scenario, 2)  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='version is a name'):
            network.update_from_rdf_db(db, scenario, 1)  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='version is a name'):
            pp.network.from_rdf_db(db, scenario, variants={'a': (1, BASE, None)})  # type: ignore[dict-item]
        with network.event_recorder() as recorder:
            _change_a_load(network, 305.0)
            with pytest.raises(TypeError, match='version is a name'):
                recorder.to_rdf_updates(db, scenario, 2)  # type: ignore[arg-type]
        with pytest.raises(ValueError, match='must not be blank'):
            pp.network.from_rdf_db(db, scenario, ' ')
        with pytest.raises(ValueError, match='give a version'):
            pp.network.from_rdf_db(db, scenario, exact=True)
        with pytest.raises(ValueError, match='give a version'):
            network.update_from_rdf_db(db, scenario, exact=True)
        assert len(db.snapshots(scenario)) == 1, 'nothing was written'


def test_registry_round_trip(rdf_db_url: str, scenario: str) -> None:
    """
    The version registry of a scenario: created strict before the first root, a name it does not hold refused, the
    order guarded once a chain exists, a transient name deleted together with its snapshots.
    """
    with pp.network.connect(rdf_db_url) as db:
        registry = db.registry(scenario)
        assert registry.names == [] and registry.permissive, 'no registry yet: the first root makes a permissive one'
        registry.create(['DA', 'ID'])
        assert not registry.permissive and registry.names == ['DA', 'ID']
        with pytest.raises(PyPowsyblError, match=f"version 'RT' is not registered in scenario '{scenario}'"):
            db.load_cgmes(CGMES_ZIP, scenario, 'RT', modelling_authority=AUTHORITY, parameters=PARAMS)
        assert not db.versioned(scenario), 'nothing was written'

        db.load_cgmes(CGMES_ZIP, scenario, 'DA', modelling_authority=AUTHORITY, parameters=PARAMS)
        assert registry.add('RT') == 30
        network = pp.network.from_rdf_db(db, scenario, 'DA', parameters=PARAMS)
        _record(db, network, scenario, 'RT', None, 501.0)
        with pytest.raises(PyPowsyblError, match="would put version 'RT'"):
            registry.rerank({'RT': 5})
        registry.rerank({'ID': 25})
        assert registry.insert('ID2', after='DA') == 17
        registry.rename('ID2', 'IDA')
        with pytest.raises(PyPowsyblError, match='cannot be renamed'):
            registry.rename('DA', 'D1')

        frame = registry.dataframe()
        assert frame.index.name == 'name' and list(frame.columns) == ['rank', 'transient']
        assert frame['rank'].to_dict() == {'DA': 10, 'IDA': 17, 'ID': 25, 'RT': 30}
        assert registry.rank('RT') == 30 and registry.rank('nope') is None

        # a transient name goes together with the snapshots that carry it, each one nothing was built on
        assert registry.add('SCRATCH') == 40
        registry.mark_transient('SCRATCH')
        assert bool(registry.dataframe().loc['SCRATCH', 'transient'])
        _record(db, network, scenario, 'SCRATCH', at('20:30'), 502.0)
        assert at('20:30') in db.timestamps(scenario).index
        registry.delete('SCRATCH')
        assert registry.names == ['DA', 'IDA', 'ID', 'RT']
        assert at('20:30') not in db.timestamps(scenario).index, 'its only snapshot was dropped with it'
        with pytest.raises(PyPowsyblError, match='cannot be deleted'):
            registry.delete('DA')
        with pytest.raises(TypeError, match='not one string'):
            registry.create('DA')


def test_latest_at_or_below_and_exact(rdf_db_url: str, scenario: str) -> None:
    """A read at a name takes the highest ranking version at or below it; ``exact=True`` that version or nothing."""
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        registry = db.registry(scenario)
        for name in ('DA', 'ID', 'RT'):
            registry.add(name)
        load, _ = _record(db, network, scenario, 'DA', at('20:30'), 601.0)
        _record(db, network, scenario, 'ID', at('20:30'), 602.0)

        def p0(reader: pp.network.Network) -> float:
            return float(reader.get_loads().loc[load, 'p0'])

        assert p0(pp.network.from_rdf_db(db, scenario, 'RT', at('20:30'), parameters=PARAMS)) == pytest.approx(602.0)
        assert p0(pp.network.from_rdf_db(db, scenario, 'DA', at('20:30'), exact=True,
                                         parameters=PARAMS)) == pytest.approx(601.0)
        with pytest.raises(PyPowsyblError, match=re.escape(f'{at("20:30").isoformat().replace("+00:00", "Z")}, =RT)')):
            pp.network.from_rdf_db(db, scenario, 'RT', at('20:30'), exact=True, parameters=PARAMS)

        reader = pp.network.from_rdf_db(db, scenario, 'DA', at('20:30'), exact=True, parameters=PARAMS)
        assert reader.update_from_rdf_db(db, scenario, 'RT', at('20:30')) == 'diff'
        assert reader.rdf_db_identity()['version'] == 'ID'
        assert reader.update_from_rdf_db(db, scenario, 'DA', at('20:30'), exact=True) == 'diff'
        assert p0(reader) == pytest.approx(601.0)

        assembly = db.assembly(scenario, at('20:30'), 'RT')
        assert assembly['version'].tolist() == ['ID'] and assembly['rank'].tolist() == [30]
        day = pp.network.from_rdf_db(db, scenario, 'RT', timestamps=[at('20:30')], parameters=PARAMS)
        assert day.variants_binding().loc['2021-02-09T20:30:00Z', 'version'] == 'ID'
        with pytest.raises(PyPowsyblError, match='=RT'):
            pp.network.from_rdf_db(db, scenario, 'RT', timestamps=[at('20:30')], exact=True, parameters=PARAMS)


def test_profiles_are_names(rdf_db_url: str, scenario: str) -> None:
    """
    ``profiles`` takes names: the nine of :data:`pypowsybl.network.PROFILES` and custom ones of the same shape; a
    name of another shape is refused by name.
    """
    assert pp.network.PROFILES == ('EQ', 'SSH', 'TP', 'SV', 'DY', 'DL', 'GL', 'EQ_BD', 'TP_BD')
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        with pytest.raises(ValueError, match="'x' is not a profile name"):
            pp.network.from_rdf_db(db, scenario, '1', profiles=['EQ', 'x'])
        with pytest.raises(ValueError, match="'1X' is not a profile name"):
            network.update_from_rdf_db(db, scenario, '1', profiles=['1X'])
        with pytest.raises(TypeError, match='not one string'):
            pp.network.from_rdf_db(db, scenario, '1', profiles='SSH')  # type: ignore[arg-type]
        with pytest.raises(TypeError, match='a profile is a name'):
            pp.network.from_rdf_db(db, scenario, '1', profiles=[1])  # type: ignore[list-item]
        # a projection that names the default changes nothing; a well-formed name the snapshot lacks is refused by
        # the database, naming what it holds
        assert network.update_from_rdf_db(db, scenario, '1', profiles=['EQ', 'SSH']) == 'noop'
        with pytest.raises(PyPowsyblError, match=re.escape('holds no [OP]; it holds [EQ, EQ_BD, SSH, SV, TP]')):
            pp.network.from_rdf_db(db, scenario, '1', profiles=['EQ', 'SSH', 'TP', 'SV', 'OP'], parameters=PARAMS)


_CFG_NS = 'http://example.org/Configuration/1#'


def _with_custom_profile(source: io.BytesIO, model_id: str, value: str) -> io.BytesIO:
    """A zip of instance files plus ``cgmes_CFG.xml``: a custom profile ``CFG``, three statements on two settings."""
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#"
         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:cfg="{_CFG_NS}">
  <md:FullModel rdf:about="{model_id}">
    <md:Model.scenarioTime>2021-02-09T19:30:00Z</md:Model.scenarioTime>
    <md:Model.created>2021-02-09T09:00:00Z</md:Model.created>
    <md:Model.version>1</md:Model.version>
    <md:Model.profile>http://example.org/Configuration/1</md:Model.profile>
    <md:Model.modelingAuthoritySet>{AUTHORITY}</md:Model.modelingAuthoritySet>
  </md:FullModel>
  <cfg:Setting rdf:about="http://example.org/cfg/setting-1">
    <cfg:Setting.name>ramp limit</cfg:Setting.name>
    <cfg:Setting.value>{value}</cfg:Setting.value>
  </cfg:Setting>
  <cfg:Setting rdf:about="http://example.org/cfg/setting-2">
    <cfg:Setting.next rdf:resource="http://example.org/cfg/setting-1"/>
  </cfg:Setting>
</rdf:RDF>
"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in archive.namelist():
            target.writestr(entry, archive.read(entry))
        target.writestr('cgmes_CFG.xml', xml)
    buffer.seek(0)
    return buffer


def test_custom_profile_round_trip(rdf_db_url: str, scenario: str) -> None:
    """
    A custom profile is stored whole next to the grid model, never reaches the network, and comes back from the
    database as a dataframe of statements; a later timestamp that names it stores its new file whole.
    """
    with open(CGMES_ZIP, 'rb') as files:
        root = _with_custom_profile(io.BytesIO(files.read()), 'urn:uuid:cfg-1-' + scenario, '120')
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes_from_binary_buffers([root], scenario, '1', modelling_authority=AUTHORITY, parameters=PARAMS)
        graphs = db.profiles(scenario)
        assert 'CFG' in graphs
        statements = db.fetch_profile(scenario, graphs['CFG'])
        assert list(statements.columns) == ['subject', 'predicate', 'object', 'is_iri']
        assert set(statements['subject']) == {'urn:uuid:cfg-1-' + scenario, 'http://example.org/cfg/setting-1',
                                              'http://example.org/cfg/setting-2'}, 'the header travels with the graph'
        value = statements[statements['predicate'] == _CFG_NS + 'Setting.value']
        assert value['subject'].tolist() == ['http://example.org/cfg/setting-1']
        assert value['object'].tolist() == ['120'] and not bool(value['is_iri'].iloc[0])
        assert bool(statements[statements['predicate'] == _CFG_NS + 'Setting.next']['is_iri'].iloc[0])
        assert 'CFG' in db.snapshots(scenario)['profiles'].iloc[0].split(';')

        network = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        assert_same_network(pp.network.load(CGMES_ZIP, PARAMS), network, rdf_db_url)

        later = _with_custom_profile(ssh_variant(1, at('20:00'), suffix=scenario), 'urn:uuid:cfg-2-' + scenario,
                                     '130')
        db.load_cgmes_from_binary_buffers([later], scenario, None, at('20:00'), profiles=['EQ', 'SSH', 'CFG'],
                                          parameters=PARAMS)
        moved = db.profiles(scenario, None, at('20:00'))['CFG']
        assert moved != graphs['CFG']
        assert '130' in db.fetch_profile(scenario, moved)['object'].tolist()
        assert db.fetch_profile(scenario, graphs['CFG'])['object'].tolist().count('120') == 1, 'the root keeps its own'
        with pytest.raises(ValueError, match='a graph IRI is required'):
            db.fetch_profile(scenario, ' ')

        with network.event_recorder() as recorder:
            _change_a_load(network, 503.0)
            with pytest.raises(PyPowsyblError, match="'CFG' is a custom one"):
                recorder.to_rdf_updates(db, scenario, None, at('20:30'), profiles=['CFG'])


def test_files_of_several_authorities_need_one_named(rdf_db_url: str, scenario: str) -> None:
    """
    ``CGMES_Full.zip`` states one modelling authority per profile, and a snapshot is stored under exactly one: the
    equipment and steady state hypothesis files disagree, so a write that leaves the authority open where there is
    no single tree to write into - the first root of a scenario, or a scenario of two trees - is refused with the
    authority of every profile, and nothing is stored. Naming one stores the files under it.
    """
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        with pytest.raises(PyPowsyblError, match='state the modelling authorities') as root:
            db.load_cgmes(CGMES_ZIP, scenario, '1', parameters=PARAMS)
        assert not db.versioned(scenario) and db.modelling_authorities(scenario) == []

        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([other_tso_zip('-' + scenario)], scenario, '1', None, other,
                                          parameters=PARAMS)
        with pytest.raises(PyPowsyblError, match='state the modelling authorities') as further:
            db.load_cgmes_from_binary_buffers([ssh_variant(1, at('20:00'), suffix=scenario)], scenario, None,
                                              at('20:00'), parameters=PARAMS)
        assert db.modelling_authorities(scenario) == [AUTHORITY, other]
        assert db.timestamps(scenario, AUTHORITY).index.tolist() == [BASE], 'the refused timestamp is not stored'

    for refusal in (root, further):
        message = str(refusal.value)
        for stated in ('EQ=powsybl.org', f'SSH={AUTHORITY}', 'SV=http://tennet.nl/CGMES',
                       'do not agree on one: pass the modelling authority in the address'):
            assert stated in message, f'{stated!r} is missing from: {message}'


def test_a_write_into_a_scenario_of_one_tree_goes_into_that_tree(rdf_db_url: str, scenario: str) -> None:
    """
    Once a scenario holds one tree, a write that leaves the authority open goes into it, as a read does - whatever
    the files' headers state (an ingestion), whether the network is at a snapshot of the database or not (a
    recorder), and for a checkpoint.
    """
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([ssh_variant(1, at('20:00'), suffix=scenario)], scenario, None,
                                          at('20:00'), parameters=PARAMS)
        from_files = pp.network.load(CGMES_ZIP, PARAMS)
        with from_files.event_recorder() as recorder:
            load = _change_a_load(from_files, 333.0)
            assert recorder.to_rdf_updates(db, scenario, None, at('20:30'))
        iri = db.checkpoint(scenario, None, at('20:30'))

        snapshots = db.snapshots(scenario)
        assert snapshots['modelling_authority'].tolist() == [AUTHORITY] * 3
        assert bool(snapshots.loc[iri, 'has_full'])
        assert db.timestamps(scenario).index.tolist() == [BASE, at('20:00'), at('20:30')]
        reader = pp.network.from_rdf_db(db, scenario, None, at('20:30'), parameters=PARAMS)
        assert reader.get_loads().loc[load, 'p0'] == pytest.approx(333.0)


def test_files_agreeing_on_another_authority_are_refused_by_a_scenario_of_one_tree(rdf_db_url: str,
                                                                                    scenario: str) -> None:
    """
    An open authority on a write into a scenario of one tree is that tree - unless the files' equipment and steady
    state hypothesis agree on another authority: those are another TSO's files, refused with both ways out, and
    nothing is stored until the address names one of them.
    """
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        before = len(db.snapshots(scenario))
        refusal = (f"state modelling authority {other} but the scenario's only tree is {AUTHORITY}: pass {AUTHORITY}"
                   f" in the address to store them under it, or {other} to open a second tree")
        for version, timestamp in (('1', None), (None, at('20:00'))):
            with pytest.raises(PyPowsyblError, match=re.escape(refusal)):
                db.load_cgmes_from_binary_buffers([_authored_by(other, '-' + scenario)], scenario, version,
                                                  timestamp, parameters=PARAMS)
        assert len(db.snapshots(scenario)) == before, 'nothing is stored'

        db.load_cgmes_from_binary_buffers([_authored_by(other, '-' + scenario)], scenario, '1', None, other,
                                          parameters=PARAMS)
        assert sorted(set(db.snapshots(scenario)['modelling_authority'])) == sorted([AUTHORITY, other])


def test_a_projected_load_keeps_the_boundary(rdf_db_url: str, scenario: str) -> None:
    """``profiles`` naming every profile but the boundary loads the boundary all the same: the full network."""
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([other_tso_zip('-' + scenario)], scenario, '1', None, other,
                                          parameters=PARAMS)
        for authority in (AUTHORITY, other):
            full = pp.network.from_rdf_db(db, scenario, '1', None, authority, parameters=PARAMS)
            projected = pp.network.from_rdf_db(db, scenario, '1', None, authority, ['EQ', 'SSH', 'TP', 'SV'],
                                               parameters=PARAMS)
            assert_same_network(full, projected, rdf_db_url)


def test_a_set_without_equipment_or_steady_state_hypothesis_names_its_authority(rdf_db_url: str,
                                                                                 scenario: str) -> None:
    """
    Only the equipment and the steady state hypothesis decide an open modelling authority: a set of instance files
    with neither of the two is refused where the authority is not the scenario's only one, and nothing is stored.
    """
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([other_tso_zip('-' + scenario)], scenario, '1', None, other,
                                          parameters=PARAMS)
        before = len(db.snapshots(scenario))
        with pytest.raises(PyPowsyblError, match=re.escape(
                'but no equipment or steady state hypothesis member states one, and only those two decide the '
                'modelling authority of an address that names none: pass the modelling authority in the address')):
            db.load_cgmes_from_binary_buffers([_state_variables_only('-' + scenario)], scenario, None, at('20:00'),
                                              profiles=['SV'], parameters=PARAMS)
        assert len(db.snapshots(scenario)) == before, 'nothing is stored'


def test_two_modelling_authorities(rdf_db_url: str, scenario: str) -> None:
    """A second TSO's files in the same scenario: a tree of its own, the same boundary, and no more guessing."""
    other = 'http://tennet.nl/CGMES'
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        db.load_cgmes_from_binary_buffers([other_tso_zip('-' + scenario)], scenario, '1', None, other,
                                          parameters=PARAMS)
        assert db.modelling_authorities(scenario) == [AUTHORITY, other]
        assert db.scenarios().loc[scenario, 'modelling_authorities'] == f'{AUTHORITY};{other}'

        with pytest.raises(PyPowsyblError, match='cannot be left open'):
            pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        with pytest.raises(PyPowsyblError, match='cannot be left open'):
            db.timestamps(scenario)
        assert len(db.timestamps(scenario, other)) == 1

        named = pp.network.from_rdf_db(db, scenario, '1', None, AUTHORITY, parameters=PARAMS)
        assert named.rdf_db_identity()['modelling_authority'] == AUTHORITY
        # the second tree loads too, with the boundary the first one stored, and is the grid its files describe
        second = pp.network.from_rdf_db(db, scenario, '1', None, other, parameters=PARAMS)
        assert second.rdf_db_identity()['modelling_authority'] == other
        assert_same_network(pp.network.load_from_binary_buffer(other_tso_zip('-' + scenario), PARAMS), second,
                            rdf_db_url)

        assembly = db.assembly(scenario, BASE)
        assert assembly.index.name == 'modelling_authority'
        assert list(assembly.index) == [AUTHORITY, other]
        assert assembly['version'].tolist() == ['1', '1']
        missing = db.assembly(scenario, BASE, '2')
        assert list(missing.index) == [AUTHORITY, other], 'every authority of the scenario is a row'
        assert missing['rank'].isna().all()
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
        with pytest.raises(PyPowsyblError, match=re.escape(f"scenario '{missing}' holds no snapshot, so the modelling"
                                                           " authority cannot be left open")):
            pp.network.from_rdf_db(db, missing, '1')
        # Without a version the un-versioned route answers, and it says the scenario holds nothing
        with pytest.raises(PyPowsyblError, match='the scenario is empty'):
            pp.network.from_rdf_db(db, missing)


def test_routes_noop_diff_full(rdf_db_url: str, scenario: str) -> None:
    """The three routes of one scenario, including the fall back to the full grid model."""
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        _record(db, sender, scenario, '1', at('20:30'), 311.0)

        receiver = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        handle = receiver._handle  # pylint: disable=protected-access
        assert receiver.update_from_rdf_db(db, scenario, '1') == 'noop'
        assert receiver.update_from_rdf_db(db, scenario, '1', at('20:30')) == 'diff'
        assert receiver._handle is handle, 'the fast route applies in place'  # pylint: disable=protected-access
        assert_same_setpoints(sender, receiver)

        # Backwards is a difference too: the stored change is undone
        assert receiver.update_from_rdf_db(db, scenario, '1') == 'diff'
        assert_same_setpoints(pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS), receiver)

        # A timestamp whose equipment drifted cannot be applied in place, so the loader falls back to a reload -
        # inside the same scenario, and without anybody asking for it
        db.load_cgmes_from_binary_buffers([eq_drift(2, at('21:00'))], scenario, None, at('21:00'),
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        models = db.models(scenario)
        assert not bool(models[(models['kind'] == 'diff') & (models['subset'] == 'EQ')]['fast'].all()), \
            'an equipment rename is not a fast-route difference'

        assert receiver.update_from_rdf_db(db, scenario, '1', at('21:00')) == 'full'
        assert receiver._handle is not handle  # pylint: disable=protected-access
        assert drifted_name(2) in _all_names(receiver), 'the renamed equipment reached the reloaded network'
        assert receiver.case_date.strftime('%H:%M') == '21:00'


def test_multiple_scenarios(rdf_db_url: str, scenario: str, scenario2: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        monday = _root(db, scenario)
        db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, '1',
                                          modelling_authority=AUTHORITY, parameters=PARAMS)

        scenarios = db.scenarios()
        assert {scenario, scenario2}.issubset(set(scenarios.index))

        # A write into one day leaves the other alone
        before = db.snapshots(scenario2)
        _record(db, monday, scenario, '2', None, 331.0)
        pd.testing.assert_frame_equal(before, db.snapshots(scenario2))

        # The same wall time of two days is two instants: each scenario's base timestamp is its own day's
        assert db.timestamps(scenario).index[0] == BASE
        assert db.timestamps(scenario2).index[0] == at('19:30', NEXT_DAY)

        # Walking to another day is a full reload, decided without a query
        walker = pp.network.from_rdf_db(db, scenario, '2', parameters=PARAMS)
        handle = walker._handle  # pylint: disable=protected-access
        assert walker.update_from_rdf_db(db, scenario2, '1') == 'full'
        assert walker._handle is not handle  # pylint: disable=protected-access
        assert walker.rdf_db_identity()['scenario'] == scenario2

        # And a sender may not write its difference into the other day
        sender = pp.network.from_rdf_db(db, scenario, '2', parameters=PARAMS)
        with sender.event_recorder() as recorder:
            _change_a_load(sender, 341.0)
            with pytest.raises(PyPowsyblError, match='never cross scenarios'):
                recorder.to_rdf_updates(db, scenario2, '2')


@pytest.mark.parametrize('drift', ['eq_drift', 'other_scenario'])
def test_full_route_swaps_the_handle(rdf_db_url: str, scenario: str, scenario2: str, drift: str) -> None:
    """Both ways to reach the full route, and what it does to the Python object either way."""
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)
        if drift == 'eq_drift':
            db.load_cgmes_from_binary_buffers([eq_drift(3, at('21:00'))], scenario, '1', at('21:00'),
                                              modelling_authority=AUTHORITY, parameters=PARAMS)
            target = (scenario, '1', at('21:00'))
        else:
            db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, '1',
                                              modelling_authority=AUTHORITY, parameters=PARAMS)
            target = (scenario2, '1', None)

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
            sender.update_from_rdf_db(db, scenario, '1')
            _record(db, sender, scenario, None, moment, 400.0 + i)

        timestamps = db.timestamps(scenario)
        assert list(timestamps.index) == [BASE] + moments, 'timestamps come back in time order'

        walker = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
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
        db.load_cgmes_from_binary_buffers([next_day_zip()], scenario2, '1',
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        db.load_cgmes_from_binary_buffers([ssh_variant(9, after_midnight)], scenario2, None, after_midnight,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        assert walker.update_from_rdf_db(db, scenario2, '1') == 'full'
        assert walker.update_from_rdf_db(db, scenario2, '1', after_midnight) == 'diff'
        assert_same_setpoints(pp.network.from_rdf_db(db, scenario2, '1', after_midnight, parameters=PARAMS), walker)
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
        assert sorted(snapshots['version']) == ['1', '1', '1', '1']
        assert sorted(snapshots['kind']) == ['diff', 'diff', 'diff', 'full']

        # An ingested timestamp is read back like any other snapshot, and equals the file it came from
        for i, moment in enumerate(moments, start=1):
            from_db = pp.network.from_rdf_db(db, scenario, None, moment, parameters=PARAMS)
            from_file = pp.network.load_from_binary_buffer(ssh_variant(i, moment), PARAMS)
            pd.testing.assert_frame_equal(from_file.get_loads()[['p0', 'q0']].sort_index(),
                                          from_db.get_loads()[['p0', 'q0']].sort_index(),
                                          check_exact=False, rtol=1e-6)

        # And one network walks the whole day, every step a difference
        walker = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        for moment in moments:
            assert walker.update_from_rdf_db(db, scenario, None, moment) == 'diff'

        iri = db.checkpoint(scenario, None, moments[-1])
        assert bool(db.snapshots(scenario).loc[iri, 'has_full'])

        # Every timestamp hung off the root, the only rollover; one flagged later becomes the pin of the next ones
        root = timestamps.loc[BASE, 'head']
        assert db.timestamps(scenario)['pin'].tolist() == ['', root, root, root]
        rolled = db.rollover(scenario, None, moments[-1])
        later = at('20:45')
        db.load_cgmes_from_binary_buffers([ssh_variant(4, later)], scenario, None, later,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        assert db.timestamps(scenario).loc[later, 'pin'] == rolled
        assert bool(db.snapshots(scenario).loc[rolled, 'rollover'])

        # The un-versioned upload has no place in a versioned scenario and core says so
        with pytest.raises(PyPowsyblError, match='is versioned'):
            db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)


def test_export_errors(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)

        with network.event_recorder() as recorder:
            with pytest.raises(PyPowsyblError):
                recorder.to_rdf_updates(db, scenario, '2')

        with network.event_recorder() as recorder:
            _change_a_load(network, 361.0)
            with pytest.raises(PyPowsyblError, match='decided by the database'):
                recorder.to_rdf_updates(db, scenario, '2', supersedes='urn:uuid:nope')
            with pytest.raises(ValueError):
                recorder.to_rdf_updates(db, scenario, '2', unsupported='nope')
            with pytest.raises(ValueError):
                recorder.to_rdf_updates(db, '   ', 2)
            with pytest.raises(TypeError):
                recorder.to_rdf_updates(db, None, 2)  # type: ignore[arg-type]
            with pytest.raises(PyPowsyblError, match=re.escape("version '1' (rank 10) is not above the parent '1'")):
                recorder.to_rdf_updates(db, scenario, '1', clear=False)
            recorder.to_rdf_updates(db, scenario, '2')

        # A sender that stayed behind is refused: the head moved on
        stale = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        with stale.event_recorder() as recorder:
            _change_a_load(stale, 362.0)
            with pytest.raises(PyPowsyblError, match='successor|head|re-record'):
                recorder.to_rdf_updates(db, scenario, '3')


def test_load_and_update_errors(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        network = _root(db, scenario)

        # the stable part of the text: who refused, and the address in full; the listing that follows may change
        address = f'({scenario}, {AUTHORITY}, base, 9)'
        with pytest.raises(PyPowsyblError, match=re.escape(f"scenario '{scenario}' holds no snapshot {address}")):
            pp.network.from_rdf_db(db, scenario, '9')
        with pytest.raises(PyPowsyblError):
            pp.network.from_rdf_db(db, scenario, '1', None, 'http://nobody/CGMES')
        with pytest.raises(ValueError):
            network.update_from_rdf_db(db, scenario, max_diff_chain=0)
        with pytest.raises(TypeError):
            network.update_from_rdf_db(db, scenario, '1', subsets=['SSH'])  # type: ignore[call-arg]
        with pytest.raises(TypeError):
            pp.network.from_rdf_db(db, scenario, '1', 42)  # type: ignore[arg-type]

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
        assert snapshots.loc[network.rdf_db_identity()['snapshot'], 'version'] == '2'


def test_report_node_carries_the_route(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        sender = _root(db, scenario)
        _record(db, sender, scenario, '2', None, 381.0)
        receiver = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        report = pp.report.ReportNode()
        assert receiver.update_from_rdf_db(db, scenario, '2', report_node=report) == 'diff'
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
        receiver = pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)

        with sender.event_recorder() as recorder:
            _apply_change(sender, kind)
            ids = recorder.to_rdf_updates(db, scenario, '2')

        assert ids, f'the {kind} change should have been stored'
        assert set(ids).issubset(set(db.models(scenario).index))
        assert len(recorder) == 0, 'a successful export clears the recorder by default'
        assert receiver.update_from_rdf_db(db, scenario, '2') == 'diff'
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
        assert sorted(versions) == ['1', '2', '3'], 'no version given: the next number, each time'
        receiver = pp.network.from_rdf_db(db, scenario, '3', parameters=PARAMS)
        assert_same_setpoints(sender, receiver)


def test_two_connections_share_one_memory_store(scenario: str) -> None:
    """Two ``RdfDatabase`` objects on one ``memory:`` name are two views of the same store."""
    name = f'memory:{uuid4().hex}'
    with pp.network.connect(name) as writer, pp.network.connect(name) as reader:
        writer.load_cgmes(CGMES_ZIP, scenario, '1', modelling_authority=AUTHORITY, parameters=PARAMS)
        assert scenario in reader.scenarios().index
        assert len(reader.snapshots(scenario)) == 1
        assert reader.versioned(scenario)
        network = pp.network.from_rdf_db(reader, scenario, '1', parameters=PARAMS)

        with network.event_recorder() as recorder:
            _change_a_load(network, 401.0)
            recorder.to_rdf_updates(writer, scenario, '2')
        assert sorted(reader.snapshots(scenario)['version']) == ['1', '2']


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

def test_pin_and_rollover(rdf_db_url: str, scenario: str) -> None:
    """
    A new timestamp hangs off a pin: by default the latest rollover at or before it for files, the sender's own
    snapshot for a recorder; ``pin=`` names another one. A timestamp nothing is pinned to can be dropped.
    """
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        root = db.snapshots(scenario).index[0]
        assert bool(db.snapshots(scenario).loc[root, 'rollover']), 'a root is a rollover'
        db.load_cgmes_from_binary_buffers([ssh_variant(1, at('20:00'), suffix=scenario)], scenario, None,
                                          at('20:00'), parameters=PARAMS)
        rolled = db.rollover(scenario, None, at('20:00'))
        assert db.rollover(scenario, None, at('20:00')) == rolled, 'idempotent'
        flags = db.snapshots(scenario).loc[rolled]
        assert bool(flags['rollover']) and bool(flags['has_full']), 'a rollover is checkpointed at once'
        db.load_cgmes_from_binary_buffers([ssh_variant(2, at('20:15'), suffix=scenario)], scenario, None,
                                          at('20:15'), parameters=PARAMS)
        db.load_cgmes_from_binary_buffers([ssh_variant(3, at('20:30'), suffix=scenario)], scenario, None,
                                          at('20:30'), pin=(None, None, None), parameters=PARAMS)
        pins = db.timestamps(scenario)['pin']
        assert pins[at('20:15')] == rolled and pins[at('20:30')] == root and pins[BASE] == ''
        with pytest.raises(PyPowsyblError, match='already exists'):
            db.load_cgmes_from_binary_buffers([ssh_variant(5, at('20:30'), suffix=scenario + '-x')], scenario, None,
                                              at('20:30'), pin=BASE, parameters=PARAMS)

        sender = pp.network.from_rdf_db(db, scenario, None, at('20:15'), parameters=PARAMS)
        with sender.event_recorder() as recorder:
            _change_a_load(sender, 701.0)
            with pytest.raises(PyPowsyblError, match='pin'):
                recorder.to_rdf_updates(db, scenario, None, at('20:45'), pin=at('20:00'), clear=False)
            recorder.to_rdf_updates(db, scenario, None, at('20:45'), pin=(None, at('20:15'), AUTHORITY))
        assert db.timestamps(scenario).loc[at('20:45'), 'pin'] == db.timestamps(scenario).loc[at('20:15'), 'head']
        with pytest.raises(ValueError, match='takes no pin'):
            sender.event_recorder().to_rdf_updates(db, scenario, pin=BASE, per_variant=True)
        with pytest.raises(TypeError, match='pin is a timestamp or'):
            db.load_cgmes_from_binary_buffers([ssh_variant(6, at('21:00'))], scenario, None, at('21:00'),
                                              pin='20:00', parameters=PARAMS)  # type: ignore[arg-type]

        with pytest.raises(PyPowsyblError, match='is the pin of'):
            db.drop_timestamp(scenario, at('20:15'))
        with pytest.raises(PyPowsyblError, match='base timestamp'):
            db.drop_timestamp(scenario, BASE)
        with pytest.raises(TypeError, match='needs a timestamp'):
            db.drop_timestamp(scenario, None)  # type: ignore[arg-type]
        assert len(db.drop_timestamp(scenario, at('20:45'))) == 1
        assert len(db.drop_timestamp(scenario, at('20:15'))) == 1, 'nothing depends on it any more'
        assert at('20:15') not in db.timestamps(scenario).index


def test_changes_between(rdf_db_url: str, scenario: str) -> None:
    """The changes from one snapshot to another, one row per statement: what holds after, and what held before."""
    with pp.network.connect(rdf_db_url) as db:
        _root(db, scenario)
        for k, moment in ((1, at('20:00')), (2, at('20:15'))):
            db.load_cgmes_from_binary_buffers([ssh_variant(k, moment, suffix=scenario)], scenario, None, moment,
                                              parameters=PARAMS)
        changes = db.changes_between(scenario, at('20:00'), at('20:15'))
        assert list(changes.columns) == ['profile', 'subject', 'property', 'value', 'side']
        assert set(changes['profile']) == {'SSH'} and set(changes['side']) == {'forward', 'reverse'}
        for side, moment in (('forward', at('20:15')), ('reverse', at('20:00'))):
            loads = pp.network.from_rdf_db(db, scenario, None, moment, parameters=PARAMS).get_loads()
            rows = changes[(changes['side'] == side) & (changes['property'] == 'EnergyConsumer.p')]
            assert not rows.empty
            for subject, value in zip(rows['subject'], rows['value']):
                assert float(value) == pytest.approx(float(loads.loc[subject, 'p0']))
        assert db.changes_between(scenario, at('20:15'), at('20:15')).empty, 'the same snapshot changes nothing'
        backwards = db.changes_between(scenario, at('20:15'), None)
        assert not backwards.empty and set(backwards['side']) == {'forward', 'reverse'}


def test_archive_cutoff(rdf_db_url: str, scenario: str) -> None:
    """
    An archive cutoff refuses reads before it, naming where the states went; a network standing at an archived
    snapshot is reloaded rather than walked from; clearing the cutoff serves the states again.
    """
    location = 's3://archive/' + scenario
    with pp.network.connect(rdf_db_url) as db:
        at_base = _root(db, scenario)
        db.load_cgmes_from_binary_buffers([ssh_variant(1, at('20:00'), suffix=scenario)], scenario, None,
                                          at('20:00'), parameters=PARAMS)
        db.rollover(scenario, None, at('20:00'))
        db.load_cgmes_from_binary_buffers([ssh_variant(2, at('20:15'), suffix=scenario)], scenario, None,
                                          at('20:15'), parameters=PARAMS)
        assert db.archive_cutoff(scenario) is None

        db.set_archive_cutoff(scenario, at('20:00'), location)
        assert db.archive_cutoff(scenario) == (at('20:00'), location)
        assert db.scenarios().loc[scenario, 'archive_cutoff'] == at('20:00')
        assert db.scenarios().loc[scenario, 'archive_location'] == location
        with pytest.raises(PyPowsyblError, match=re.escape(f'is in the archive at {location}')):
            pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS)
        later = pp.network.from_rdf_db(db, scenario, None, at('20:15'), parameters=PARAMS)
        assert not later.get_loads().empty
        assert at_base.update_from_rdf_db(db, scenario, None, at('20:15')) == 'full', \
            'an archived snapshot is not walked from'
        with pytest.raises(PyPowsyblError, match='archive'):
            later.update_from_rdf_db(db, scenario, '1')
        assert len(db.snapshots(scenario)) == 3, 'listings still show archived snapshots'
        with pytest.raises(ValueError, match='archive location is required'):
            db.set_archive_cutoff(scenario, at('20:00'), ' ')

        db.clear_archive_cutoff(scenario)
        assert db.archive_cutoff(scenario) is None
        assert not pp.network.from_rdf_db(db, scenario, '1', parameters=PARAMS).get_loads().empty

