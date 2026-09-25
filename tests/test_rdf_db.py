#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Tests of the split CGMES loading: instance files into an RDF graph database, and a network out of it.

Every test runs twice, once on the in-process ``memory:`` backend and once against a real Apache Jena Fuseki
server started as a subprocess (skipped, with the reason, when the Fuseki jar is not installed). Isolation between
tests on the shared server is by scenario name, which is what the scenario key is for.
"""
import io
import pathlib
import time
from collections import Counter
from typing import Dict

import pandas as pd
import pytest

import pypowsybl as pp
from pypowsybl import PyPowsyblError

TEST_DIR = pathlib.Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'
CGMES_ZIP = DATA_DIR / 'CGMES_Full.zip'

# The database path always produces one network; a combined model is split into subnetworks at file level, above
# any triple store. Both sides of every comparison are therefore loaded with the split switched off.
PARAMS = {'iidm.import.cgmes.cgm-with-subnetworks': 'false'}
# A sorted XIIDM document does not depend on the order the elements were created in, which makes it comparable
# between two loading paths as long as the node numbering agrees - see assert_same_network.
XIIDM_SORTED = {'iidm.export.xml.sorted': 'true'}

# Everything that is a property of the data rather than of the order it was read in: the identifiers of every
# equipment type, and the values attached to them.
_IDENTIFIER_FRAMES = (('loads', 'get_loads'), ('generators', 'get_generators'), ('switches', 'get_switches'),
                      ('lines', 'get_lines'), ('transformers', 'get_2_windings_transformers'),
                      ('3w transformers', 'get_3_windings_transformers'),
                      ('shunts', 'get_shunt_compensators'), ('voltage_levels', 'get_voltage_levels'),
                      ('substations', 'get_substations'), ('boundary_lines', 'get_boundary_lines'),
                      ('tie_lines', 'get_tie_lines'))
_VALUE_COLUMNS = ((['p0', 'q0', 'connected'], 'get_loads'),
                  (['target_p', 'target_q', 'target_v', 'voltage_regulator_on', 'connected'], 'get_generators'),
                  (['open'], 'get_switches'),
                  (['section_count', 'connected'], 'get_shunt_compensators'),
                  (['r', 'x', 'g1', 'b1', 'g2', 'b2', 'connected1', 'connected2'], 'get_lines'),
                  (['r', 'x', 'g', 'b', 'connected1', 'connected2'], 'get_2_windings_transformers'),
                  (['r1', 'x1', 'g1', 'b1', 'r2', 'x2', 'g2', 'b2', 'r3', 'x3', 'g3', 'b3',
                    'connected1', 'connected2', 'connected3'], 'get_3_windings_transformers'),
                  (['r', 'x', 'g', 'b', 'p0', 'q0', 'connected'], 'get_boundary_lines'),
                  (['connected1', 'connected2'], 'get_tie_lines'),
                  (['tap'], 'get_ratio_tap_changers'),
                  (['tap'], 'get_phase_tap_changers'))


def _bus_voltages(network: pp.network.Network) -> Dict[str, Counter]:
    """
    The multiset of ``(v, angle)`` of every voltage level.

    The *name* of a calculated bus is ``<voltage level>_<lowest node>`` and therefore depends on the node
    numbering, which the Fuseki path is free to change; how many buses a voltage level has and which state
    variables they carry does not. ``NaN`` becomes ``None`` so that a missing value equals a missing value.
    """
    buses = network.get_buses()
    return {voltage_level: Counter((None if pd.isna(v) else round(float(v), 6),
                                    None if pd.isna(angle) else round(float(angle), 6))
                                   for v, angle in zip(frame['v_mag'], frame['v_angle']))
            for voltage_level, frame in buses.groupby('voltage_level_id')}


def assert_same_network(expected: pp.network.Network, actual: pp.network.Network, url: str) -> None:
    """
    Assert that two networks hold the same grid.

    On the in-process ``memory:`` backend the two are compared as whole **XIIDM documents**: the store keeps the
    statements in insertion order, so the database path walks its query results in the same order as the file path
    and the networks come out identical down to the node numbering. Nothing a graph transfer could break escapes
    that comparison.

    Against a real SPARQL server the row order is the server's (its own index order per graph), and a CGMES
    conversion numbers the nodes of a node-breaker voltage level in the order it meets them, so the *names* of
    calculated buses legitimately differ. Everything that is a property of the data is still compared strictly:
    every identifier of every equipment type, the connection state and the impedances of every branch, every
    steady-state value, and the multiset of ``(v, angle)`` per voltage level.

    Args:
        expected: the network the files produced
        actual: the network the database produced
        url: the database URL, which decides how strict the comparison can be
    """
    _assert_structurally_same(expected, actual)
    if url.startswith('memory:'):
        assert expected.save_to_string('XIIDM', XIIDM_SORTED) == actual.save_to_string('XIIDM', XIIDM_SORTED), \
            'the XIIDM documents differ'


def _assert_structurally_same(expected: pp.network.Network, actual: pp.network.Network) -> None:
    for name, frame in _IDENTIFIER_FRAMES:
        expected_frame = getattr(expected, frame)().sort_index()
        actual_frame = getattr(actual, frame)().sort_index()
        assert list(expected_frame.index) == list(actual_frame.index), f'the {name} differ'

    expected_buses = expected.get_buses().groupby('voltage_level_id').size().sort_index()
    actual_buses = actual.get_buses().groupby('voltage_level_id').size().sort_index()
    pd.testing.assert_series_equal(expected_buses, actual_buses)
    assert _bus_voltages(expected) == _bus_voltages(actual), 'the bus voltages and angles differ'

    for columns, frame in _VALUE_COLUMNS:
        expected_frame = getattr(expected, frame)().sort_index()[columns]
        actual_frame = getattr(actual, frame)().sort_index()[columns]
        pd.testing.assert_frame_equal(expected_frame, actual_frame, check_dtype=False)


def load_from_files() -> pp.network.Network:
    return pp.network.load(CGMES_ZIP, PARAMS)


def modified_ssh(delta: float = 123.0) -> io.BytesIO:
    """A steady state exported from a network whose first load was moved, as a buffer ready to be uploaded."""
    network = load_from_files()
    load_id = sorted(network.get_loads().index)[0]
    network.update_loads(id=load_id, p0=network.get_loads().loc[load_id]['p0'] + delta)
    return io.BytesIO(network.save_to_binary_buffer('CGMES', {'iidm.export.cgmes.profiles': 'SSH'}).getbuffer())


def test_the_network_from_the_database_is_the_network_from_the_files(rdf_db_url: str, scenario: str) -> None:
    from_files = load_from_files()
    with pp.network.connect(rdf_db_url) as db:
        graphs = db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        assert len(graphs) >= 4
        assert scenario in db.scenarios().index

        from_db = pp.network.from_rdf_db(db, scenario, parameters=PARAMS)
        assert_same_network(from_files, from_db, rdf_db_url)


def test_the_graph_catalogue_names_the_instance_files(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        graphs = db.graphs(scenario)
        assert graphs.index.name == 'name'
        assert list(graphs.columns) == ['subset', 'graph']
        assert {'EQ', 'SSH', 'TP', 'SV'}.issubset(set(graphs['subset']))
        assert all(name.startswith('contexts:') for name in graphs.index)
        assert all(scenario in graph for graph in graphs['graph'])


def test_two_days_live_side_by_side_in_one_database(rdf_db_url: str, scenario: str, scenario2: str) -> None:
    from_files = load_from_files()
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        db.load_cgmes(CGMES_ZIP, scenario2, parameters=PARAMS)
        assert {scenario, scenario2}.issubset(set(db.scenarios().index))

        # Neither day sees a statement of the other: each is exactly the model that was uploaded into it
        assert_same_network(from_files, pp.network.from_rdf_db(db, scenario, parameters=PARAMS), rdf_db_url)
        assert_same_network(from_files, pp.network.from_rdf_db(db, scenario2, parameters=PARAMS), rdf_db_url)

        db.clear(scenario)
        assert scenario not in db.scenarios().index
        assert db.graphs(scenario).empty
        # Dropping one day leaves the other intact
        assert_same_network(from_files, pp.network.from_rdf_db(db, scenario2, parameters=PARAMS), rdf_db_url)


def test_a_steady_state_from_the_database_updates_a_network(rdf_db_url: str, scenario2: str) -> None:
    network = load_from_files()
    load_id = sorted(network.get_loads().index)[0]
    before = network.get_loads().loc[load_id]['p0']

    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes_from_binary_buffers([modified_ssh()], scenario2, parameters=PARAMS)
        assert db.graphs(scenario2)['subset'].tolist() == ['SSH']

        route = network.update_from_rdf_db(db, scenario2, subsets=['SSH'], parameters=PARAMS)
        assert route == 'update'
        assert network.get_loads().loc[load_id]['p0'] == pytest.approx(before + 123.0)


def test_loading_the_same_files_twice_replaces_the_graphs(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        first = db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        second = db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        assert first == second
        assert len(db.graphs(scenario)) == len(first)
        assert_same_network(load_from_files(), pp.network.from_rdf_db(db, scenario, parameters=PARAMS), rdf_db_url)


def test_an_empty_scenario_is_an_error_that_says_which_scenarios_exist(rdf_db_url: str, scenario: str) -> None:
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        with pytest.raises(PyPowsyblError, match='not-a-day'):
            pp.network.from_rdf_db(db, 'not-a-day', parameters=PARAMS)


def test_an_unknown_subset_is_refused_with_the_known_ones(rdf_db_url: str, scenario: str) -> None:
    network = load_from_files()
    with pp.network.connect(rdf_db_url) as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        with pytest.raises(PyPowsyblError, match='Unknown CGMES subset'):
            network.update_from_rdf_db(db, scenario, subsets=['NOPE'])


def test_the_connection_is_a_context_manager_and_closes_once() -> None:
    db = pp.network.connect('memory:closing')
    assert db.url == 'memory:closing'
    with db:
        assert db.scenarios().empty
    db.close()  # idempotent
    network = pp.network.create_eurostag_tutorial_example1_network()
    # Every route through the connection refuses to touch a closed one, and says which connection it was
    for call in (db.scenarios,
                 lambda: db.graphs('2021-02-09'),
                 lambda: db.clear('2021-02-09'),
                 lambda: db.load_cgmes(CGMES_ZIP, '2021-02-09'),
                 lambda: pp.network.from_rdf_db(db, '2021-02-09'),
                 lambda: network.update_from_rdf_db(db, '2021-02-09')):
        with pytest.raises(PyPowsyblError, match='memory:closing is closed'):
            call()


def test_a_scenario_is_required_and_must_not_be_blank() -> None:
    network = pp.network.create_eurostag_tutorial_example1_network()
    with pp.network.connect('memory:blank') as db:
        calls = (lambda s: db.load_cgmes(CGMES_ZIP, s),
                 lambda s: db.load_cgmes_from_binary_buffers([], s),
                 db.graphs,
                 db.clear,
                 lambda s: pp.network.from_rdf_db(db, s),
                 lambda s: network.update_from_rdf_db(db, s))
        for blank in ('', '   '):
            for call in calls:
                with pytest.raises(ValueError, match='must not be blank'):
                    call(blank)
        # Not a string at all is a TypeError, not a "must not be blank" ValueError
        for wrong_type in (5, None, b'2021-02-09'):
            for call in calls:
                with pytest.raises(TypeError, match='must be a string'):
                    call(wrong_type)


def test_an_unknown_query_mode_is_refused_before_anything_is_opened() -> None:
    with pytest.raises(ValueError, match='query_mode'):
        pp.network.connect_rdf_db('memory:x', query_mode='sideways')


def test_an_unreachable_server_names_its_url() -> None:
    with pytest.raises(PyPowsyblError, match='127.0.0.1:1'):
        pp.network.connect_rdf_db('http://127.0.0.1:1/ds', connect_timeout=1.0, read_timeout=1.0)


def test_remote_query_mode_gives_the_same_network(fuseki_url: str, scenario: str) -> None:
    """The catalog queries run on the server instead of on a local copy of the graphs; the network is the same."""
    from_files = load_from_files()
    with pp.network.connect(fuseki_url, query_mode='remote') as db:
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        assert_same_network(from_files, pp.network.from_rdf_db(db, scenario, parameters=PARAMS), fuseki_url)


def test_the_database_load_is_not_far_off_the_file_load(fuseki_url: str, scenario: str) -> None:
    """A soft check, printed rather than asserted: the point of the split is that the second half is cheap."""
    start = time.perf_counter()
    load_from_files()
    file_seconds = time.perf_counter() - start

    with pp.network.connect(fuseki_url) as db:
        start = time.perf_counter()
        db.load_cgmes(CGMES_ZIP, scenario, parameters=PARAMS)
        upload_seconds = time.perf_counter() - start

        start = time.perf_counter()
        pp.network.from_rdf_db(db, scenario, parameters=PARAMS)
        db_seconds = time.perf_counter() - start

    print(f'\nfile={file_seconds * 1000:.0f} ms  upload={upload_seconds * 1000:.0f} ms  '
          f'db={db_seconds * 1000:.0f} ms  ratio={db_seconds / file_seconds:.2f}')
    assert db_seconds > 0
