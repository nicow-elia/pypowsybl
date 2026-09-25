#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""Tests of the network event recorder: recording changes and exporting them as CGMES update documents."""
import datetime
import io
import pathlib
import warnings
import xml.etree.ElementTree as ET
import zipfile

import pandas as pd
import pytest

import pypowsybl as pp
from pypowsybl import PyPowsyblError

TEST_DIR = pathlib.Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'

# A partial SSH describes only what changed, so the receiver has to be told to keep the values the file does not
# mention; without it the ordinary SSH update resets them to their defaults. A difference model needs nothing.
SSH_PARAMS = {'iidm.import.cgmes.use-previous-values-during-update': 'true'}

MD = 'http://iec.ch/TC57/61970-552/ModelDescription/1#'
DM = 'http://iec.ch/TC57/61970-552/DifferenceModel/1#'


@pytest.fixture(autouse=True)
def set_up():
    pp.set_config_read(False)


def load_network() -> pp.network.Network:
    return pp.network.load(DATA_DIR / 'CGMES_Full.zip')


@pytest.fixture
def sender() -> pp.network.Network:
    return load_network()


@pytest.fixture
def receiver() -> pp.network.Network:
    return load_network()


def setpoints(n: pp.network.Network) -> dict:
    """The steady state hypothesis values a partial update is supposed to carry over, per element type.

    State columns (p, q, v, angle, connected) are deliberately left out: every SSH-type update resets them.
    """
    frames = {
        'switches': n.get_switches()[['open']],
        'loads': n.get_loads()[['p0', 'q0']],
        'generators': n.get_generators()[['target_p', 'target_q', 'target_v', 'voltage_regulator_on']],
        'ratio_tap_changers': n.get_ratio_tap_changers()[['tap', 'regulating']],
        'phase_tap_changers': n.get_phase_tap_changers()[['tap', 'regulating']],
        'shunts': n.get_shunt_compensators()[['section_count']],
    }
    return {name: frame for name, frame in frames.items() if not frame.empty}


def assert_same_setpoints(a: pp.network.Network, b: pp.network.Network) -> None:
    left = setpoints(a)
    right = setpoints(b)
    assert left.keys() == right.keys()
    for name, frame in left.items():
        pd.testing.assert_frame_equal(frame, right[name], check_exact=False, rtol=1e-6,
                                      obj=f'{name} of sender and receiver')


def first_id(frame: pd.DataFrame) -> str:
    return str(sorted(frame.index)[0])


def other_tap(row: pd.Series) -> int:
    return int(row['low_tap']) if int(row['tap']) != int(row['low_tap']) else int(row['high_tap'])


def apply_five_changes(n: pp.network.Network) -> None:
    """The five change kinds of the tender snippet."""
    n.update_switches(id=first_id(n.get_switches()), open=True)
    rtc = n.get_ratio_tap_changers()
    n.update_ratio_tap_changers(id=first_id(rtc), tap=other_tap(rtc.loc[first_id(rtc)]))
    ptc = n.get_phase_tap_changers()
    n.update_phase_tap_changers(id=first_id(ptc), tap=other_tap(ptc.loc[first_id(ptc)]))
    n.update_generators(id=first_id(n.get_generators()), target_p=42.0)
    n.update_loads(id=first_id(n.get_loads()), p0=11.0, q0=3.0)


# ---------------------------------------------------------------------------------------------- the tender pattern

def test_tender_usage_pattern(sender, receiver, tmp_path):
    network = sender
    with network.event_recorder() as recorder:
        network.update_switches(id=first_id(network.get_switches()), open=True)
        rtc = network.get_ratio_tap_changers()
        network.update_ratio_tap_changers(id=first_id(rtc), tap=other_tap(rtc.loc[first_id(rtc)]))
        ptc = network.get_phase_tap_changers()
        network.update_phase_tap_changers(id=first_id(ptc), tap=other_tap(ptc.loc[first_id(ptc)]))
        network.update_generators(id=first_id(network.get_generators()), target_p=42.0)
        network.update_loads(id=first_id(network.get_loads()), p0=11.0, q0=3.0)
        # The one deviation from the verbatim tender snippet: the exported document is not ASCII (the
        # md:Model.description inherited from CGMES_Full.zip carries curly quotes) and declares UTF-8, so the
        # stream has to be UTF-8 whatever the platform default is. Without this the test fails on a Windows
        # runner, where open() defaults to cp1252. The user guide tells users the same thing.
        with open(tmp_path / 'output.ssh', 'w', encoding='utf-8') as f:
            recorder.to_ssh(f)

    root = ET.parse(tmp_path / 'output.ssh').getroot()
    assert root.tag.endswith('RDF')
    assert root.find(f'{{{MD}}}FullModel') is not None

    # the tender file name has no _SSH, so the document is handed over under a name CGMES recognises
    receiver.update_from_string((tmp_path / 'output.ssh').read_text(encoding='utf-8'), 'output_SSH.xml',
                                parameters=SSH_PARAMS)
    assert_same_setpoints(network, receiver)


# --------------------------------------------------------------------------------------------- one change per kind

def _switch(n):
    n.update_switches(id=first_id(n.get_switches()), open=True)
    return first_id(n.get_switches())


def _open_switch_shortcut(n):
    switch_id = first_id(n.get_switches())
    n.open_switch(switch_id)
    return switch_id


def _load(n):
    n.update_loads(id=first_id(n.get_loads()), p0=13.5, q0=2.5)
    return first_id(n.get_loads())


def _generator_target_p(n):
    n.update_generators(id=first_id(n.get_generators()), target_p=33.0)
    return first_id(n.get_generators())


def _generator_target_q(n):
    n.update_generators(id=first_id(n.get_generators()), target_q=7.0, voltage_regulator_on=False)
    return first_id(n.get_generators())


def _generator_target_v(n):
    n.update_generators(id=first_id(n.get_generators()), target_v=225.0, voltage_regulator_on=True)
    # the voltage setpoint lives on the shared cim:RegulatingControl, whose mRID is not the generator's
    return None


def _rtc(n):
    rtc = n.get_ratio_tap_changers()
    n.update_ratio_tap_changers(id=first_id(rtc), tap=other_tap(rtc.loc[first_id(rtc)]))
    # a tap changer is addressed in IIDM by its transformer, in CGMES by its own cim:RatioTapChanger mRID
    return None


def _ptc(n):
    ptc = n.get_phase_tap_changers()
    n.update_phase_tap_changers(id=first_id(ptc), tap=other_tap(ptc.loc[first_id(ptc)]))
    # see _rtc: the CGMES subject is the cim:PhaseTapChanger, not the transformer
    return None


def _shunt(n):
    shunts = n.get_shunt_compensators()
    shunt_id = first_id(shunts)
    current = int(shunts.loc[shunt_id]['section_count'])
    n.update_shunt_compensators(id=shunt_id, section_count=1 if current != 1 else 0)
    return shunt_id


def _per_unit_load(n):
    n.per_unit = True
    n.update_loads(id=first_id(n.get_loads()), p0=0.15)
    n.per_unit = False
    return first_id(n.get_loads())


@pytest.mark.parametrize('change', [
    _switch, _open_switch_shortcut, _load, _generator_target_p, _generator_target_q, _generator_target_v,
    _rtc, _ptc, _shunt, _per_unit_load,
], ids=lambda f: f.__name__.lstrip('_'))
def test_each_update_kind(change):
    for as_diff in (False, True):
        network = load_network()
        target = load_network()
        with network.event_recorder() as recorder:
            changed_id = change(network)
            xml = recorder.to_cgmes_diff() if as_diff else recorder.to_ssh()
        # the element is named by its own mRID, except where the CGMES subject of the change is another object
        if changed_id is not None:
            assert changed_id in xml
        target.update_from_string(xml, 'u_SSH_DIFF.xml' if as_diff else 'u_SSH.xml',
                                  parameters=None if as_diff else SSH_PARAMS)
        assert_same_setpoints(network, target)


# --------------------------------------------------------------------------------------------------- output targets

def test_outputs_string_text_binary_path(sender, tmp_path):
    created = datetime.datetime(2026, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)

        as_string = recorder.to_ssh(created=created)
        assert as_string is not None
        assert as_string.startswith('<?xml')

        text_path = tmp_path / 'text.xml'
        with open(text_path, 'w', encoding='utf-8') as f:
            assert recorder.to_ssh(f, created=created) is None

        binary_path = tmp_path / 'binary.xml'
        with open(binary_path, 'wb') as f:
            recorder.to_ssh(f, created=created)

        string_io = io.StringIO()
        recorder.to_ssh(string_io, created=created)

        bytes_io = io.BytesIO()
        recorder.to_ssh(bytes_io, created=created)

        str_path = tmp_path / 'str.xml'
        recorder.to_ssh(str(str_path), created=created)

        path_path = tmp_path / 'path.xml'
        recorder.to_ssh(path_path, created=created)

    outputs = [as_string,
               text_path.read_text(encoding='utf-8'),
               binary_path.read_text(encoding='utf-8'),
               string_io.getvalue(),
               bytes_io.getvalue().decode('utf-8'),
               str_path.read_text(encoding='utf-8'),
               path_path.read_text(encoding='utf-8')]
    # with a fixed created time the export is reproducible, so every target holds exactly the same document
    assert all(output == outputs[0] for output in outputs)

    with pytest.raises(TypeError):
        recorder.to_ssh(object())


# ----------------------------------------------------------------------------------------- unsupported changes

def test_unsupported_raise_and_ignore(sender):
    load_id = first_id(sender.get_loads())
    generator_id = first_id(sender.get_generators())
    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=9.0)
        sender.update_generators(id=generator_id, max_p=999.0)

        with pytest.raises(PyPowsyblError, match='maxP') as excinfo:
            recorder.to_ssh()
        # the message names the offending element, not only the attribute
        assert generator_id in str(excinfo.value)
        with pytest.raises(PyPowsyblError, match='maxP') as excinfo:
            recorder.to_cgmes_diff()
        assert generator_id in str(excinfo.value)

        ssh = recorder.to_ssh(unsupported='ignore')
        assert load_id in ssh
        assert generator_id not in ssh
        diff = recorder.to_cgmes_diff(unsupported='ignore')
        assert load_id in diff

        with pytest.raises(ValueError, match='unsupported'):
            recorder.to_ssh(unsupported='x')
        with pytest.raises(ValueError, match='granularity'):
            recorder.to_cgmes_diff(granularity='x')


def test_structural_change_is_unsupported(sender):
    with sender.event_recorder() as recorder:
        sender.remove_elements(first_id(sender.get_loads()))
        with pytest.raises(PyPowsyblError):
            recorder.to_ssh()
        # ignoring it yields a document without that change
        assert recorder.to_ssh(unsupported='ignore') is not None


def test_load_flow_inside_block_is_unsupported(sender):
    load_id = first_id(sender.get_loads())
    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=9.0)
        pp.loadflow.run_ac(sender)
        with pytest.raises(PyPowsyblError):
            recorder.to_ssh()
        ssh = recorder.to_ssh(unsupported='ignore')
    assert load_id in ssh


# --------------------------------------------------------------------------------------------------- metadata

def test_metadata(sender):
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)

        xml = recorder.to_ssh(
            model_id='urn:uuid:11111111-2222-3333-4444-555555555555',
            version=7,
            description='a described change',
            modeling_authority_set='http://example.com/OperationalPlanning',
            supersedes=['urn:uuid:aaaaaaaa-0000-0000-0000-000000000001'],
            depends_on='urn:uuid:bbbbbbbb-0000-0000-0000-000000000002',
            scenario_time=datetime.datetime(2026, 2, 3, 4, 5, tzinfo=datetime.timezone.utc),
            created=datetime.datetime(2026, 2, 3, 6, 7))
        model = ET.fromstring(xml).find(f'{{{MD}}}FullModel')
        assert model is not None
        texts = {child.tag: child.text for child in model}
        assert texts[f'{{{MD}}}Model.version'] == '7'
        assert texts[f'{{{MD}}}Model.description'] == 'a described change'
        assert texts[f'{{{MD}}}Model.modelingAuthoritySet'] == 'http://example.com/OperationalPlanning'
        assert 'urn:uuid:aaaaaaaa-0000-0000-0000-000000000001' in xml
        assert 'urn:uuid:bbbbbbbb-0000-0000-0000-000000000002' in xml
        assert '2026-02-03T04:05' in xml
        assert '2026-02-03T06:07' in xml

        # an empty sequence removes the default
        assert 'Model.Supersedes' in recorder.to_ssh()
        assert 'Model.Supersedes' not in recorder.to_ssh(supersedes=[])

        with pytest.raises(PyPowsyblError, match='model_id'):
            recorder.to_ssh(modelid='x')

        # a dict addresses one profile of a difference model export
        diff = recorder.to_cgmes_diff(model_id={'SSH': 'urn:uuid:dddddddd-0000-0000-0000-000000000000'})
        assert 'urn:uuid:dddddddd-0000-0000-0000-000000000000' in diff
        assert ET.fromstring(diff).find(f'{{{DM}}}DifferenceModel') is not None

        full = recorder.to_cgmes_diff(granularity='full_object')
        delta = recorder.to_cgmes_diff(granularity='changed_only')
        assert len(delta) < len(full)


def test_naive_datetime_is_utc(sender):
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)
        naive = recorder.to_ssh(created=datetime.datetime(2026, 2, 3, 6, 7))
        aware = recorder.to_ssh(created=datetime.datetime(2026, 2, 3, 6, 7, tzinfo=datetime.timezone.utc))
    assert naive == aware


# ------------------------------------------------------------------------------------------------- events view

def test_events_view_len_clear(sender):
    load_id = first_id(sender.get_loads())
    recorder = sender.event_recorder()
    assert len(recorder) == 0
    assert 'recording=False' in repr(recorder)

    with recorder:
        assert 'recording=True' in repr(recorder)
        sender.update_loads(id=load_id, p0=1.0)
        sender.update_loads(id=load_id, p0=2.0)

    events = recorder.events
    assert events.index.name == 'index'
    assert list(events.columns) == ['type', 'id', 'extension', 'attribute', 'variant', 'old_value', 'new_value']
    # not compacted: two changes of one attribute are two rows
    assert len(events) == 2
    assert len(recorder) == 2
    first = events.iloc[0]
    assert first['type'] == 'UPDATE'
    assert first['id'] == load_id
    assert first['attribute'] == 'p0'
    assert first['extension'] == ''
    assert events.iloc[1]['new_value'] == '2.0'

    # ... but the export keeps only the last value
    ssh = recorder.to_ssh()
    values = [element.text for element in ET.fromstring(ssh).iter()
              if element.tag.endswith('EnergyConsumer.p')]
    assert [float(value) for value in values] == [2.0]

    recorder.clear()
    assert len(recorder) == 0
    assert recorder.events.empty
    assert load_id not in recorder.to_ssh()


# --------------------------------------------------------------------------------------------------- lifecycle

def test_lifecycle(sender):
    load_id = first_id(sender.get_loads())

    # a recorder that was never entered records nothing
    recorder = sender.event_recorder()
    assert not recorder.recording
    assert recorder.network is sender
    sender.update_loads(id=load_id, p0=1.0)
    assert len(recorder) == 0

    # an exception inside the block propagates and leaves the recorder stopped, with its events
    with pytest.raises(RuntimeError):
        with recorder:
            sender.update_loads(id=load_id, p0=2.0)
            raise RuntimeError('boom')
    assert not recorder.recording
    assert len(recorder) == 1
    assert load_id in recorder.to_ssh()

    # changes after the block are not recorded, re-entering resumes and appends
    sender.update_loads(id=load_id, p0=3.0)
    assert len(recorder) == 1
    with recorder:
        sender.update_loads(id=load_id, p0=4.0)
    assert len(recorder) == 2

    # start/stop without the context manager
    recorder.start()
    assert recorder.recording
    sender.update_loads(id=load_id, p0=5.0)
    recorder.stop()
    assert not recorder.recording
    assert len(recorder) == 3

    # two recorders of the same network are independent
    other = sender.event_recorder()
    with other:
        sender.update_loads(id=load_id, p0=6.0)
    assert len(other) == 1
    assert len(recorder) == 3

    # nested recorders: both see the inner change, only the outer one the outer change
    outer = sender.event_recorder()
    with outer:
        sender.update_loads(id=load_id, p0=7.0)
        inner = sender.event_recorder()
        with inner:
            sender.update_loads(id=load_id, p0=8.0)
    assert len(inner) == 1
    assert len(outer) == 2

    # deleting a running recorder detaches its listener and does not break further updates
    doomed = sender.event_recorder()
    doomed.start()
    del doomed
    sender.update_loads(id=load_id, p0=9.0)


# -------------------------------------------------------------------------------------------------- round trip

def test_round_trip_ssh_and_diff(sender, tmp_path):
    receivers = [load_network() for _ in range(4)]
    with sender.event_recorder() as recorder:
        apply_five_changes(sender)

        ssh_file = tmp_path / 'update_SSH.xml'
        recorder.to_ssh(ssh_file)

        directory = tmp_path / 'd'
        recorder.to_cgmes_diffs(directory)

        buffer = io.BytesIO()
        recorder.to_cgmes_diffs(buffer)

        archive = tmp_path / 'd.zip'
        recorder.to_cgmes_diffs(archive)

        assert recorder.touched_profiles() == ['SSH']
        assert list(recorder.to_cgmes_diffs()) == ['SSH']

    receivers[0].update_from_file(ssh_file, parameters=SSH_PARAMS)
    receivers[1].update_from_file(directory / 'update_SSH_DIFF.xml')
    buffer.seek(0)
    receivers[2].update_from_binary_buffer(buffer)
    receivers[3].update_from_file(archive)

    for receiver_network in receivers:
        assert_same_setpoints(sender, receiver_network)

    with zipfile.ZipFile(archive) as zf:
        assert zf.namelist() == ['update_SSH_DIFF.xml']


def test_to_cgmes_diffs_empty_recording(sender, tmp_path):
    recorder = sender.event_recorder()
    assert recorder.to_cgmes_diffs() == {}
    assert recorder.touched_profiles() == []
    target = tmp_path / 'empty'
    assert recorder.to_cgmes_diffs(target) is None
    assert list(target.iterdir()) == []


def test_to_cgmes_diffs_rejects_a_bad_target(sender, tmp_path):
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)
        with pytest.raises(TypeError):
            recorder.to_cgmes_diffs(object())

        # a directory target that is an existing file says so, instead of a bare FileExistsError
        not_a_directory = tmp_path / 'already_a_file'
        not_a_directory.write_text('', encoding='utf-8')
        with pytest.raises(NotADirectoryError, match='already_a_file'):
            recorder.to_cgmes_diffs(not_a_directory)


def test_non_utf8_text_stream_warns(sender, tmp_path):
    """The document declares UTF-8, so a text stream with another encoding would produce a lying declaration."""
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)

        with open(tmp_path / 'latin.xml', 'w', encoding='cp1252', errors='replace') as f:
            with pytest.warns(UserWarning, match='utf-8'):
                recorder.to_ssh(f)

        # the documented way round, and the binary and path targets, are silent
        with warnings.catch_warnings():
            warnings.simplefilter('error')
            with open(tmp_path / 'utf8.xml', 'w', encoding='utf-8') as f:
                recorder.to_ssh(f)
            with open(tmp_path / 'bytes.xml', 'wb') as f:
                recorder.to_ssh(f)
            recorder.to_ssh(tmp_path / 'path.xml')


def test_profile_keyed_metadata_rejects_a_non_string_key(sender):
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)
        with pytest.raises(TypeError, match='model_id'):
            recorder.to_cgmes_diff(model_id={1: 'urn:uuid:0'})


def test_variant_operations_are_unsupported(sender):
    """A variant event is a recorded change of its own, and no update document can carry it."""
    with sender.event_recorder() as recorder:
        sender.update_loads(id=first_id(sender.get_loads()), p0=9.0)
        sender.clone_variant('InitialState', 'other')
        assert 'VARIANT' in list(recorder.events['type'])
        with pytest.raises(PyPowsyblError):
            recorder.to_ssh()
        # ignoring it still exports the attribute change
        assert first_id(sender.get_loads()) in recorder.to_ssh(unsupported='ignore')


# ------------------------------------------------------------------------------------------------ diff chains

def test_diff_chain(sender):
    first_model = 'urn:uuid:12345678-0000-0000-0000-000000000001'
    load_id = first_id(sender.get_loads())
    receiver_ok = load_network()
    receiver_ko = load_network()
    receiver_forced = load_network()

    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=9.0)
        diff1 = recorder.to_cgmes_diff(model_id=first_model)
        recorder.clear()
        sender.update_loads(id=load_id, p0=19.0)
        chained = recorder.to_cgmes_diff(supersedes=[first_model], version=3)
        unchained = recorder.to_cgmes_diff()

    for receiver_network in (receiver_ok, receiver_ko, receiver_forced):
        receiver_network.update_from_string(diff1, 'd1_SSH_DIFF.xml')

    receiver_ok.update_from_string(chained, 'd2_SSH_DIFF.xml')
    assert receiver_ok.get_loads().loc[load_id]['p0'] == pytest.approx(19.0)

    with pytest.raises(PyPowsyblError):
        receiver_ko.update_from_string(unchained, 'd2_SSH_DIFF.xml')

    receiver_forced.update_from_string(unchained, 'd2_SSH_DIFF.xml',
                                       parameters={'iidm.import.cgmes.diff.check-supersedes': 'false'})
    assert receiver_forced.get_loads().loc[load_id]['p0'] == pytest.approx(19.0)


# ------------------------------------------- equipment changes: operational limits, voltage limits, impedances

# CGMES_Full.zip is a CGMES 3 (CIM100) model, where an operational limit value is steady state data: a limit
# change travels in the SSH document. MicroGrid is CGMES 2.4.15, where the same value is equipment data and only
# an EQ difference model can carry it.
MICRO_GRID = DATA_DIR / 'MicroGridTestConfiguration_T4_BE_BB_Complete_v2.zip'


def equipment_state(n: pp.network.Network) -> dict:
    """The equipment values an EQ difference model can carry, per element type."""
    return {
        'loading_limits': n.get_loading_limits(show_inactive_sets=True)[['value']].sort_index(),
        'lines': n.get_lines()[['r', 'x', 'g1', 'b1', 'g2', 'b2']].sort_index(),
        'boundary_lines': n.get_boundary_lines()[['r', 'x', 'g', 'b']].sort_index(),
        'voltage_levels': n.get_voltage_levels()[['high_voltage_limit', 'low_voltage_limit']].sort_index(),
    }


def assert_same_equipment(a: pp.network.Network, b: pp.network.Network) -> None:
    left = equipment_state(a)
    right = equipment_state(b)
    for name, frame in left.items():
        pd.testing.assert_frame_equal(frame, right[name], check_exact=False, rtol=1e-9,
                                      obj=f'{name} of sender and receiver')


def line_current_limits(n: pp.network.Network) -> pd.DataFrame:
    """Every current limit of a line, as rows of :meth:`get_loading_limits` with the index flattened.

    Only the limits of a line imported from one CGMES ACLineSegment: a merged line has two of them (see
    :func:`ac_line_segments`) and its limit sets carry no CGMES identifier.
    """
    limits = n.get_loading_limits(show_inactive_sets=True).reset_index()
    rows = limits[limits['element_id'].isin(ac_line_segments(n)) & (limits['type'] == 'CURRENT')]
    return rows.sort_values(['element_id', 'side', 'group_name', 'acceptable_duration'])


def real_permanent_limits(n: pp.network.Network) -> pd.DataFrame:
    """The permanent current limits a CGMES CurrentLimit object was imported as.

    A CGMES limit set that states no permanent limit gets one *synthesized* by the import, at
    ``missing-permanent-limit-percentage`` (100 by default) of the lowest temporary limit of the set. That limit
    has no CGMES object behind it, so it cannot be changed -- see
    :func:`test_a_synthesized_permanent_limit_cannot_be_changed`. It is recognised here by being exactly the
    lowest temporary limit of its own set, which is what the import made it.
    """
    rows = line_current_limits(n)
    lowest = rows[rows['acceptable_duration'] != -1].groupby(['element_id', 'side', 'group_name'])['value'].min()
    permanent = rows[rows['acceptable_duration'] == -1]
    keys = zip(permanent['element_id'], permanent['side'], permanent['group_name'], permanent['value'])
    return permanent[[lowest.get(key[:3]) != key[3] for key in keys]]


def a_current_limit(n: pp.network.Network, acceptable_duration: int) -> pd.Series:
    """One changeable current limit of a line: its permanent limit, or a temporary one of that duration."""
    rows = (real_permanent_limits(n) if acceptable_duration == -1
            else line_current_limits(n)[line_current_limits(n)['acceptable_duration'] == acceptable_duration])
    assert not rows.empty, f'the fixture has no line current limit of duration {acceptable_duration}'
    return rows.iloc[0]


def change_limit(n: pp.network.Network, row: pd.Series, value: float) -> None:
    """The bulk limit updater, addressing exactly the limit of that row."""
    n.update_loading_limits(element_id=row['element_id'], side=row['side'], type=row['type'],
                            acceptable_duration=int(row['acceptable_duration']),
                            group_name=row['group_name'], value=value)


def ac_line_segments(n: pp.network.Network) -> list:
    """The lines *one* CGMES ACLineSegment was imported as; only those have one r/x/gch/bch to write back.

    A line whose id holds a ``' + '`` is a merged line, two CGMES ACLineSegments joined at a boundary point and
    imported as one IIDM line, so its impedance belongs to no single CGMES object. Lines with a non-positive
    reactance are left out too: CGMES_Full.zip holds one with ``x = -31.83``, and an impedance a CGMES file
    cannot state (``r``, ``x`` must be finite and ``>= 0``) is refused by the export.
    """
    lines = n.get_lines(all_attributes=True)
    original = lines.get('CGMES.originalClass')
    ids = lines.index if original is None else lines.index[original.isin(('ACLineSegment', ''))]
    return sorted(line_id for line_id in ids
                  if ' + ' not in line_id and lines.loc[line_id]['r'] >= 0 and lines.loc[line_id]['x'] > 0)


def test_loading_limit_change_travels_in_the_ssh_diff(sender, receiver):
    row = a_current_limit(sender, acceptable_duration=-1)
    with sender.event_recorder() as recorder:
        change_limit(sender, row, row['value'] + 50.0)
        # a CGMES 3 limit value is steady state data
        assert recorder.touched_profiles() == ['SSH']
        diff = recorder.to_cgmes_diff()
    assert 'CurrentLimit.value' in diff
    receiver.update_from_string(diff, 'limits_SSH_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_loading_limit_change_travels_in_a_partial_ssh(sender, receiver):
    """A CGMES 3 limit value is an SSH value, so the partial SSH file carries it too."""
    row = a_current_limit(sender, acceptable_duration=-1)
    with sender.event_recorder() as recorder:
        change_limit(sender, row, row['value'] + 70.0)
        ssh = recorder.to_ssh()
    assert 'CurrentLimit.value' in ssh
    receiver.update_from_string(ssh, 'limits_SSH.xml', parameters=SSH_PARAMS)
    assert_same_equipment(sender, receiver)


def test_temporary_limit_change_travels(sender, receiver):
    row = a_current_limit(sender, acceptable_duration=10)
    with sender.event_recorder() as recorder:
        change_limit(sender, row, row['value'] + 30.0)
        diff = recorder.to_cgmes_diff()
    receiver.update_from_string(diff, 'tatl_SSH_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_several_limits_in_one_dataframe(sender, receiver):
    """The bulk updater with a dataframe of several limits: one document carries all of them."""
    rows = pd.concat([real_permanent_limits(sender).head(2),
                      line_current_limits(sender)[line_current_limits(sender)['acceptable_duration'] == 10].head(2)])
    assert len(rows) == 4
    # the index of the loading limits dataframe is what addresses a limit, and the updater expects it back
    update = rows.set_index(['element_id', 'side', 'type', 'acceptable_duration', 'group_name'])[['value']] + 25.0

    with sender.event_recorder() as recorder:
        sender.update_loading_limits(update)
        assert len(recorder) == 4
        diff = recorder.to_cgmes_diff()
    receiver.update_from_string(diff, 'limits_SSH_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_a_synthesized_permanent_limit_cannot_be_changed(sender):
    """A CGMES limit set of temporary limits only gets its permanent limit synthesized by the import.

    There is no CGMES OperationalLimit behind it, so changing it is refused; its temporary limits still travel.
    """
    rows = line_current_limits(sender)
    real = {tuple(key) for key in real_permanent_limits(sender)[['element_id', 'side', 'group_name']].to_numpy()}
    permanent = rows[rows['acceptable_duration'] == -1]
    keys = permanent[['element_id', 'side', 'group_name']].to_numpy()
    synthesized = permanent[[tuple(key) not in real for key in keys]]
    assert not synthesized.empty, 'the fixture is expected to hold a temporary-limits-only CGMES limit set'
    row = synthesized.iloc[0]

    with sender.event_recorder() as recorder:
        change_limit(sender, row, row['value'] + 10.0)
        with pytest.raises(PyPowsyblError, match='no CGMES OperationalLimit id') as excinfo:
            recorder.to_cgmes_diff(profile='SSH')
        assert row['element_id'] in str(excinfo.value)
        assert recorder.to_cgmes_diff(profile='SSH', unsupported='ignore') is not None


def test_line_impedance_travels_in_the_eq_diff(sender, receiver):
    line_id = ac_line_segments(sender)[0]
    r = float(sender.get_lines().loc[line_id]['r'])
    with sender.event_recorder() as recorder:
        sender.update_lines(id=line_id, r=r + 0.5)
        # an impedance is equipment data whatever the CIM version, so no SSH document can carry it
        assert recorder.touched_profiles() == ['EQ']
        with pytest.raises(PyPowsyblError, match='EQ profile'):
            recorder.to_ssh()
        assert list(recorder.to_cgmes_diffs()) == ['EQ']
        diff = recorder.to_cgmes_diff(profile='EQ')
    assert 'ACLineSegment.r' in diff
    assert line_id in diff
    receiver.update_from_string(diff, 'impedance_EQ_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_several_line_impedances_in_one_dataframe(sender, receiver, tmp_path):
    """`update_lines` with a dataframe: r, x and the two shunt admittances of every such line at once.

    CGMES holds one total ``gch``/``bch`` that the import splits equally, so ``g1`` and ``g2`` (and ``b1``,
    ``b2``) have to move together; a one-sided change is refused.
    """
    line_ids = ac_line_segments(sender)
    assert len(line_ids) >= 2
    before = sender.get_lines().loc[line_ids]
    update = pd.DataFrame(index=pd.Series(line_ids, name='id'),
                          data={'r': before['r'] + 0.25,
                                'x': before['x'] + 1.5,
                                'g1': before['g1'] + 1e-6,
                                'g2': before['g2'] + 1e-6,
                                'b1': before['b1'] + 2e-6,
                                'b2': before['b2'] + 2e-6})
    with sender.event_recorder() as recorder:
        sender.update_lines(update)
        eq_file = tmp_path / 'lines_EQ_DIFF.xml'
        recorder.to_cgmes_diff(eq_file, profile='EQ')
    document = eq_file.read_text(encoding='utf-8')
    assert 'ACLineSegment.gch' in document and 'ACLineSegment.bch' in document

    receiver.update_from_file(eq_file)
    assert_same_equipment(sender, receiver)


def test_asymmetric_shunt_admittance_is_unsupported(sender):
    line_id = ac_line_segments(sender)[0]
    g1 = float(sender.get_lines().loc[line_id]['g1'])
    with sender.event_recorder() as recorder:
        sender.update_lines(id=line_id, g1=g1 + 1e-6)
        with pytest.raises(PyPowsyblError, match='g1 == g2') as excinfo:
            recorder.to_cgmes_diff(profile='EQ')
        assert line_id in str(excinfo.value)
        # ignoring it yields a document without that change
        assert line_id not in recorder.to_cgmes_diff(profile='EQ', unsupported='ignore')


def test_boundary_line_impedance_travels_in_the_eq_diff():
    """A boundary line is one CGMES ACLineSegment at a boundary point, so all four values map 1:1.

    Unlike a line, its ``g`` and ``b`` are the whole ``gch``/``bch`` and are not split, so they move on their own.
    Only MicroGrid has boundary lines; the merged model of CGMES_Full.zip has none.
    """
    sender = pp.network.load(MICRO_GRID)
    receiver = pp.network.load(MICRO_GRID)
    boundary_lines = sender.get_boundary_lines()
    assert not boundary_lines.empty
    boundary_line_id = str(sorted(boundary_lines.index)[0])
    before = boundary_lines.loc[boundary_line_id]

    with sender.event_recorder() as recorder:
        sender.update_boundary_lines(id=boundary_line_id, r=float(before['r']) + 0.3,
                                    x=float(before['x']) + 1.0, g=float(before['g']) + 1e-6,
                                    b=float(before['b']) + 2e-6)
        assert recorder.touched_profiles() == ['EQ']
        diff = recorder.to_cgmes_diff(profile='EQ')
    assert 'ACLineSegment.gch' in diff and 'ACLineSegment.bch' in diff
    receiver.update_from_string(diff, 'boundary_EQ_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_a_merged_line_impedance_is_rejected_by_the_export(sender, receiver):
    """A merged line has no single CGMES ACLineSegment, so its impedance cannot be exchanged.

    Powsybl names an object that stands for two by joining their identifiers with ``" + "``, and a difference
    model names *one* existing CGMES object per statement. The export therefore treats the change as an
    unsupported change: with ``unsupported='raise'`` (the default) it says so, naming the pair, and with
    ``unsupported='ignore'`` it leaves the change out of the document. Either way nothing that cannot be applied
    reaches a receiver -- the error arrives on the *sending* side, where the change was made.
    """
    merged = [line_id for line_id in sender.get_lines().index if ' + ' in line_id]
    assert merged, 'the fixture is expected to hold a merged line'
    before = equipment_state(receiver)

    with sender.event_recorder() as recorder:
        sender.update_lines(id=merged[0], r=float(sender.get_lines().loc[merged[0]]['r']) + 1.0)
        with pytest.raises(PyPowsyblError, match='not a single CGMES master resource identifier'):
            recorder.to_cgmes_diff(profile='EQ')
        # The very same recording exports cleanly once the change is declared unexportable
        diff = recorder.to_cgmes_diff(profile='EQ', unsupported='ignore')

    assert merged[0] not in diff
    receiver.update_from_string(diff, 'merged_EQ_DIFF.xml')
    for name, frame in before.items():
        pd.testing.assert_frame_equal(frame, equipment_state(receiver)[name], obj=f'{name} of the receiver')


def test_voltage_level_limits_travel_in_the_eq_diff(sender, receiver):
    """CGMES_Full.zip has no cim:VoltageLimit objects, so the limits are the VoltageLevel attributes."""
    voltage_levels = sender.get_voltage_levels()
    vl_id = str(voltage_levels['high_voltage_limit'].dropna().index[0])
    high = float(voltage_levels.loc[vl_id]['high_voltage_limit'])
    low = float(voltage_levels.loc[vl_id]['low_voltage_limit'])
    with sender.event_recorder() as recorder:
        sender.update_voltage_levels(id=vl_id, high_voltage_limit=high + 2.0, low_voltage_limit=low - 2.0)
        assert recorder.touched_profiles() == ['EQ']
        diff = recorder.to_cgmes_diff(profile='EQ')
    assert 'VoltageLevel.highVoltageLimit' in diff
    assert 'VoltageLevel.lowVoltageLimit' in diff
    receiver.update_from_string(diff, 'voltage_EQ_DIFF.xml')
    assert_same_equipment(sender, receiver)


def test_mixed_steady_state_and_equipment_changes(sender, receiver, tmp_path):
    """One recording, two documents: the setpoint goes to the SSH difference, the impedance to the EQ one."""
    load_id = first_id(sender.get_loads())
    line_id = ac_line_segments(sender)[0]
    row = a_current_limit(sender, acceptable_duration=-1)
    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=17.0)
        sender.update_lines(id=line_id, x=float(sender.get_lines().loc[line_id]['x']) + 2.0)
        change_limit(sender, row, row['value'] + 40.0)

        assert recorder.touched_profiles() == ['EQ', 'SSH']
        # without a profile the export refuses to pick one
        with pytest.raises(PyPowsyblError):
            recorder.to_cgmes_diff()

        archive = tmp_path / 'mixed.zip'
        recorder.to_cgmes_diffs(archive)

    with zipfile.ZipFile(archive) as zf:
        assert zf.namelist() == ['update_EQ_DIFF.xml', 'update_SSH_DIFF.xml']

    receiver.update_from_file(archive)
    assert_same_equipment(sender, receiver)
    assert_same_setpoints(sender, receiver)


def test_revert_an_equipment_change(sender, receiver):
    """A difference model holds both directions, but a receiver applies the forward one.

    The way back is therefore a second difference, recorded while the sender restores the values: the receiver
    ends up exactly where it started.
    """
    line_id = ac_line_segments(sender)[0]
    original = equipment_state(receiver)
    before = sender.get_lines().loc[line_id]

    with sender.event_recorder() as recorder:
        sender.update_lines(id=line_id, r=float(before['r']) + 0.75)
        forward = recorder.to_cgmes_diff(profile='EQ')
        recorder.clear()

        receiver.update_from_string(forward, 'forward_EQ_DIFF.xml')
        assert_same_equipment(sender, receiver)

        sender.update_lines(id=line_id, r=float(before['r']))
        backward = recorder.to_cgmes_diff(profile='EQ')

    receiver.update_from_string(backward, 'backward_EQ_DIFF.xml',
                               parameters={'iidm.import.cgmes.diff.check-supersedes': 'false'})
    reverted = equipment_state(receiver)
    for name, frame in original.items():
        pd.testing.assert_frame_equal(frame, reverted[name], check_exact=False, rtol=1e-9,
                                      obj=f'{name} after the revert')


def test_cim16_limit_change_travels_in_the_eq_diff():
    """In CGMES 2.4.15 a limit value is equipment data, so the very same change routes to the EQ profile."""
    sender = pp.network.load(MICRO_GRID)
    receiver = pp.network.load(MICRO_GRID)
    row = a_current_limit(sender, acceptable_duration=-1)
    with sender.event_recorder() as recorder:
        change_limit(sender, row, row['value'] + 60.0)
        assert recorder.touched_profiles() == ['EQ']
        with pytest.raises(PyPowsyblError, match='EQ profile'):
            recorder.to_ssh()
        diff = recorder.to_cgmes_diff(profile='EQ')
    assert 'CurrentLimit.value' in diff
    receiver.update_from_string(diff, 'limits_EQ_DIFF.xml')
    assert_same_equipment(sender, receiver)


# ------------------------------------------------------------------------------------------ update_from_string

def test_update_from_string_partial_ssh_fixture():
    network = pp.network.load(DATA_DIR / 'load_EQ.xml')
    network.update_from_string((DATA_DIR / 'load_SSH.xml').read_text(encoding='utf-8'), 'load_SSH.xml')
    assert network.get_loads()['p0']['EnergyConsumer'] == 10.0
