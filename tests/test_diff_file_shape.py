#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
The shape of a difference model file: what ``to_cgmes_diff`` writes and what ``update_from_file`` reads.

The rule: one touched profile is one XML document; several are one zip archive; a path ending in ``.zip`` is always
a zip archive. On the way back a zip archive is read as a whole, and a plain difference model file is read alone,
whatever its extension and whatever lies next to it. The read cases are the ones that failed before this rule: a
same-prefix sibling next to the file, a ``.diff`` extension, and a relative path.
"""
import io
import os
import pathlib
import warnings
import zipfile

import pytest

import pypowsybl as pp
from pypowsybl import PyPowsyblError
from pypowsybl.network.impl.network import _is_difference_model_file, _is_zip_file

from test_network_event_recorder import ac_line_segments, first_id, load_network

TEST_DIR = pathlib.Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'


@pytest.fixture
def sender() -> pp.network.Network:
    return load_network()


def p0(network: pp.network.Network, load_id: str) -> float:
    return float(network.get_loads().loc[load_id]['p0'])


def x(network: pp.network.Network, line_id: str) -> float:
    return float(network.get_lines().loc[line_id]['x'])


def record_both_profiles(sender: pp.network.Network):
    """A recorder holding one steady state change (a load) and one equipment change (a line reactance)."""
    load_id = first_id(sender.get_loads())
    line_id = ac_line_segments(sender)[0]
    new_x = x(sender, line_id) + 2.0
    recorder = sender.event_recorder()
    with recorder:
        sender.update_loads(id=load_id, p0=17.0)
        sender.update_lines(id=line_id, x=new_x)
    assert recorder.touched_profiles() == ['EQ', 'SSH']
    return recorder, load_id, line_id, new_x


def record_load(sender: pp.network.Network, value: float = 9.0):
    load_id = first_id(sender.get_loads())
    recorder = sender.event_recorder()
    with recorder:
        sender.update_loads(id=load_id, p0=value)
    return recorder, load_id


# ------------------------------------------------------------------------------------------------- write side

def test_one_profile_is_one_xml_file(sender, tmp_path):
    recorder, load_id = record_load(sender)
    target = tmp_path / 'out_SSH_DIFF.xml'
    assert recorder.to_cgmes_diff(target) is None
    assert target.read_text(encoding='utf-8').startswith('<?xml')
    assert _is_difference_model_file(str(target))
    assert sorted(p.name for p in tmp_path.iterdir()) == ['out_SSH_DIFF.xml']

    receiver = load_network()
    receiver.update_from_file(target)
    assert p0(receiver, load_id) == pytest.approx(9.0)


def test_several_profiles_to_a_zip_path(sender, tmp_path):
    recorder, load_id, line_id, new_x = record_both_profiles(sender)
    target = tmp_path / 'out.zip'
    with warnings.catch_warnings():
        warnings.simplefilter('error')  # a .zip path is exactly what is written: no warning
        written = recorder.to_cgmes_diff(target)
    assert written == target
    with zipfile.ZipFile(target) as archive:
        assert archive.namelist() == ['out_EQ_DIFF.xml', 'out_SSH_DIFF.xml']

    receiver = load_network()
    receiver.update_from_file(target)
    assert p0(receiver, load_id) == pytest.approx(17.0)
    assert x(receiver, line_id) == pytest.approx(new_x)


def test_several_profiles_to_an_xml_path_write_a_zip_next_to_it(sender, tmp_path):
    recorder, load_id, _, _ = record_both_profiles(sender)
    target = tmp_path / 'out_SSH_DIFF.xml'
    with pytest.warns(UserWarning, match='out_SSH_DIFF.zip'):
        written = recorder.to_cgmes_diff(str(target))
    assert written == tmp_path / 'out_SSH_DIFF.zip'
    # no XML file, not even half of one
    assert sorted(p.name for p in tmp_path.iterdir()) == ['out_SSH_DIFF.zip']
    with zipfile.ZipFile(written) as archive:
        # the stem loses its _SSH_DIFF, so the entries do not read out_SSH_DIFF_EQ_DIFF.xml
        assert archive.namelist() == ['out_EQ_DIFF.xml', 'out_SSH_DIFF.xml']

    receiver = load_network()
    receiver.update_from_file(written)
    assert p0(receiver, load_id) == pytest.approx(17.0)


def test_several_profiles_with_another_extension(sender, tmp_path):
    recorder, _, _, _ = record_both_profiles(sender)
    with pytest.warns(UserWarning):
        written = recorder.to_cgmes_diff(tmp_path / 'change.diff')
    assert written == tmp_path / 'change.zip'
    with zipfile.ZipFile(written) as archive:
        assert archive.namelist() == ['change_EQ_DIFF.xml', 'change_SSH_DIFF.xml']


def test_several_profiles_cannot_go_to_a_string_or_a_text_stream(sender):
    recorder, _, _, _ = record_both_profiles(sender)
    with pytest.raises(PyPowsyblError, match=r"\['EQ', 'SSH'\].*to_cgmes_diffs"):
        recorder.to_cgmes_diff()
    with pytest.raises(PyPowsyblError, match='text file object'):
        recorder.to_cgmes_diff(io.StringIO())
    # a profile still selects one document, as before
    assert 'DifferenceModel' in recorder.to_cgmes_diff(profile='SSH', unsupported='ignore')


def test_several_profiles_to_a_binary_buffer(sender):
    recorder, load_id, line_id, new_x = record_both_profiles(sender)
    buffer = io.BytesIO()
    assert recorder.to_cgmes_diff(buffer) is None
    assert buffer.getvalue().startswith(b'PK\x03\x04')
    with zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as archive:
        assert archive.namelist() == ['update_EQ_DIFF.xml', 'update_SSH_DIFF.xml']

    buffer.seek(0)
    receiver = load_network()
    receiver.update_from_binary_buffer(buffer)
    assert p0(receiver, load_id) == pytest.approx(17.0)
    assert x(receiver, line_id) == pytest.approx(new_x)


def test_a_zip_path_is_a_zip_also_for_one_profile(sender, tmp_path):
    recorder, load_id = record_load(sender)
    written = recorder.to_cgmes_diff(tmp_path / 'x_SSH_DIFF.zip')
    assert written == tmp_path / 'x_SSH_DIFF.zip'
    with zipfile.ZipFile(written) as archive:
        assert archive.namelist() == ['x_SSH_DIFF.xml']
    receiver = load_network()
    receiver.update_from_file(written)
    assert p0(receiver, load_id) == pytest.approx(9.0)

    # an empty recording too: the empty SSH difference, still in a zip
    empty = load_network().event_recorder()
    with zipfile.ZipFile(empty.to_cgmes_diff(tmp_path / 'empty.zip')) as archive:
        assert archive.namelist() == ['empty_SSH_DIFF.xml']


def test_one_profile_output_is_unchanged(sender):
    """The single-profile document is the same with or without a profile named (up to its fresh model id)."""
    recorder, _ = record_load(sender)
    implicit = recorder.to_cgmes_diff(model_id='urn:uuid:00000000-0000-0000-0000-00000000000a',
                                      created='2026-01-01T00:00:00Z')
    explicit = recorder.to_cgmes_diff(profile='SSH', model_id='urn:uuid:00000000-0000-0000-0000-00000000000a',
                                      created='2026-01-01T00:00:00Z')
    assert implicit == explicit


# -------------------------------------------------------------------------------------------------- read side

def test_a_plain_file_is_read_alone_next_to_a_same_prefix_sibling(sender, tmp_path):
    """The owner's failure: a second file with the same prefix made it 'two models of the SSH profile'."""
    first_model = 'urn:uuid:12345678-0000-0000-0000-0000000000d1'
    load_id = first_id(sender.get_loads())
    target = tmp_path / 'out_SSH_DIFF.xml'
    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=9.0)
        recorder.to_cgmes_diff(target, model_id=first_model)
        recorder.clear()
        sender.update_loads(id=load_id, p0=19.0)
        # a sibling that chains onto the file: read along, it would move the load to 19
        recorder.to_cgmes_diff(tmp_path / 'out_SSH_DIFF_next.xml', supersedes=[first_model])
    with zipfile.ZipFile(tmp_path / 'out_SSH_DIFF.zip', 'w') as archive:
        archive.write(target, 'out_SSH_DIFF.xml')

    receiver = load_network()
    receiver.update_from_file(target)
    assert p0(receiver, load_id) == pytest.approx(9.0)


def test_a_plain_file_with_another_extension_is_read(sender, tmp_path):
    recorder, load_id = record_load(sender)
    target = tmp_path / 'out.diff'
    with open(target, 'w', encoding='utf-8') as stream:
        recorder.to_cgmes_diff(stream)
    receiver = load_network()
    receiver.update_from_file(str(target))
    assert p0(receiver, load_id) == pytest.approx(9.0)


def test_a_relative_path_is_read_from_the_python_working_directory(sender, tmp_path, monkeypatch):
    recorder, load_id = record_load(sender)
    recorder.to_cgmes_diff(tmp_path / 'out_SSH_DIFF.xml')

    # relative to the directory the process started in, without changing it
    relative = os.path.relpath(tmp_path / 'out_SSH_DIFF.xml')
    receiver = load_network()
    receiver.update_from_file(relative)
    assert p0(receiver, load_id) == pytest.approx(9.0)

    # and after a chdir, which the Java side does not see on its own
    monkeypatch.chdir(tmp_path)
    receiver = load_network()
    receiver.update_from_file('out_SSH_DIFF.xml')
    assert p0(receiver, load_id) == pytest.approx(9.0)


def test_a_zip_is_read_whatever_its_name(sender, tmp_path):
    recorder, load_id, line_id, new_x = record_both_profiles(sender)
    written = recorder.to_cgmes_diff(tmp_path / 'both.zip')
    renamed = written.rename(tmp_path / 'both.bin')
    assert _is_zip_file(str(renamed))
    receiver = load_network()
    receiver.update_from_file(renamed)
    assert p0(receiver, load_id) == pytest.approx(17.0)
    assert x(receiver, line_id) == pytest.approx(new_x)


def test_a_zip_with_a_supersedes_chain_of_one_profile(sender, tmp_path):
    first_model = 'urn:uuid:12345678-0000-0000-0000-0000000000c1'
    load_id = first_id(sender.get_loads())
    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=9.0)
        first = recorder.to_cgmes_diff(model_id=first_model)
        recorder.clear()
        sender.update_loads(id=load_id, p0=19.0)
        second = recorder.to_cgmes_diff(supersedes=[first_model])
        fork = recorder.to_cgmes_diff()  # supersedes the loaded model, like the first one

    chain = tmp_path / 'chain.zip'
    with zipfile.ZipFile(chain, 'w') as archive:
        # the names sort against the chain: only the Supersedes links give the order
        archive.writestr('a_SSH_DIFF.xml', second)
        archive.writestr('b_SSH_DIFF.xml', first)
    receiver = load_network()
    receiver.update_from_file(chain)
    assert p0(receiver, load_id) == pytest.approx(19.0)

    forked = tmp_path / 'forked.zip'
    with zipfile.ZipFile(forked, 'w') as archive:
        archive.writestr('a_SSH_DIFF.xml', first)
        archive.writestr('b_SSH_DIFF.xml', fork)
    receiver = load_network()
    before = p0(receiver, load_id)
    with pytest.raises(PyPowsyblError, match=r'a_SSH_DIFF\.xml, b_SSH_DIFF\.xml'):
        receiver.update_from_file(forked)
    assert p0(receiver, load_id) == pytest.approx(before)


def test_a_full_cgmes_file_still_reads_its_siblings(tmp_path):
    """
    Not a difference model: the CGMES convention of reading the same-prefix files of the folder is kept.

    A full SSH read alone would reset the load as well, so the value only guards the update; the route is pinned by
    the sniff assertion, which is what sends the file to the importer unchanged.
    """
    with zipfile.ZipFile(DATA_DIR / 'CGMES_Full.zip') as archive:
        archive.extractall(tmp_path)
    ssh = tmp_path / 'cgmes_SSH.xml'
    assert not _is_difference_model_file(str(ssh))
    assert not _is_zip_file(str(ssh))

    receiver = load_network()
    load_id = first_id(receiver.get_loads())
    expected = p0(receiver, load_id)
    receiver.update_loads(id=load_id, p0=expected + 100.0)
    # the full SSH of the model resets the load, which only works because the update reads the model's files
    receiver.update_from_file(ssh)
    assert p0(receiver, load_id) == pytest.approx(expected)


def test_the_sniffs_never_raise(tmp_path):
    assert not _is_difference_model_file(str(tmp_path / 'missing.xml'))
    assert not _is_zip_file(str(tmp_path / 'missing.zip'))
    assert not _is_difference_model_file(str(tmp_path))
    (tmp_path / 'garbage.xml').write_bytes(b'\x00\x01 not xml')
    assert not _is_difference_model_file(str(tmp_path / 'garbage.xml'))
