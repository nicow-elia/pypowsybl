#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
The regulation columns of the dataframes (target_v, target_q, voltage_regulator_on, regulation_mode, regulating,
regulated_element_id, ...) since powsybl-core 7.5, where they all live in one VoltageRegulation per equipment.

They must keep behaving as the independent attributes they were before: writing a dataframe back unchanged changes
nothing, and the order in which the columns of one update are applied does not matter.
"""
import pathlib

import pandas as pd
import pytest

import pypowsybl as pp
import pypowsybl.loadflow as lf

TEST_DIR = pathlib.Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'


def _four_substations_cgmes(tmp_path: pathlib.Path) -> pp.network.Network:
    pp.network.create_four_substations_node_breaker_network().save(str(tmp_path / 'four'), format='CGMES')
    return pp.network.load(str(tmp_path))


def _svc_without_regulation(tmp_path: pathlib.Path) -> pp.network.Network:
    """Since core 7.5 a compensator can be created without any regulation: regulation_mode reads ''."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.create_static_var_compensators(id='SVC9', voltage_level_id='S1VL2', node=90, b_min=-0.01, b_max=0.01)
    return n


def _svc_mode_in_another_variant(tmp_path: pathlib.Path) -> pp.network.Network:
    """A regulation created while the network has two variants has no mode in the other one (review 21 F9)."""
    n = _svc_without_regulation(tmp_path)
    n.clone_variant(n.get_working_variant_id(), 'other')
    n.update_static_var_compensators(id='SVC9', regulation_mode='VOLTAGE')
    n.set_working_variant('other')
    return n


# name -> (factory, whether the network comes from CGMES, i.e. whether a change of it can be exported as SSH)
NETWORKS = {
    'four_substations': (lambda tmp: pp.network.create_four_substations_node_breaker_network(), False),
    'four_substations_extensions':
        (lambda tmp: pp.network.create_four_substations_node_breaker_network_with_extensions(), False),
    'four_substations_cgmes': (_four_substations_cgmes, True),
    'micro_grid_be': (lambda tmp: pp.network.create_micro_grid_be_network(), True),
    'micro_grid_nl': (lambda tmp: pp.network.create_micro_grid_nl_network(), True),
    'cgmes_full': (lambda tmp: pp.network.load(DATA_DIR / 'CGMES_Full.zip'), True),
    'eurostag': (lambda tmp: pp.network.create_eurostag_tutorial_example1_network(), False),
    'ieee14': (lambda tmp: pp.network.create_ieee14(), False),
    'battery': (lambda tmp: pp.network.load(TEST_DIR / 'battery.xiidm'), False),
    'dc_vsc': (lambda tmp: pp.network.create_dc_detailed_vsc_symmetrical_monopole_network(), False),
    'svc_without_regulation': (_svc_without_regulation, False),
    'svc_mode_in_another_variant': (_svc_mode_in_another_variant, False),
}

# element dataframe -> its regulation related columns
REGULATION_COLUMNS = {
    'generators': ['target_v', 'target_q', 'voltage_regulator_on', 'regulated_element_id'],
    'batteries': ['target_p', 'target_q'],
    'vsc_converter_stations': ['target_v', 'target_q', 'voltage_regulator_on', 'regulated_element_id'],
    'static_var_compensators': ['target_v', 'target_q', 'regulation_mode', 'regulating', 'regulated_element_id'],
    'shunt_compensators': ['voltage_regulation_on', 'target_v', 'target_deadband'],
    'ratio_tap_changers': ['regulating', 'target_v', 'target_deadband', 'regulated_side'],
    'phase_tap_changers': ['regulation_mode', 'regulation_value', 'target_deadband', 'regulating', 'regulated_side'],
    'voltage_source_converters': ['voltage_regulator_on', 'target_v_ac', 'target_q'],
}
# extension -> its regulation related columns (views on the VoltageRegulation since core 7.5)
EXTENSION_COLUMNS = {
    'voltageRegulation': ['voltage_regulator_on', 'target_v', 'regulated_element_id'],
    'voltagePerReactivePowerControl': ['slope'],
}

SNAPSHOT_DATAFRAMES = ['buses', 'generators', 'loads', 'batteries', 'shunt_compensators', 'static_var_compensators',
                       'vsc_converter_stations', 'lcc_converter_stations', 'hvdc_lines', 'lines',
                       '2_windings_transformers', '3_windings_transformers', 'ratio_tap_changers',
                       'phase_tap_changers', 'boundary_lines', 'voltage_source_converters']


def snapshot(n: pp.network.Network) -> dict:
    """Every dataframe of the network a regulation change could show in."""
    frames = {name: getattr(n, 'get_' + name)(all_attributes=True) for name in SNAPSHOT_DATAFRAMES}
    for extension in EXTENSION_COLUMNS:
        frames[extension] = n.get_extensions(extension)
    return frames


def assert_unchanged(before: dict, after: dict, what: str) -> None:
    for name, frame in before.items():
        try:
            pd.testing.assert_frame_equal(frame, after[name])
        except AssertionError as e:
            raise AssertionError(f'{what} changed the {name} dataframe: {e}') from e


def selections(columns: list) -> list:
    """Each column alone, then all of them in one update."""
    return [[c] for c in columns] + [columns]


def regulation_frames(n: pp.network.Network) -> list:
    """(name, regulation columns, current values, update function) of each non empty regulation dataframe."""
    frames = []
    for kind, columns in REGULATION_COLUMNS.items():
        frame = getattr(n, 'get_' + kind)(all_attributes=True)
        if kind in ('ratio_tap_changers', 'phase_tap_changers'):
            # the updaters of the tap changers only address two windings transformers (pre-existing)
            frame = frame[frame.index.isin(n.get_2_windings_transformers().index)]
        frames.append((kind, columns, frame, getattr(n, 'update_' + kind)))
    for extension, columns in EXTENSION_COLUMNS.items():
        frames.append((extension, columns, n.get_extensions(extension),
                       lambda df, name=extension: n.update_extensions(name, df)))
    return [f for f in frames if not f[2].empty]


@pytest.mark.parametrize('network', NETWORKS)
def test_write_back_unchanged_regulation_columns(tmp_path, network):
    """
    Writing the regulation columns back unchanged, each alone and all in one update, changes no dataframe and not
    even the IIDM serialisation, and a network imported from CGMES can still export the (empty) change as SSH.
    """
    factory, from_cgmes = NETWORKS[network]
    n = factory(tmp_path)
    xiidm = n.save_to_string()
    for kind, columns, frame, update in regulation_frames(n):
        for selection in selections(columns):
            what = f'writing back {selection} of the {kind} of {network}'
            before = snapshot(n)
            with n.event_recorder() as recorder:
                update(frame[selection])
                if from_cgmes:
                    recorder.to_ssh()
            assert_unchanged(before, snapshot(n), what)
            assert n.save_to_string() == xiidm, what + ' changed the IIDM serialisation'
    if from_cgmes:
        n.save(str(tmp_path / 'export'), format='CGMES')


def test_write_back_unchanged_regulated_element_with_several_variants():
    n = pp.network.create_four_substations_node_breaker_network()
    n.clone_variant(n.get_working_variant_id(), 'other')
    for kind in ('generators', 'vsc_converter_stations', 'static_var_compensators'):
        frame = getattr(n, 'get_' + kind)()
        before = snapshot(n)
        getattr(n, 'update_' + kind)(frame[['regulated_element_id']])
        assert_unchanged(before, snapshot(n), f'writing back the regulated elements of the {kind}')


# ------------------------------------------------------------------------------------ the scenarios of review 21 F1

COLUMNS = ['target_v', 'target_q', 'voltage_regulator_on', 'regulated_element_id']


def row(frame: pd.DataFrame, element_id: str, columns=None) -> list:
    return frame.loc[element_id, columns or COLUMNS].tolist()


def test_vsc_target_and_regulated_element_in_one_update():
    """F1 b: the target written in the same update as the regulated element must not be lost."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.update_vsc_converter_stations(id='VSC2', target_v=1.0, target_q=2.0, regulated_element_id='VSC1')
    assert row(n.get_vsc_converter_stations(), 'VSC2') == [1.0, 2.0, False, 'VSC1']


@pytest.mark.parametrize('columns', [['target_v', 'regulated_element_id'], ['regulated_element_id', 'target_v']])
def test_vsc_target_and_regulated_element_in_any_column_order(columns):
    n = pp.network.create_four_substations_node_breaker_network()
    update = pd.DataFrame({'target_v': [1.0], 'regulated_element_id': ['VSC1']}, index=pd.Index(['VSC2'], name='id'))
    n.update_vsc_converter_stations(update[columns])
    assert row(n.get_vsc_converter_stations(), 'VSC2', ['target_v', 'regulated_element_id']) == [1.0, 'VSC1']


def test_generator_created_without_regulation_then_regulating_remotely():
    """F1 c: target and regulated element first, regulator switched on afterwards."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.create_generators(id='G9', voltage_level_id='S1VL2', node=91, max_p=10, min_p=0, target_p=5, target_q=1.0,
                        voltage_regulator_on=False)
    n.update_generators(id='G9', target_v=392.0, regulated_element_id='GH2')
    assert row(n.get_generators(), 'G9') == [392.0, 1.0, False, 'GH2']
    n.update_generators(id='G9', voltage_regulator_on=True)
    assert row(n.get_generators(), 'G9') == [392.0, 1.0, True, 'GH2']


def test_generator_regulated_element_keeps_its_target():
    n = pp.network.create_four_substations_node_breaker_network()
    n.update_generators(id='GH1', target_v=390.0, regulated_element_id='GH2')
    assert row(n.get_generators(), 'GH1', ['target_v', 'regulated_element_id']) == [390.0, 'GH2']
    n.update_generators(id='GH1', regulated_element_id='GH3')
    assert row(n.get_generators(), 'GH1', ['target_v', 'regulated_element_id']) == [390.0, 'GH3']


def test_vsc_switched_off_then_reactive_setpoint():
    """F1 d: the station follows its reactive power setpoint, dataframe and load flow as before core 7.5."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.update_vsc_converter_stations(id='VSC1', voltage_regulator_on=False, target_q=30.0)
    assert row(n.get_vsc_converter_stations(), 'VSC1') == [400.0, 30.0, False, 'VSC1']
    assert lf.run_ac(n)[0].status == lf.ComponentStatus.CONVERGED
    assert n.get_vsc_converter_stations().loc['VSC1', 'q'] == pytest.approx(-30.0, abs=1e-3)


def test_vsc_reactive_setpoint_survives_full_cgmes_export(tmp_path):
    """F1 e: core wrote targetQpcc 0 for a station whose regulation does not regulate (fixed in core, 03:28)."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.update_vsc_converter_stations(id='VSC1', voltage_regulator_on=False, target_q=30.0)
    n.save(str(tmp_path / 'vsc'), format='CGMES')
    assert pp.network.load(str(tmp_path)).get_vsc_converter_stations().loc['VSC1', 'target_q'] == pytest.approx(30.0)


def test_vsc_switched_on_keeps_both_targets(tmp_path):
    """
    A station imported from CGMES in reactive power mode regulates its reactive power at a terminal: switching the
    voltage regulator on moves the regulation to voltage mode with the voltage target shown, and keeps the reactive
    power target shown.
    """
    n = _four_substations_cgmes(tmp_path)
    before = row(n.get_vsc_converter_stations(), 'VSC2')
    with n.event_recorder() as recorder:
        n.update_vsc_converter_stations(id='VSC2', target_v=401.0)
        n.update_vsc_converter_stations(id='VSC2', voltage_regulator_on=True)
        assert row(n.get_vsc_converter_stations(), 'VSC2') == [401.0, before[1], True, before[3]]
        # CGMES has the switch to voltage control: qPccControl voltagePcc with its target
        assert 'voltagePcc' in recorder.to_ssh()
    n.update_vsc_converter_stations(id='VSC2', voltage_regulator_on=False)
    assert row(n.get_vsc_converter_stations(), 'VSC2') == [401.0, before[1], False, before[3]]


SVC_COLUMNS = ['target_v', 'target_q', 'regulation_mode', 'regulating', 'regulated_element_id']


@pytest.mark.parametrize('network', ['four_substations', 'four_substations_cgmes'])
def test_svc_mode_switch_keeps_both_targets(tmp_path, network):
    n = NETWORKS[network][0](tmp_path)
    n.update_static_var_compensators(id='SVC', target_v=401.0, target_q=-30.0)
    before = row(n.get_static_var_compensators(), 'SVC', SVC_COLUMNS)
    other = 'REACTIVE_POWER' if before[2] == 'VOLTAGE' else 'VOLTAGE'
    n.update_static_var_compensators(id='SVC', regulation_mode=other)
    assert row(n.get_static_var_compensators(), 'SVC', SVC_COLUMNS) == [401.0, -30.0, other] + before[3:]
    n.update_static_var_compensators(id='SVC', regulation_mode=before[2])
    assert row(n.get_static_var_compensators(), 'SVC', SVC_COLUMNS) == [401.0, -30.0] + before[2:]


def test_svc_regulated_element_keeps_its_target(tmp_path):
    n = _four_substations_cgmes(tmp_path)
    n.update_static_var_compensators(id='SVC', target_q=-30.0, target_v=401.0)
    n.update_static_var_compensators(id='SVC', regulation_mode='REACTIVE_POWER')
    n.update_static_var_compensators(id='SVC', regulated_element_id='GH1')
    assert row(n.get_static_var_compensators(), 'SVC', ['target_v', 'target_q', 'regulated_element_id']) \
           == [401.0, -30.0, 'GH1']


# -------------------------------------------------------------------------- review 21 round 2: refused updates

@pytest.mark.parametrize('network, update', [
    # regulating in voltage mode without reactive power target
    ('four_substations', lambda n: n.update_static_var_compensators(id='SVC', regulation_mode='REACTIVE_POWER')),
    # regulating reactive power at a terminal, without voltage target
    ('four_substations_cgmes', lambda n: n.update_vsc_converter_stations(id='VSC2', voltage_regulator_on=True)),
])
def test_refused_mode_switch_leaves_the_network_unchanged(tmp_path, network, update):
    n = NETWORKS[network][0](tmp_path)
    xiidm = n.save_to_string()
    with n.event_recorder() as recorder:
        with pytest.raises(pp.PyPowsyblError):
            update(n)
        assert recorder.events.empty
    assert n.save_to_string() == xiidm


def test_vsc_created_with_regulated_element_and_regulator_off_can_be_switched_on():
    """Core's adder makes it regulate reactive power at the regulated element, without local reactive target."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.create_vsc_converter_stations(id='V9', voltage_level_id='S1VL2', node=98, target_v=403.0, target_q=3.0,
                                    voltage_regulator_on=False, loss_factor=1.0, regulating_element_id='GH2')
    assert row(n.get_vsc_converter_stations(), 'V9') == [403.0, 3.0, False, 'GH2']
    n.update_vsc_converter_stations(id='V9', voltage_regulator_on=True)
    assert row(n.get_vsc_converter_stations(), 'V9') == [403.0, 3.0, True, 'GH2']


# ------------------------------------------------- review 21 round 3 (R3-M1): switching on equipment without regulation

def _cgmes_with_unregulated_equipment(tmp_path: pathlib.Path) -> pp.network.Network:
    """
    The four-substations network with one more generator, through CGMES: GTH1, G9 and SHUNT come back without
    voltage regulation (core writes no RegulatingControl for them). A CGMES import gives every VSC station a
    regulation (qPccControl), an SVC without regulation cannot be exported to CGMES ("Invalid regulation mode for
    Static Var Compensator null"), and a battery comes back as a generator: those kinds have no such case.
    """
    folder = tmp_path / 'unregulated'
    if not folder.exists():
        n = pp.network.create_four_substations_node_breaker_network()
        n.create_generators(id='G9', voltage_level_id='S1VL2', node=91, max_p=10, min_p=0, target_p=5, target_q=1.0,
                            target_v=401.0)
        n.create_switches(id='SWG9', voltage_level_id='S1VL2', node1=91, node2=0, kind='BREAKER', open=False)
        folder.mkdir()
        n.save(str(folder / 'unregulated'), format='CGMES')
    return pp.network.load(str(folder))


@pytest.mark.parametrize('kind, element_id, before, switch_on', [
    ('generators', 'GTH1', {'target_v': 401.0}, {'voltage_regulator_on': True}),
    ('generators', 'G9', {'target_v': 401.0}, {'voltage_regulator_on': True}),
    ('shunt_compensators', 'SHUNT', {'target_v': 401.0}, {'target_deadband': 1.0, 'voltage_regulation_on': True}),
])
def test_switching_on_equipment_without_regulation_is_exported_or_refused(tmp_path, kind, element_id, before,
                                                                          switch_on):
    """
    The regulation created by switching on must reach the recorder (R3-M1: a regulation created already regulating
    fired no event, the exports wrote nothing and the receiver stayed without regulation). These equipments have no
    RegulatingControl in their CGMES model, so core refuses the change: the refusal is pinned.
    """
    sender = _cgmes_with_unregulated_equipment(tmp_path)
    getattr(sender, 'update_' + kind)(id=element_id, **before)
    with sender.event_recorder() as recorder:
        getattr(sender, 'update_' + kind)(id=element_id, **switch_on)
        assert 'VoltageRegulation.isRegulating' in list(recorder.events['attribute'])
        for export in (recorder.to_ssh, recorder.to_cgmes_diff):
            with pytest.raises(pp.PyPowsyblError, match=f'{element_id} has no CGMES regulating control to carry'):
                export()


def test_battery_voltage_regulation_created_regulating_is_recorded():
    n = pp.network.load(TEST_DIR / 'battery.xiidm')
    battery = n.get_batteries().index[0]
    n.remove_extensions('voltageRegulation', [battery])
    with n.event_recorder() as recorder:
        n.create_extensions('voltageRegulation', id=battery, voltage_regulator_on=True, target_v=401.0)
        assert 'VoltageRegulation.isRegulating' in list(recorder.events['attribute'])
    assert n.get_extensions('voltageRegulation').loc[battery, 'voltage_regulator_on']


# ---------------------------------- closing review C-M1: VSC stations between voltage and reactive power regulation

KEEP_PREVIOUS = {'iidm.import.cgmes.use-previous-values-during-update': 'true'}
NO_SUPERSEDES_CHECK = {'iidm.import.cgmes.diff.check-supersedes': 'false'}


def _apply(recorder, route: str, receiver: pp.network.Network, parameters=None) -> None:
    if route == 'ssh':
        receiver.update_from_string(recorder.to_ssh(), 'update_SSH.xml', parameters=KEEP_PREVIOUS)
    else:
        receiver.update_from_string(recorder.to_cgmes_diff(), 'update_SSH_DIFF.xml', parameters=parameters)


def _vsc_columns(n: pp.network.Network) -> list:
    return n.get_vsc_converter_stations()[COLUMNS].values.tolist()


@pytest.mark.parametrize('route', ['ssh', 'diff'])
def test_vsc_moves_between_voltage_and_reactive_power_through_the_change_export(tmp_path, route):
    """
    As on core 7.4 a station switched off follows its target_q, and on again its target_v; since core 7.5 that is a
    reactive power regulation at the station's own terminal, which CGMES carries (qPccControl reactivePcc).
    """
    sender = _four_substations_cgmes(tmp_path)
    receiver = _four_substations_cgmes(tmp_path)
    original = _vsc_columns(receiver)
    with sender.event_recorder() as recorder:
        sender.update_vsc_converter_stations(id='VSC1', voltage_regulator_on=False, target_q=33.0)
        assert row(sender.get_vsc_converter_stations(), 'VSC1') == [400.0, 33.0, False, 'VSC1']
        _apply(recorder, route, receiver)
        assert _vsc_columns(receiver) == _vsc_columns(sender)
        recorder.clear()

        sender.update_vsc_converter_stations(id='VSC1', target_v=401.0, voltage_regulator_on=True)
        _apply(recorder, route, receiver, NO_SUPERSEDES_CHECK)
        assert _vsc_columns(receiver) == _vsc_columns(sender)
        recorder.clear()

        # back to where it started: the receiver too
        sender.update_vsc_converter_stations(id='VSC1', target_v=400.0, target_q=500.0)
        _apply(recorder, route, receiver, NO_SUPERSEDES_CHECK)
    assert _vsc_columns(receiver) == original


@pytest.mark.parametrize('route', ['ssh', 'diff'])
def test_vsc_switched_on_and_off_again_is_exported(tmp_path, route):
    """VSC2 regulates reactive power after the CGMES import: on, then off again, is exported on both routes."""
    sender = _four_substations_cgmes(tmp_path)
    receiver = _four_substations_cgmes(tmp_path)
    with sender.event_recorder() as recorder:
        sender.update_vsc_converter_stations(id='VSC2', target_v=402.0, voltage_regulator_on=True)
        sender.update_vsc_converter_stations(id='VSC2', voltage_regulator_on=False)
        assert row(sender.get_vsc_converter_stations(), 'VSC2')[1:3] == [120.0, False]
        _apply(recorder, route, receiver)
    assert _vsc_columns(receiver) == _vsc_columns(sender)
