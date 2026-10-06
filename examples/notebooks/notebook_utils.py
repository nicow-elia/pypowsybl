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
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional

DATA_DIR = Path(__file__).resolve().parents[2] / 'data'
CGMES_ZIP = DATA_DIR / 'CGMES_Full_one_authority.zip'
"""
``data/CGMES_Full.zip`` with every non-boundary file stating one ``md:Model.modelingAuthoritySet``
(``http://elia.be/CGMES``). The original states three - ``powsybl.org`` on EQ and TP, Elia on SSH, TenneT on SV - and
a snapshot of the database belongs to exactly one modelling authority, so the original can be stored un-versioned
but not as a snapshot. Everything else is byte for byte the same.
"""

SCENARIO = '2021-02-09'
"""The day ``data/CGMES_Full.zip`` describes: its steady state file states 2021-02-09T19:30:00Z."""

NEXT_DAY = '2021-02-10'
"""The day the copy of :func:`next_day_zip` describes."""

AUTHORITY = 'http://elia.be/CGMES'
"""The ``md:Model.modelingAuthoritySet`` of ``data/CGMES_Full.zip``: the one tree of every scenario here."""


def at(hh_mm: str, day: str = SCENARIO) -> datetime:
    """
    A moment of a day as the timezone-aware UTC datetime the database calls take: ``at('20:30')`` is
    ``datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc)``.
    """
    hour, minute = hh_mm.split(':')
    return datetime.fromisoformat(day).replace(hour=int(hour), minute=int(minute), tzinfo=timezone.utc)


BASE = at('19:30')
"""The base timestamp of :data:`SCENARIO`: the scenario time of ``data/CGMES_Full.zip``."""

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


def ssh_variant(k: int, timestamp: datetime, suffix: str = '') -> io.BytesIO:
    """
    The daily CGMES export of one timestamp, standing in for a real schedule.

    The base archive with its steady state file rewritten: the active power of every energy consumer scaled by
    ``1 + 0.01 * k``, the scenario time moved to ``timestamp``, and a model identifier of its own so that two
    timestamps never claim to be the same model. Everything else - the equipment model, the boundary - is copied
    byte for byte, which is what makes the ingested difference small.

    Args:
        k: which timestamp this is
        timestamp: the moment the files claim, a timezone-aware datetime (see :func:`at`)
        suffix: appended to the model identifier only. The tests share one Fuseki server between many scenarios
            and pass the scenario name here, so that two of them never store two models claiming one identity
    """
    utc = timestamp.astimezone(timezone.utc)
    instant = utc.strftime('%Y-%m-%dT%H:%M:%SZ')
    model_id = f'urn:uuid:ssh-{utc:%Y-%m-%d}-{k}-{utc:%H%M}{suffix}'
    buffer = io.BytesIO()
    with zipfile.ZipFile(CGMES_ZIP) as source, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.namelist():
            content = source.read(entry).decode('utf-8')
            if entry.endswith('SSH.xml'):
                content = _ABOUT.sub(lambda m: m.group(1) + model_id + m.group(3), content, count=1)
                content = _SCENARIO_TIME.sub(lambda m: m.group(1) + instant + m.group(3), content)
                content = _CONSUMER_P.sub(
                    lambda m: m.group(1) + f'{float(m.group(2)) * (1 + 0.01 * k):.6f}' + m.group(3), content)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


_LINE_NAME = re.compile(r'(<cim:ACLineSegment\b[^>]*>.*?<cim:IdentifiedObject\.name>)([^<]*)(</cim:IdentifiedObject\.name>)',
                        re.DOTALL)


def eq_drift(k: int, timestamp: datetime, suffix: str = '') -> io.BytesIO:
    """
    A timestamp whose **equipment model** drifted: :func:`ssh_variant` plus one renamed line.

    The equipment of a day is not always quite the equipment of the base, and a name is the cheapest change that
    no in-place update knows how to apply. The difference is stored like any other, but it is not fast-route
    capable - which is what makes a network walking to this timestep fall back to a full reload.

    Args:
        k: which timestamp this is, as in :func:`ssh_variant`; the renamed line is called :func:`drifted_name` ``(k)``
        timestamp: the moment the files claim, a timezone-aware datetime
        suffix: appended to the model identifiers only, see :func:`ssh_variant`
    """
    source = ssh_variant(k, timestamp, suffix)
    model_id = f'urn:uuid:eq-{k}-{timestamp.astimezone(timezone.utc):%H%M}{suffix}'
    buffer = io.BytesIO()
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in archive.namelist():
            content = archive.read(entry).decode('utf-8')
            if entry.endswith('EQ.xml') and '_BD_' not in entry:
                content = _ABOUT.sub(lambda m: m.group(1) + model_id + m.group(3), content, count=1)
                content = _LINE_NAME.sub(lambda m: m.group(1) + drifted_name(k) + m.group(3), content, count=1)
            target.writestr(entry, content)
    buffer.seek(0)
    return buffer


def drifted_name(k: int) -> str:
    """The name :func:`eq_drift` gives the line it renames."""
    return f'drifted-{k}'


def fresh_scenario(db, name: str) -> str:  # type: ignore[no-untyped-def]
    """
    Drop whatever a previous run of a notebook left in ``name``, so that re-running a notebook is idempotent.

    A root snapshot is written once and never overwritten, which is exactly what one wants in production and
    exactly what a notebook run twice trips over.
    """
    db.clear(name)
    return name
