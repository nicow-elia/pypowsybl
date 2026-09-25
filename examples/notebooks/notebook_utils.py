#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Everything the notebooks need besides pypowsybl itself.

Deliberately self-contained: a reader who checked the repository out can run the notebooks without the test suite
being importable. ``PYPOWSYBL_RDF_DB`` points the notebooks at a real database; without it they use the in-process
store, which needs no server.
"""
import io
import os
import re
import zipfile
from pathlib import Path
from typing import Dict, Optional

DATA_DIR = Path(__file__).resolve().parents[2] / 'data'
CGMES_ZIP = DATA_DIR / 'CGMES_Full.zip'

SCENARIO = '2021-02-09'
"""The day ``data/CGMES_Full.zip`` describes: its steady state file states 2021-02-09T19:30:00Z."""

NEXT_DAY = '2021-02-10'
"""The day the copy of :func:`next_day_zip` describes."""

_ABOUT = re.compile(r'(<md:FullModel[^>]*rdf:about=")([^"]*)(")')
_SCENARIO_TIME = re.compile(r'(<md:Model\.scenarioTime>)([^<]*)(</md:Model\.scenarioTime>)')
_DEPENDENT = re.compile(r'(<md:Model\.DependentOn[^>]*rdf:resource=")([^"]*)(")')


def database_url() -> str:
    """
    Where the notebooks write.

    ``memory:notebook`` unless ``PYPOWSYBL_RDF_DB`` names a SPARQL endpoint, for instance
    ``http://localhost:3030/ds`` for the Fuseki container of the README.
    """
    return os.environ.get('PYPOWSYBL_RDF_DB', 'memory:notebook')


def credentials() -> Dict[str, Optional[str]]:
    """User and password from ``PYPOWSYBL_RDF_DB_USER`` / ``PYPOWSYBL_RDF_DB_PASSWORD``, both optional."""
    return {'user': os.environ.get('PYPOWSYBL_RDF_DB_USER'),
            'password': os.environ.get('PYPOWSYBL_RDF_DB_PASSWORD')}


def connect():  # type: ignore[no-untyped-def]
    """Open the connection the notebooks use, with whatever credentials the environment provides."""
    import pypowsybl as pp  # pylint: disable=import-outside-toplevel
    return pp.network.connect(database_url(), **credentials())


def _rewrite(text: str, day: str, suffix: str) -> str:
    text = _ABOUT.sub(lambda m: m.group(1) + m.group(2) + suffix + m.group(3), text)
    text = _DEPENDENT.sub(lambda m: m.group(1) + m.group(2) + suffix + m.group(3), text)
    return _SCENARIO_TIME.sub(lambda m: m.group(1) + day + m.group(2)[10:] + m.group(3), text)


def next_day_zip(day: str = NEXT_DAY, suffix: str = '-d1') -> io.BytesIO:
    """
    ``CGMES_Full.zip`` as the root of another day: the same grid, every ``md:Model.scenarioTime`` moved to ``day``
    and every model identifier suffixed, so that the two days really are two base grid models. Boundary files keep
    their identity, because a boundary is shared reference data.
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            keep = '_EQ_BD_' in entry or '_TP_BD_' in entry
            target.writestr(entry, content if keep else _rewrite(content, day, suffix))
    buffer.seek(0)
    return buffer


_CONSUMER_P = re.compile(r'(<cim:EnergyConsumer\.p>)(-?[0-9.eE+]+)(</cim:EnergyConsumer\.p>)')


def ssh_variant(k: int, label: str, day: str = SCENARIO) -> io.BytesIO:
    """
    The daily CGMES export of one timestep, standing in for a real schedule.

    The base archive with its steady state file rewritten: the active power of every energy consumer scaled by
    ``1 + 0.01 * k``, the scenario time moved to ``label`` of ``day``, and a model identifier of its own so that
    two timesteps never claim to be the same model. Everything else - the equipment model, the boundary - is
    copied byte for byte, which is what makes the ingested difference small.

    Args:
        k: which timestep this is
        label: the wall time the files claim, ``"HH:MM"``
        day: the day they claim, which has to be the base day of the scenario they go into
    """
    hour, minute = label.split(':')
    instant = f'{day}T{int(hour):02d}:{int(minute):02d}:00Z'
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if entry.endswith('SSH.xml'):
                content = _ABOUT.sub(lambda m: m.group(1) + f'urn:uuid:ssh-{day}-{k}-{label.replace(":", "")}'
                                     + m.group(3), content, count=1)
                content = _SCENARIO_TIME.sub(lambda m: m.group(1) + instant + m.group(3), content)
                content = _CONSUMER_P.sub(
                    lambda m: m.group(1) + f'{float(m.group(2)) * (1 + 0.01 * k):.6f}' + m.group(3), content)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


_LINE_NAME = re.compile(r'(<cim:ACLineSegment\b[^>]*>.*?<cim:IdentifiedObject\.name>)([^<]*)(</cim:IdentifiedObject\.name>)',
                        re.DOTALL)


def eq_drift(k: int, label: str, day: str = SCENARIO) -> io.BytesIO:
    """
    A timestep whose **equipment model** drifted: :func:`ssh_variant` plus one renamed line.

    The equipment of a day is not always quite the equipment of the base, and a name is the cheapest change that
    no in-place update knows how to apply. The difference is stored like any other, but it is not fast-route
    capable - which is what makes a network walking to this timestep fall back to a full reload.
    """
    source = ssh_variant(k, label, day)
    buffer = io.BytesIO()
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in archive.namelist():
            content = archive.read(entry).decode('utf-8')
            if entry.endswith('EQ.xml') and '_BD_' not in entry:
                content = _ABOUT.sub(lambda m: m.group(1) + f'urn:uuid:eq-{k}-{label.replace(":", "")}'
                                     + m.group(3), content, count=1)
                content = _LINE_NAME.sub(lambda m: m.group(1) + f'drifted-{k}' + m.group(3), content, count=1)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


def fresh_scenario(db, name: str) -> str:  # type: ignore[no-untyped-def]
    """
    Drop whatever a previous run of a notebook left in ``name``, so that re-running a notebook is idempotent.

    A root snapshot is written once and never overwritten, which is exactly what one wants in production and
    exactly what a notebook run twice trips over.
    """
    db.clear(name)
    return name
