#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Fixtures of the versioned RDF database tests: the base grid model, and a copy of it describing the next day.

``data/CGMES_Full.zip`` is one day: its steady state file states ``md:Model.scenarioTime 2021-02-09T19:30:00Z``,
which becomes the base timestep of the scenario it is uploaded into. A *second* day is a second scenario, and it
needs its own model identifiers - two scenarios that shared one may not be told apart when a difference says what
it supersedes. :func:`next_day_zip` produces that copy by rewriting the headers of every instance file: the same
grid, one day later, under new identifiers.
"""
import io
import re
import zipfile
from pathlib import Path
from typing import Dict

TEST_DIR = Path(__file__).parent
DATA_DIR = TEST_DIR.parent / 'data'
CGMES_ZIP = DATA_DIR / 'CGMES_Full.zip'

BASE_DAY = '2021-02-09'
NEXT_DAY = '2021-02-10'

_ABOUT = re.compile(r'(<md:FullModel[^>]*rdf:about=")([^"]*)(")')
_SCENARIO_TIME = re.compile(r'(<md:Model\.scenarioTime>)([^<]*)(</md:Model\.scenarioTime>)')
_DEPENDENT = re.compile(r'(<md:Model\.DependentOn[^>]*rdf:resource=")([^"]*)(")')


def base_zip() -> Path:
    """The CGMES archive every test starts from."""
    return CGMES_ZIP


def _rewrite(text: str, day: str, suffix: str) -> str:
    """Move a header to another day and give it a distinct identity, leaving the payload untouched."""
    text = _ABOUT.sub(lambda m: m.group(1) + m.group(2) + suffix + m.group(3), text)
    text = _DEPENDENT.sub(lambda m: m.group(1) + m.group(2) + suffix + m.group(3), text)
    return _SCENARIO_TIME.sub(lambda m: m.group(1) + day + m.group(2)[10:] + m.group(3), text)


def next_day_zip(day: str = NEXT_DAY, suffix: str = '-d1') -> io.BytesIO:
    """
    ``CGMES_Full.zip`` as the root of another day: every ``md:Model.scenarioTime`` moved to ``day`` and every
    model identifier suffixed. The grid content is byte-identical, which is what makes the two scenarios
    comparable.

    Args:
        day: the ISO date the copy describes
        suffix: what is appended to every model identifier

    Returns:
        a buffer holding the zip archive
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if '_EQ_BD_' in entry or '_TP_BD_' in entry:
                # The boundary is shared reference data and keeps its identity in every scenario
                target.writestr(entry, content)
            else:
                target.writestr(entry, _rewrite(content, day, suffix))
    buffer.seek(0)
    return buffer


_CONSUMER_P = re.compile(r'(<cim:EnergyConsumer\.p>)(-?[0-9.eE+]+)(</cim:EnergyConsumer\.p>)')


def ssh_variant(k: int, label: str, day: str = BASE_DAY, suffix: str = '') -> io.BytesIO:
    """
    The daily CGMES export of one timestep: the base archive with its steady state file rewritten.

    This is what a TSO's schedule looks like from the outside - the same equipment, the same identifiers, other
    setpoints, another scenario time - and it is what :meth:`RdfDatabase.load_cgmes` ingests as a difference
    against the state the timestep derives from. The edits are textual on purpose: re-exporting the network
    through the CGMES exporter would differ from the base in a hundred incidental ways, and a test built on that
    would be about the exporter rather than about the change. Every other entry is copied byte for byte, boundary
    included - the ingestion checks that the boundary did not move.

    Args:
        k: which timestep this is; the active powers are scaled by ``1 + 0.01 * k`` and the model identifier gets
            this number, so two timesteps never claim to be the same model
        label: the wall time the files claim, ``"HH:MM"``
        day: the day they claim, which has to be the base day of the scenario they go into
        suffix: appended to the model identifier only. The Fuseki backend is one server shared by a whole test
            session, so two tests writing ``ssh_variant(1, '20:00')`` into two scenarios would store two models
            claiming the same identity; passing the scenario name here keeps them apart without touching the
            values, which is what the ``k`` above is for

    Returns:
        a buffer holding the zip archive
    """
    hour, minute = label.split(':')
    instant = f'{day}T{int(hour):02d}:{int(minute):02d}:00Z'
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if entry.endswith('SSH.xml'):
                content = _ABOUT.sub(lambda m: m.group(1) + f'urn:uuid:ssh-{k}-{label.replace(":", "")}{suffix}'
                                     + m.group(3), content, count=1)
                content = _SCENARIO_TIME.sub(lambda m: m.group(1) + instant + m.group(3), content)
                content = _CONSUMER_P.sub(
                    lambda m: m.group(1) + f'{float(m.group(2)) * (1 + 0.01 * k):.6f}' + m.group(3), content)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


_LINE_NAME = re.compile(r'(<cim:ACLineSegment\b[^>]*>.*?<cim:IdentifiedObject\.name>)([^<]*)(</cim:IdentifiedObject\.name>)',
                        re.DOTALL)


def eq_drift(k: int, label: str, day: str = BASE_DAY, suffix: str = '') -> io.BytesIO:
    """
    A timestep whose **equipment model** drifted too: :func:`ssh_variant` plus one renamed line.

    The equipment of a day is not always quite the equipment of the base, and an ``IdentifiedObject.name`` is the
    cheapest change that no in-place update knows how to apply. The difference is stored like any other, but with
    ``fast == False`` - which is what makes a network walking to this timestep fall back to a full reload instead
    of applying it in place, inside one scenario and without the caller asking for it.

    Args:
        k: which timestep this is, as in :func:`ssh_variant`
        label: the wall time the files claim, ``"HH:MM"``
        day: the day they claim
        suffix: appended to the model identifiers only, see :func:`ssh_variant`

    Returns:
        a buffer holding the zip archive
    """
    source = ssh_variant(k, label, day, suffix)
    buffer = io.BytesIO()
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in archive.namelist():
            content = archive.read(entry).decode('utf-8')
            if entry.endswith('EQ.xml') and '_BD_' not in entry:
                content = _ABOUT.sub(lambda m: m.group(1) + f'urn:uuid:eq-{k}-{label.replace(":", "")}{suffix}'
                                     + m.group(3), content, count=1)
                content = _LINE_NAME.sub(lambda m: m.group(1) + f'drifted-{k}' + m.group(3), content, count=1)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


def drifted_name(k: int) -> str:
    """The name :func:`eq_drift` gives the line it renames."""
    return f'drifted-{k}'


def model_ids(zip_source: Path) -> Dict[str, str]:
    """The ``rdf:about`` of every header in an archive, keyed by entry name; used to explain a failure."""
    found = {}
    with zipfile.ZipFile(zip_source) as archive:
        for entry in archive.namelist():
            match = _ABOUT.search(archive.read(entry).decode('utf-8'))
            if match is not None:
                found[entry] = match.group(2)
    return found
