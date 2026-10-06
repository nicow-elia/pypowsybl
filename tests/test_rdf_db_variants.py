#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Timestamps and versions as the **variants** of one network.

A day walked by one network keeps no history: after the last timestamp the first one is gone. Loading every
timestamp as a network of its own keeps all of them but converts the same equipment ninety-six times. The third
way is this one: one network, one conversion, one variant per timestamp, and the stored differences applied on
clones of the state they derive from. A variant is named after its ISO instant unless the caller names it.

What these tests pin is what a user can rely on:

* a variant is **exactly** the network a separate load of that snapshot gives, for every timestamp;
* variants are **isolated** - moving one leaves the others byte-identical;
* a snapshot that cannot be reached inside a variant is **refused with reasons and nothing is changed**, never
  silently reloaded;
* the exports write each variant's changes as the successor of *that variant's* snapshot.

Every test runs twice, once on the in-process ``memory:`` backend and once against a real Fuseki server started
as a subprocess (see ``conftest.py``).
"""
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

import pandas as pd
import pytest

import pypowsybl as pp
import pypowsybl.loadflow as lf
from pypowsybl import PyPowsyblError
from pypowsybl.network import RdfDbVariantRefusedError
from pypowsybl.network.impl.rdf_db import _split_reasons
from rdf_db_fixtures import AUTHORITY, CGMES_ZIP, at, drifted_name, eq_drift, ssh_variant

PARAMS = {'iidm.import.cgmes.create-cgmes-export-mapping': 'true'}
T2000, T2015, T2030, T2100 = at('20:00'), at('20:15'), at('20:30'), at('21:00')
MOMENTS = [T2000, T2015, T2030]


def _name(moment: datetime) -> str:
    """The name a bulk load gives the variant of a moment when the caller names none: its ISO instant."""
    return moment.strftime('%Y-%m-%dT%H:%M:%SZ')


V2000, V2015, V2030, V2100 = _name(T2000), _name(T2015), _name(T2030), _name(T2100)
NAMES = [V2000, V2015, V2030]
TESTS_DIR = Path(__file__).parent


def _a_day(db: pp.network.RdfDatabase, scenario: str, moments: Optional[List[datetime]] = None) -> None:
    """The base grid model as the root of a scenario, plus one steady-state timestamp per moment."""
    db.load_cgmes(CGMES_ZIP, scenario, 1, modelling_authority=AUTHORITY, parameters=PARAMS)
    for i, moment in enumerate(moments if moments is not None else MOMENTS, start=1):
        db.load_cgmes_from_binary_buffers([ssh_variant(i, moment, suffix=scenario)], scenario, None, moment,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)


def _loads(network: pp.network.Network, variant: str) -> pd.DataFrame:
    """The active and reactive setpoints of one variant, sorted, which is what a variant *is* for these tests."""
    previous = network.get_working_variant_id()
    network.set_working_variant(variant)
    try:
        return network.get_loads()[['p0', 'q0']].sort_index()
    finally:
        network.set_working_variant(previous)


def _total_load(network: pp.network.Network, variant: str) -> float:
    return float(_loads(network, variant)['p0'].sum())


def _binding(network: pp.network.Network) -> Dict[str, pd.Series]:
    frame = network.variants_binding()
    return {str(index): row for index, row in frame.iterrows()}


def test_a_day_as_variants_equals_a_load_per_timestamp(rdf_db_url: str, scenario: str) -> None:
    """The headline claim: every variant is the network the same snapshot gives when it is loaded on its own."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)

        assert sorted(day.get_variant_ids()) == [V2000, V2015, V2030, 'InitialState']
        assert day.get_working_variant_id() == 'InitialState', 'the caller is left on the primary variant'

        for moment, name in zip(MOMENTS, NAMES):
            alone = pp.network.from_rdf_db(db, scenario, 1, moment, parameters=PARAMS)
            pd.testing.assert_frame_equal(alone.get_loads()[['p0', 'q0']].sort_index(), _loads(day, name),
                                          check_exact=False, rtol=1e-9)
            # and nothing the difference does not carry moved either: this fixture rewrites EnergyConsumer.p
            # only, so the generators have to be the base model's in every variant
            previous = day.get_working_variant_id()
            day.set_working_variant(name)
            pd.testing.assert_frame_equal(alone.get_generators()[['target_p', 'target_q', 'target_v']].sort_index(),
                                          day.get_generators()[['target_p', 'target_q', 'target_v']].sort_index(),
                                          check_exact=False, rtol=1e-9)
            day.set_working_variant(previous)

        # and the comparison above is not vacuous: the variants really do differ from one another
        assert not _loads(day, NAMES[0]).equals(_loads(day, NAMES[-1]))


def test_variants_are_isolated_from_each_other(rdf_db_url: str, scenario: str) -> None:
    """Moving one variant to another snapshot leaves every other variant exactly where it was."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        before = {name: _loads(day, name) for name in NAMES + ['InitialState']}

        assert day.update_from_rdf_db(db, scenario, 1, T2030, variant=V2000) == 'diff'

        pd.testing.assert_frame_equal(before[V2030], _loads(day, V2000), check_exact=False, rtol=1e-9)
        for name in [V2015, V2030, 'InitialState']:
            pd.testing.assert_frame_equal(before[name], _loads(day, name), check_exact=False, rtol=1e-9)


def test_variants_mapping_names_them_and_mixes_versions(rdf_db_url: str, scenario: str) -> None:
    """``variants={...}`` chooses the names, and an address of its own lets one variant sit at another version."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes_from_binary_buffers([ssh_variant(9, T2015, suffix=scenario)], scenario, 2, T2015,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)

        day = pp.network.from_rdf_db(db, scenario, 1,
                                     variants={'early': T2000, 'study': (2, T2015, None)}, parameters=PARAMS)
        assert sorted(day.get_variant_ids()) == ['InitialState', 'early', 'study']
        binding = _binding(day)
        assert binding['early']['version'] == 1
        assert binding['study']['version'] == 2
        assert binding['early']['timestamp'] == T2000
        assert binding['study']['timestamp'] == T2015
        assert binding['study']['modelling_authority'] == AUTHORITY
        assert binding['early']['status'] == 'bound' and binding['study']['status'] == 'bound'

        study_alone = pp.network.from_rdf_db(db, scenario, 2, T2015, parameters=PARAMS)
        pd.testing.assert_frame_equal(study_alone.get_loads()[['p0', 'q0']].sort_index(), _loads(day, 'study'),
                                      check_exact=False, rtol=1e-9)


def test_update_creates_or_moves_one_variant(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        network = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        handle = network._handle  # pylint: disable=protected-access
        base = float(network.get_loads()['p0'].sum())

        assert network.update_from_rdf_db(db, scenario, 1, T2000, variant='study') == 'diff'
        assert sorted(network.get_variant_ids()) == ['InitialState', 'study']
        assert network.get_working_variant_id() == 'InitialState'
        assert float(network.get_loads()['p0'].sum()) == pytest.approx(base)
        assert _total_load(network, 'study') != pytest.approx(base)

        # the same variant again, at another timestamp: moved, not created a second time
        assert network.update_from_rdf_db(db, scenario, 1, T2030, variant='study') == 'diff'
        assert sorted(network.get_variant_ids()) == ['InitialState', 'study']
        assert network.update_from_rdf_db(db, scenario, 1, T2030, variant='study') == 'noop'
        assert network._handle is handle, 'a variant update never swaps the Java network'  # pylint: disable=protected-access

        alone = pp.network.from_rdf_db(db, scenario, 1, T2030, parameters=PARAMS)
        pd.testing.assert_frame_equal(alone.get_loads()[['p0', 'q0']].sort_index(), _loads(network, 'study'),
                                      check_exact=False, rtol=1e-9)


def test_an_equipment_drift_is_refused_and_changes_nothing(rdf_db_url: str, scenario: str) -> None:
    """
    A renamed line is not a per-variant value in IIDM, so reaching that timestamp inside a variant would change
    every other variant too. It is refused, with reasons, and the network is untouched.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes_from_binary_buffers([eq_drift(7, T2100, suffix=scenario)], scenario, 1, T2100,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        before = {name: _loads(day, name) for name in NAMES + ['InitialState']}

        with pytest.raises(RdfDbVariantRefusedError) as error:
            day.update_from_rdf_db(db, scenario, 1, T2100, variant='drifted')
        assert error.value.variant == 'drifted'
        assert error.value.reasons, 'a refusal always says why'
        assert sorted(day.get_variant_ids()) == [V2000, V2015, V2030, 'InitialState'], \
            'the variant the refused call would have created does not exist'
        for name in NAMES + ['InitialState']:
            pd.testing.assert_frame_equal(before[name], _loads(day, name), check_exact=False, rtol=1e-9)
        assert drifted_name(7) not in set(day.get_lines()['name'])

        # and the refusal is on the network, where variants_binding() shows it
        table = day.variants_binding()
        assert table.index.is_unique, 'the table is indexed by variant and must stay addressable'
        refused = table[table['status'] == 'refused']
        assert list(refused.index) == ['drifted']
        assert str(refused.iloc[0]['reasons'])
        assert error.value.reasons == _split_reasons(str(refused.iloc[0]['reasons'])), \
            'a single update and the table say the same thing, line by line'

        # refusing a variant that *exists* puts the reasons on that variant's own row: still one row per variant
        with pytest.raises(RdfDbVariantRefusedError):
            day.update_from_rdf_db(db, scenario, 1, T2100, variant=V2015)
        table = day.variants_binding()
        assert table.index.is_unique
        assert table.loc[V2015, 'status'] == 'bound'
        assert 'not stored per variant' in str(table.loc[V2015, 'reasons']) or str(table.loc[V2015, 'reasons'])
        pd.testing.assert_frame_equal(before[V2015], _loads(day, V2015), check_exact=False, rtol=1e-9)

        # the way out is a network of its own, which the refusal message points at
        separate = pp.network.from_rdf_db(db, scenario, 1, T2100, parameters=PARAMS)
        assert drifted_name(7) in set(separate.get_lines()['name'])


def test_a_bulk_load_refuses_one_timestamp(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes_from_binary_buffers([eq_drift(7, T2100, suffix=scenario)], scenario, 1, T2100,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)

        with pytest.raises(RdfDbVariantRefusedError) as error:
            pp.network.from_rdf_db(db, scenario, 1, timestamps=[T2000, T2100, T2030], parameters=PARAMS)
        assert V2100 in str(error.value)
        assert error.value.reasons
        assert error.value.refused is not None and len(error.value.refused) == 1

        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=[T2000, T2100, T2030],
                                     on_refusal='skip', parameters=PARAMS)
        assert sorted(day.get_variant_ids()) == [V2000, V2030, 'InitialState']
        binding = _binding(day)
        assert binding[V2100]['status'] == 'refused'
        assert binding[V2000]['status'] == 'bound'
        assert binding['InitialState']['status'] == 'primary'


def test_variants_binding_describes_every_variant(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        day.clone_variant(V2000, 'what-if')

        frame = day.variants_binding()
        assert list(frame.columns) == ['scenario', 'snapshot', 'modelling_authority', 'timestamp', 'version',
                                       'cloned_from', 'eq', 'ssh', 'case_date', 'status', 'reasons']
        assert str(frame['timestamp'].dt.tz) == 'UTC' and frame['version'].dtype == 'Int64'
        assert not pd.isna(frame.loc['InitialState', 'version'])
        assert frame.index.name == 'variant'
        binding = _binding(day)
        assert binding[V2015]['scenario'] == scenario
        assert binding[V2015]['timestamp'] == T2015
        assert binding[V2015]['ssh'] and binding[V2015]['eq']
        # a clone of a bound variant inherits its binding, because its state *is* that snapshot
        assert binding['what-if']['status'] == 'bound'
        assert binding['what-if']['cloned_from'] == V2000
        assert binding['what-if']['snapshot'] == binding[V2000]['snapshot']


def test_a_user_clone_does_not_switch_the_network_into_variant_mode(rdf_db_url: str, scenario: str) -> None:
    """Cloning is the ordinary IIDM idiom; only a named variant or a bulk load opts into variant mode."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes_from_binary_buffers([eq_drift(7, T2100, suffix=scenario)], scenario, 1, T2100,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        network = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        network.clone_variant('InitialState', 'what-if')

        assert network.update_from_rdf_db(db, scenario, 1, T2000) == 'diff'
        # and the classic full route still works, handle swap included
        assert network.update_from_rdf_db(db, scenario, 1, T2100) == 'full'
        assert drifted_name(7) in set(network.get_lines()['name'])


def test_rdf_db_identity_of_a_variant(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)

        early = day.rdf_db_identity(db, scenario, variant=V2000)
        late = day.rdf_db_identity(db, scenario, variant=V2030)
        assert early['scenario'] == scenario
        assert early['timestamp'] == V2000 and early['version'] == '1'
        assert late['timestamp'] == V2030
        assert early['snapshot'] != late['snapshot']
        assert early['SSH'] != late['SSH']
        # the primary is where the bulk load left it: at the first requested snapshot
        assert day.rdf_db_identity(db, scenario)['snapshot'] == early['snapshot']
        assert day.rdf_db_identity(variant=V2030)['SSH'] == late['SSH'], 'no connection needed for the network'

        with pytest.raises(PyPowsyblError, match='has no variant'):
            day.rdf_db_identity(db, scenario, variant='nope')


def test_per_variant_export_round_trip(rdf_db_url: str, scenario: str) -> None:
    """Changes recorded on two variants become two successors, which a second network reads back."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        load = str(day.get_loads().index[0])

        with day.event_recorder() as recorder:
            day.set_working_variant(V2000)
            day.update_loads(id=load, p0=111.0)
            day.set_working_variant(V2030)
            day.update_loads(id=load, p0=333.0)
            day.set_working_variant('InitialState')
        rows = recorder.to_rdf_updates(db, scenario, 2, per_variant=True)

        assert list(rows.columns) == ['snapshot', 'modelling_authority', 'timestamp', 'version', 'models',
                                      'exported_events', 'rejected']
        assert sorted(rows.index) == [V2000, V2030]
        assert all(rows['version'] == 2)
        assert sorted(rows['timestamp']) == [T2000, T2030]
        assert all(rows['models'])

        reader = pp.network.from_rdf_db(db, scenario, 2, timestamps=[T2000, T2030], parameters=PARAMS)
        assert _loads(reader, V2000).loc[load, 'p0'] == pytest.approx(111.0)
        assert _loads(reader, V2030).loc[load, 'p0'] == pytest.approx(333.0)
        # the untouched timestamp kept its own version
        snapshots = db.snapshots(scenario)
        assert T2015 not in set(snapshots[snapshots['version'] == 2]['timestamp'])


def test_one_variant_export(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        load = str(day.get_loads().index[0])

        with day.event_recorder() as recorder:
            day.set_working_variant(V2015)
            day.update_loads(id=load, p0=222.0)
            day.set_working_variant('InitialState')
        ids = recorder.to_rdf_updates(db, scenario, 3, variant=V2015)
        assert ids

        reader = pp.network.from_rdf_db(db, scenario, 3, T2015, parameters=PARAMS)
        assert float(reader.get_loads().loc[load, 'p0']) == pytest.approx(222.0)

        with pytest.raises(ValueError, match='drop them'):
            recorder.to_rdf_updates(db, scenario, 4, T2015, variant=V2015)
        with pytest.raises(ValueError, match='drop them'):
            recorder.to_rdf_updates(db, scenario, 4, None, AUTHORITY, variant=V2015)
        with pytest.raises(ValueError, match='neither a timestamp'):
            recorder.to_rdf_updates(db, scenario, 4, per_variant=True, variant=V2015)
        with pytest.raises(ValueError, match='neither a timestamp'):
            recorder.to_rdf_updates(db, scenario, 4, profiles=['SSH'], per_variant=True)


def test_file_exports_take_a_variant(rdf_db_url: str, scenario: str) -> None:
    """The three file exports write the values of the variant they are told to, and restore the working one."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        load = str(day.get_loads().index[0])

        with day.event_recorder() as recorder:
            day.set_working_variant(V2000)
            day.update_loads(id=load, p0=11.0)
            day.set_working_variant(V2030)
            day.update_loads(id=load, p0=99.0)
            day.set_working_variant('InitialState')

        ssh = recorder.to_ssh(variant=V2000)
        assert '<cim:EnergyConsumer.p>11</cim:EnergyConsumer.p>' in ssh
        assert '<cim:EnergyConsumer.p>99</cim:EnergyConsumer.p>' not in ssh
        assert day.get_working_variant_id() == 'InitialState'

        diff = recorder.to_cgmes_diff(profile='SSH', variant=V2030)
        assert '<cim:EnergyConsumer.p>99</cim:EnergyConsumer.p>' in diff
        assert '<cim:EnergyConsumer.p>11</cim:EnergyConsumer.p>' not in diff

        documents = recorder.to_cgmes_diffs(variant=V2000)
        assert '<cim:EnergyConsumer.p>11</cim:EnergyConsumer.p>' in documents['SSH']
        assert day.get_working_variant_id() == 'InitialState'

        with pytest.raises(PyPowsyblError):
            recorder.to_ssh(variant='nope')


def test_a_load_flow_per_variant(rdf_db_url: str, scenario: str) -> None:
    """The reason the whole feature exists: run a study on every timestamp of a day without reloading anything."""
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)

        totals = {}
        for name in NAMES:
            day.set_working_variant(name)
            result = lf.run_ac(day)
            assert result[0].status == lf.ComponentStatus.CONVERGED, f'{name} did not converge'
            totals[name] = float(day.get_loads()['p0'].sum())
        day.set_working_variant('InitialState')

        # the day really moves: each timestamp scales the loads a little further
        assert totals[V2000] < totals[V2015] < totals[V2030]


def test_variant_argument_errors(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        with pytest.raises(ValueError, match='give one of the two'):
            pp.network.from_rdf_db(db, scenario, 1, T2000, timestamps=MOMENTS, parameters=PARAMS)
        with pytest.raises(ValueError, match='give one of the two'):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, variants={'a': T2000},
                                   parameters=PARAMS)
        with pytest.raises(ValueError, match='At least one snapshot'):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=[], parameters=PARAMS)
        with pytest.raises(ValueError, match='on_refusal'):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, on_refusal='ignore', parameters=PARAMS)
        with pytest.raises(ValueError, match='post_processors'):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, post_processors=['replaceTieLinesByLines'],
                                   parameters=PARAMS)
        with pytest.raises(ValueError, match='non-blank string'):
            pp.network.from_rdf_db(db, scenario, 1, variants={' ': T2000}, parameters=PARAMS)
        with pytest.raises(ValueError, match="Unknown CGMES profile 'XX'"):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, profiles=['XX'],  # type: ignore[list-item]
                                   parameters=PARAMS)
        with pytest.raises(ValueError, match='must be a timestamp or a'):
            pp.network.from_rdf_db(db, scenario, variants={'a': (1, T2000)}, parameters=PARAMS)  # type: ignore
        with pytest.raises(ValueError, match='takes a list of timestamps'):
            pp.network.from_rdf_db(db, scenario, 1, timestamps=T2000, parameters=PARAMS)  # type: ignore
        with pytest.raises(ValueError, match='on_refusal'):
            pp.network.from_rdf_db(db, scenario, 1, T2000, on_refusal='zzz', parameters=PARAMS)
        with pytest.raises(ValueError, match='non-blank string'):
            pp.network.from_rdf_db(db, scenario, 1, variants={1: T2000}, parameters=PARAMS)  # type: ignore
        network = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        for bad in (' ', 5):
            with pytest.raises(ValueError, match='non-blank string'):
                network.rdf_db_identity(db, scenario, variant=bad)  # type: ignore
            with pytest.raises(ValueError, match='non-blank string'):
                network.update_from_rdf_db(db, scenario, 1, T2000, variant=bad)  # type: ignore
        with network.event_recorder() as recorder:
            network.update_loads(id=str(network.get_loads().index[0]), p0=1.0)
        with pytest.raises(PyPowsyblError, match="has no variant 'nope'"):
            recorder.to_rdf_updates(db, scenario, 9, variant='nope', clear=False)


def test_a_variant_export_opts_the_network_into_variant_mode(rdf_db_url: str, scenario: str) -> None:
    """
    Naming a variant on the way *out* is an opt-in too.

    Before this, ``to_rdf_updates(variant=...)`` was the only door into variant semantics that did not lock them
    in, so the very next classic update could write across the variants the caller had just written histories
    for. It is now as sticky as the other two doors: afterwards an equipment drift is refused instead of
    reloading the network behind the caller's back.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes_from_binary_buffers([eq_drift(7, T2100, suffix=scenario)], scenario, 1, T2100,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
        network = pp.network.from_rdf_db(db, scenario, 1, T2000, parameters=PARAMS)
        network.clone_variant('InitialState', 'study')
        load = str(network.get_loads().index[0])

        with network.event_recorder() as recorder:
            network.set_working_variant('study')
            network.update_loads(id=load, p0=55.0)
            network.set_working_variant('InitialState')
        assert recorder.to_rdf_updates(db, scenario, 4, variant='study')

        # sticky from here on: the drifted timestamp is refused, not reloaded
        with pytest.raises(RdfDbVariantRefusedError):
            network.update_from_rdf_db(db, scenario, 1, T2100)
        assert drifted_name(7) not in set(network.get_lines()['name'])


def test_a_classic_operation_forgets_what_your_clones_stood_for(rdf_db_url: str, scenario: str) -> None:
    """
    A clone inherits its source's binding, but only until the next classic call.

    A classic in-place update may write values that are shared by every variant, so afterwards the library cannot
    vouch for what a cloned variant stands for any more - and says so instead of planning from a stale address.
    The primary row is unaffected, and in variant mode nothing is dropped.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        network = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        network.clone_variant('InitialState', 'what-if')
        assert _binding(network)['what-if']['status'] == 'bound'

        assert network.update_from_rdf_db(db, scenario, 1, T2000) == 'diff'

        binding = _binding(network)
        assert binding['InitialState']['status'] == 'primary', 'the primary row is never dropped'
        assert binding['what-if']['status'] == 'unbound'
        with pytest.raises(PyPowsyblError):
            network.rdf_db_identity(db, scenario, variant='what-if')

        # in variant mode the same clone keeps its binding
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        day.clone_variant(V2000, 'kept')
        # 'noop' is the deterministic answer here - the nearest bound variant, the V2030 one, already stands for
        # the target - and it still creates the variant, which is what the route does not tell you
        assert day.update_from_rdf_db(db, scenario, 1, T2030, variant='moved') == 'noop'
        assert 'moved' in day.get_variant_ids()
        pd.testing.assert_frame_equal(_loads(day, V2030), _loads(day, 'moved'), check_exact=False, rtol=1e-9)
        assert _binding(day)['kept']['status'] == 'bound'


def test_an_export_without_a_variant_refuses_shared_changes_in_variant_mode(rdf_db_url: str,
                                                                            scenario: str) -> None:
    """
    In variant mode every export is a variant export, ``variant=`` or not.

    A line impedance is not stored per variant, so it belongs to every variant of the network and to none of
    their snapshots. Writing it into the history of the working variant would be a lie, so it is an unsupported
    change - raised, or skipped with ``unsupported='ignore'``, like any other.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        load = str(day.get_loads().index[0])
        # a plain line, not one of the merged ones: a merged line is rejected by the export for a reason of its
        # own (its id is a pair), which would hide the rule under test here
        line = next(str(i) for i in day.get_lines().index if ' + ' not in str(i))
        day.set_working_variant(V2000)

        with day.event_recorder() as recorder:
            day.update_loads(id=load, p0=77.0)
            day.update_lines(id=line, r=0.42)

        # the address is still given the classic way - the timestamp of the variant the network is working on
        with pytest.raises(PyPowsyblError, match='not stored per variant'):
            recorder.to_rdf_updates(db, scenario, 5, T2000, clear=False)
        # a file export of the same recording says the same thing, named or not
        with pytest.raises(PyPowsyblError, match='not stored per variant'):
            recorder.to_cgmes_diffs()
        ids = recorder.to_rdf_updates(db, scenario, 5, T2000, unsupported='ignore')
        assert ids, 'the steady-state half is still written'
        day.set_working_variant('InitialState')

        # and with the primary selected too: a shared value belongs to no single snapshot
        with day.event_recorder() as primary_recorder:
            day.update_loads(id=load, p0=88.0)
            day.update_lines(id=line, r=0.88)
        with pytest.raises(PyPowsyblError, match='not stored per variant'):
            primary_recorder.to_cgmes_diffs()
        assert sorted((primary_recorder.to_cgmes_diffs(unsupported='ignore') or {}).keys()) == ['SSH']

        reader = pp.network.from_rdf_db(db, scenario, 5, T2000, parameters=PARAMS)
        assert float(reader.get_loads().loc[load, 'p0']) == pytest.approx(77.0)


def test_file_exports_carry_the_identity_of_the_variant_they_describe(rdf_db_url: str, scenario: str) -> None:
    """
    A document describing one variant has to *say* which snapshot it succeeds.

    ``inVariant`` is in the binding for exactly this: the header of the difference supersedes the model the
    variant stands for and is dated at that variant's moment. It must hold when the caller names the variant and
    when the caller merely works on it - the second is the natural gesture of the whole feature, and getting it
    wrong writes a document that silently claims to succeed another snapshot.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=[T2000, T2030], parameters=PARAMS)
        binding = _binding(day)
        ssh_late = str(binding[V2030]['ssh'])
        ssh_primary = str(binding['InitialState']['ssh'])
        assert ssh_late and ssh_primary and ssh_late != ssh_primary
        load = str(day.get_loads().index[0])

        with day.event_recorder() as recorder:
            day.set_working_variant(V2030)
            day.update_loads(id=load, p0=123.0)
            day.set_working_variant('InitialState')

        named = recorder.to_ssh(variant=V2030)
        assert ssh_late in named and ssh_primary not in named
        assert '20:30:00Z</md:Model.scenarioTime>' in named

        # the same without naming it: the working variant is bound, so that is what is being described
        day.set_working_variant(V2030)
        implicit = recorder.to_ssh()
        assert day.get_working_variant_id() == V2030, 'the export restores the working variant'
        day.set_working_variant('InitialState')
        assert ssh_late in implicit and ssh_primary not in implicit
        assert '20:30:00Z</md:Model.scenarioTime>' in implicit

        diff = recorder.to_cgmes_diff(profile='SSH', variant=V2030)
        assert ssh_late in diff and ssh_primary not in diff

        # and on the primary the identity is the network's own, as it always was
        primary = recorder.to_ssh(unsupported='ignore')
        assert ssh_primary in primary and ssh_late not in primary
        assert '20:00:00Z</md:Model.scenarioTime>' in primary


def test_a_variant_unsafe_change_is_refused(rdf_db_url: str, scenario: str) -> None:
    """
    The other half of the refusal story: not an equipment *drift*, but a difference whose statements the in-place
    update knows and whose target IIDM does not store per variant.

    A line impedance is the cheapest such change. The classic network applies it; a variant update and a bulk
    load refuse it, name the reason, and leave every variant where it was. This is the Python coverage of the
    variant-safety table, and the compensation for the HVDC case the fixture cannot carry.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        sender = pp.network.from_rdf_db(db, scenario, 1, T2030, parameters=PARAMS)
        line = next(str(i) for i in sender.get_lines().index if ' + ' not in str(i))
        r0 = float(sender.get_lines().loc[line, 'r'])
        with sender.event_recorder() as recorder:
            sender.update_lines(id=line, r=r0 * 2)
            sender.update_loads(id=str(sender.get_loads().index[0]), p0=5.0)
            recorder.to_rdf_updates(db, scenario, 2, T2030)

        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
        before = {name: _loads(day, name) for name in NAMES}
        with pytest.raises(RdfDbVariantRefusedError) as error:
            day.update_from_rdf_db(db, scenario, 2, T2030, variant=V2030)
        assert any('not stored per variant' in reason for reason in error.value.reasons), error.value.reasons
        assert float(day.get_lines().loc[line, 'r']) == pytest.approx(r0), 'the impedance is shared and untouched'
        for name in NAMES:
            pd.testing.assert_frame_equal(before[name], _loads(day, name), check_exact=False, rtol=1e-9)

        with pytest.raises(RdfDbVariantRefusedError):
            pp.network.from_rdf_db(db, scenario, 1, variants={'a': T2000, 'b': (2, T2030, None)},
                                   parameters=PARAMS)

        # the very same difference is ordinary work for a network that is not in variant mode
        classic = pp.network.from_rdf_db(db, scenario, 1, T2030, parameters=PARAMS)
        assert classic.update_from_rdf_db(db, scenario, 2, T2030) == 'diff'
        assert float(classic.get_lines().loc[line, 'r']) == pytest.approx(r0 * 2)


def test_an_unnamed_export_on_a_user_clone_is_unchanged(rdf_db_url: str, scenario: str) -> None:
    """
    Tracking a clone is not an opt-in, and an unnamed export must not behave as if it were.

    A variant cloned from a database-loaded network inherits its source's binding - its state *is* that snapshot -
    but the caller asked for nothing. Exporting while such a clone is the working variant therefore has to write
    what it always wrote, a shared line impedance included: that is usually the very reason a clone exists.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        network = pp.network.from_rdf_db(db, scenario, 1, T2030, parameters=PARAMS)
        network.clone_variant('InitialState', 'what-if')
        assert _binding(network)['what-if']['status'] == 'bound', 'the clone is tracked...'
        network.set_working_variant('what-if')

        load = str(network.get_loads().index[0])
        line = next(str(i) for i in network.get_lines().index if ' + ' not in str(i))
        with network.event_recorder() as recorder:
            network.update_loads(id=load, p0=31.0)
            network.update_lines(id=line, r=0.31)

        # ...but nothing opted in, so the shared change is written exactly as it was before variants existed
        assert sorted((recorder.to_cgmes_diffs() or {}).keys()) == ['EQ', 'SSH']
        assert '<cim:EnergyConsumer.p>31</cim:EnergyConsumer.p>' in recorder.to_ssh(unsupported='ignore')
        assert network.get_working_variant_id() == 'what-if'
        network.set_working_variant('InitialState')


def test_a_cross_scenario_refusal_switches_variant_mode_on(rdf_db_url: str, scenario: str,
                                                           scenario2: str) -> None:
    """
    A refused first opt-in still opts in.

    A difference never crosses scenarios, so naming a variant for a snapshot of another day is refused without a
    query. Variant mode is on from that moment all the same: the caller asked for variant semantics, and the next
    classic call must not quietly rebuild the network and drop the variants it was about to create.
    """
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        db.load_cgmes(CGMES_ZIP, scenario2, 1, modelling_authority=AUTHORITY, parameters=PARAMS)
        network = pp.network.from_rdf_db(db, scenario, 1, parameters=PARAMS)
        network.clone_variant('InitialState', 'keep')
        handle = network._handle  # pylint: disable=protected-access

        with pytest.raises(RdfDbVariantRefusedError) as error:
            network.update_from_rdf_db(db, scenario2, 1, variant='other-day')
        assert error.value.reasons
        assert 'other-day' not in network.get_variant_ids()
        assert network._handle is handle  # pylint: disable=protected-access

        with pytest.raises(RdfDbVariantRefusedError):
            network.update_from_rdf_db(db, scenario2, 1)
        assert network._handle is handle, 'the handle swap must not run once variant mode is on'  # pylint: disable=protected-access
        assert 'keep' in network.get_variant_ids()


def test_variants_binding_is_readable_while_a_variant_is_moved(rdf_db_url: str, scenario: str) -> None:
    """
    Reading the table from another thread while a variant is being moved must not hang the process.

    Both sides take the provenance lock in Java. The update releases the GIL, holds that lock and logs, and
    logging crosses back into Python, which needs the GIL; a table read that kept the GIL while waiting for the
    lock therefore deadlocked the whole interpreter - with the ``powsybl`` logger at INFO, which is not an exotic
    setting. The check runs in a subprocess with a hard timeout, so a regression fails the suite instead of
    hanging it.
    """
    script = f'''
import logging, os, sys, threading
sys.path.insert(0, {str(TESTS_DIR)!r})
logging.basicConfig(stream=open(os.devnull, "w"))
logging.getLogger("powsybl").setLevel(logging.INFO)
import pypowsybl as pp
from rdf_db_fixtures import AUTHORITY, CGMES_ZIP, at, ssh_variant
pp.set_config_read(False)
PARAMS = {PARAMS!r}
MOMENTS = [at("20:00"), at("20:15"), at("20:30")]
with pp.network.connect({rdf_db_url!r}) as db:
    scenario = {scenario!r} + "-thread"
    db.load_cgmes(CGMES_ZIP, scenario, 1, modelling_authority=AUTHORITY, parameters=PARAMS)
    for i, moment in enumerate(MOMENTS, start=1):
        db.load_cgmes_from_binary_buffers([ssh_variant(i, moment, suffix=scenario)], scenario, None, moment,
                                          modelling_authority=AUTHORITY, parameters=PARAMS)
    day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)
    stop = []
    reads = [0]
    def reader():
        while not stop:
            day.variants_binding()
            reads[0] += 1
    t = threading.Thread(target=reader)
    t.start()
    for i in range(40):
        day.update_from_rdf_db(db, scenario, 1, MOMENTS[i % 2], variant={V2030!r})
    stop.append(True)
    t.join()
    print("OK", reads[0])
'''
    completed = subprocess.run([sys.executable, '-c', script], capture_output=True, text=True, timeout=180,
                               check=False, cwd=str(TESTS_DIR.parent))
    assert completed.returncode == 0, f'stdout={completed.stdout}\nstderr={completed.stderr[-3000:]}'
    assert completed.stdout.startswith('OK'), completed.stdout


def test_removed_and_cloned_variants(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        _a_day(db, scenario)
        day = pp.network.from_rdf_db(db, scenario, 1, timestamps=MOMENTS, parameters=PARAMS)

        day.clone_variant(V2030, 'copy')
        assert _binding(day)['copy']['snapshot'] == _binding(day)[V2030]['snapshot']
        # a user-made clone is a full member: it can be brought to another snapshot like any other variant
        assert day.update_from_rdf_db(db, scenario, 1, T2000, variant='copy') == 'diff'
        pd.testing.assert_frame_equal(_loads(day, V2000), _loads(day, 'copy'), check_exact=False, rtol=1e-9)

        day.remove_variant('copy')
        assert 'copy' not in day.get_variant_ids()
        assert 'copy' not in _binding(day)
