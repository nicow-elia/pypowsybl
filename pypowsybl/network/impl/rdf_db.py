#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Two-step CGMES loading: instance files into an RDF graph database, and a network out of it.

Reading CGMES files is the expensive half of an import, and it is repeated every time the same grid model is
loaded. This module splits it in two: :meth:`RdfDatabase.load_cgmes` parses the files once and writes them into a
SPARQL 1.1 database as named graphs, and :func:`from_rdf_db` builds an IIDM network from those graphs. The network
it produces is the network the files themselves would have produced.

Every call names a **scenario**: the free-form name of the base grid model the graphs belong to, typically a day
(``"2021-02-09"``). It is required and never guessed, because a database is expected to hold many days side by side.

Inside a scenario, a grid state is a **snapshot** addressed by a *version* (a ``str``, a name the version registry
of the scenario ranks: ``'1'``, ``'DA'``, ``'ID'``, ...), a *timestamp* (a timezone-aware
:class:`datetime.datetime`, the moment it describes) and a *modelling authority* (the
``md:Model.modelingAuthoritySet`` of its files, i.e. which TSO's tree it belongs to). A read at a version takes the
highest ranking version at or below it that the timestamp holds; ``exact=True`` takes that version or nothing. The
four together -
``(scenario, version, timestamp, modelling_authority)`` - are the address of every call: :func:`from_rdf_db` reads
one, :meth:`pypowsybl.network.Network.update_from_rdf_db` walks to one, and
:meth:`pypowsybl.network.NetworkEventRecorder.to_rdf_updates` writes one. ``profiles`` is not part of the address:
it selects which CGMES profiles a call reads or writes.
"""
from __future__ import annotations  # Necessary for type alias like _DataFrame to work with sphinx

import datetime
import io
from os import PathLike
from types import TracebackType
from typing import (Dict, List, Literal, Mapping, Optional, Sequence, Tuple, Type, Union, TYPE_CHECKING, cast,
                    get_args)

import pandas as pd
from pandas import DataFrame

import pypowsybl._pypowsybl as _pp
from pypowsybl.report import ReportNode
from pypowsybl.utils import create_data_frame_from_series_array, path_to_str

if TYPE_CHECKING:
    from .network import Network

_QUERY_MODES = ('local', 'remote')
_ON_REFUSAL = ('raise', 'skip')

Profile = Literal['EQ', 'SSH', 'TP', 'SV', 'DY', 'DL', 'GL', 'EQ_BD', 'TP_BD']
"""
A CGMES profile, as the ``profiles`` argument of the RDF database calls names it. ``profiles`` is a projection, never
part of an address: it selects which profiles a call loads, updates, stores or compares, and ``None`` is the
default of each call (every profile of a snapshot on a load, the equipment model and the steady state hypothesis on
an update and on an ingestion).
"""

_PROFILES = get_args(Profile)

SnapshotAddress = Tuple[Optional[str], datetime.datetime, Optional[str]]
"""
Where one variant of a :func:`from_rdf_db` ``variants={...}`` mapping stands, when it is not simply a timestamp:
``(version, timestamp, modelling_authority)`` in the order of the call's own arguments, ``None`` meaning the head
version and the only modelling authority of the scenario.
"""


def _timestamp_to_str(timestamp: Optional[datetime.datetime]) -> str:
    """
    Turn a timestamp into the ISO-8601 UTC instant the native layer takes; ``None`` is ``''``.

    Raises:
        TypeError: the timestamp is not a :class:`datetime.datetime`, or it is naive
    """
    if timestamp is None:
        return ''
    if not isinstance(timestamp, datetime.datetime):
        raise TypeError(f'A timestamp must be a timezone-aware datetime.datetime or None, got '
                        f'{type(timestamp).__name__} {timestamp!r}')
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise TypeError(f'timestamp must be timezone-aware: the naive {timestamp!r} names no instant. Pass for '
                        'instance datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc)')
    return timestamp.astimezone(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


def _version_to_str(version: Optional[str]) -> str:
    """
    Turn a version into the name the native layer takes; ``None`` (the head, or the next one on a write) is ``''``.

    Raises:
        TypeError: the version is not a ``str`` - a version is a name, ``'2'`` and not ``2``
        ValueError: the version is blank
    """
    if version is None:
        return ''
    if not isinstance(version, str):
        raise TypeError(f'version is a name: pass a str, for instance {str(version)!r}, got '
                        f'{type(version).__name__} {version!r}')
    if not version.strip():
        raise ValueError('A version name must not be blank; pass None for the head')
    return version


def _authority_to_str(modelling_authority: Optional[str]) -> str:
    """
    Turn a modelling authority into the text the native layer takes; ``None`` (the only one of the scenario) is
    ``''``.

    Raises:
        TypeError: the modelling authority is not a string
        ValueError: it is blank
    """
    if modelling_authority is None:
        return ''
    if not isinstance(modelling_authority, str):
        raise TypeError(f'A modelling authority must be a string, for instance "http://elia.be/CGMES/2.4.15", got '
                        f'{type(modelling_authority).__name__}')
    if not modelling_authority.strip():
        raise ValueError('A modelling authority must not be blank; pass None for the only one of the scenario')
    return modelling_authority


def _profiles_to_list(profiles: Optional[Sequence[Profile]]) -> List[str]:
    """
    Check a profile projection; ``None`` (the default of the call) is the empty list.

    Raises:
        TypeError: a single string was given instead of a sequence
        ValueError: a name is not a :data:`Profile`
    """
    if profiles is None:
        return []
    if isinstance(profiles, str):
        raise TypeError(f'profiles takes a sequence of profiles, for instance [{profiles!r}], not one string')
    for profile in profiles:
        if profile not in _PROFILES:
            raise ValueError(f'Unknown CGMES profile {profile!r}, expected one of {list(_PROFILES)}')
    return list(profiles)


def _typed(frame: DataFrame) -> DataFrame:
    """
    Give the address columns of a catalogue dataframe their Python types: ``timestamp`` (column or index) becomes
    ``datetime64[ns, UTC]``, ``version`` stays a name (``None`` in a row that stands for no snapshot) and ``rank``
    becomes ``int64`` - or the nullable ``Int64`` in the tables where a row may stand for no snapshot.
    """
    if frame.index.name == 'timestamp':
        frame.index = pd.to_datetime(frame.index, utc=True).rename('timestamp')
    if 'timestamp' in frame.columns:
        text = frame['timestamp']
        frame['timestamp'] = pd.to_datetime(text.where(text != '', None), utc=True)
    if 'version' in frame.columns:
        text = frame['version'].astype(object)
        frame['version'] = text.where(text != '', None)
    if 'rank' in frame.columns:
        rank = frame['rank'].astype('int64')
        frame['rank'] = rank.where(rank >= 0).astype('Int64') if (rank < 0).any() else rank
    return frame


class RdfDbVariantRefusedError(_pp.PyPowsyblError):
    """
    A snapshot could not be reached inside a network variant, and **nothing was changed**.

    A variant of a network is one state among several, and IIDM stores only part of a grid model per variant:
    branch impedances, operational limit values, voltage limits and the ``maxP``/loss-factor pair the simplified
    HVDC model recomputes are shared by every variant. A difference that writes one of them would silently change
    every other variant too, so it is refused instead - the network, its variants and their bindings are exactly
    as they were before the call.

    Attributes:
        variant: the variant that was refused; the first one when several were, see :attr:`refused`
        reasons: one line per blocking statement, in the same shape whether one snapshot was refused or several.
            A line names the difference model and what about it cannot be applied to a single variant; a line
            decided on the fetched statements also names the CGMES statement and the IIDM attribute behind it
        refused: the refused rows of :meth:`pypowsybl.network.Network.variants_binding`, or ``None`` when the
            refusal came from a single :meth:`pypowsybl.network.Network.update_from_rdf_db` call

    Examples:
        .. code-block:: python

            try:
                network.update_from_rdf_db(db, '2021-02-09', 1, at_0930, variant='09:30')
            except pp.network.RdfDbVariantRefusedError as e:
                print(e.variant, e.reasons)
                network = pp.network.from_rdf_db(db, '2021-02-09', 1, at_0930)  # a network of its own
    """

    def __init__(self, message: str, variant: Optional[str] = None, reasons: Optional[Sequence[str]] = None,
                 refused: Optional[DataFrame] = None) -> None:
        super().__init__(message)
        self.variant = variant
        self.reasons: List[str] = [str(reason) for reason in (reasons or [])]
        self.refused = refused


def _split_reasons(text: Optional[str]) -> List[str]:
    """
    The reasons of a refusal, which travel as one ``'; '`` joined string through the native layer.

    Split on the separator the Java side joins with, not on every ``;``: a reason text may contain one, and a
    single update and a bulk load must hand the caller the same shape.
    """
    if not text:
        return []
    return [reason.strip() for reason in text.split('; ') if reason.strip()]


def _report_handle(report_node: Optional[ReportNode]) -> Optional[_pp.JavaHandle]:
    return None if report_node is None else report_node._report_node  # pylint: disable=protected-access


def _check_scenario(scenario: str) -> str:
    """
    The scenario is required everywhere; catching a bad one here gives a Python error, not a Java one.

    Raises:
        TypeError: the scenario is not a string
        ValueError: the scenario is empty or only whitespace
    """
    if not isinstance(scenario, str):
        raise TypeError(f'A scenario name must be a string, for instance "2021-02-09", '
                        f'got {type(scenario).__name__}')
    if not scenario.strip():
        raise ValueError('A scenario name is required and must not be blank, for instance "2021-02-09"')
    return scenario


def _check_variant(variant: str) -> str:
    """A variant identifier is a non-blank string; raises :class:`ValueError` otherwise."""
    if not isinstance(variant, str) or not variant.strip():
        raise ValueError(f'A variant identifier must be a non-blank string, got {variant!r}')
    return variant


class VersionRegistry:
    """
    The version names of one scenario and the order they are compared in.

    A version is a name - ``'DA'``, ``'ID'``, ``'RT'`` or simply ``'1'``, ``'2'`` - and every scenario holds a
    *registry* that gives each name a **rank**: versions are compared by rank, never by name, so a chain of study
    states of one timestamp only grows upwards in rank, and a read at a name takes the highest ranking version at or
    below it. Ranks are sparse (10, 20, ...) so that a name can be inserted between two others later.

    A registry is **strict** or **permissive**. A strict one refuses a write under a name it does not hold; a
    permissive one appends such a name at the top. A scenario whose first root is written without a registry gets a
    permissive one holding the root's version name - which is why writing ``'1'``, ``'2'``, ... in order just works.

    Obtained from :meth:`RdfDatabase.registry`. Every edit is guarded by the registry's revision in the database: an
    edit that lost a race against another writer raises :class:`pypowsybl.PyPowsyblError` and changes nothing.

    Examples:
        .. code-block:: python

            registry = db.registry('2021-02-09')
            registry.create(['DA', 'ID', 'RT'])          # strict: only these three may be written
            db.load_cgmes('grid.zip', '2021-02-09', 'DA', modelling_authority=tso)
            registry.insert('ID2', after='ID')           # ranks DA 10, ID 20, ID2 25, RT 30
            registry.dataframe()
    """

    def __init__(self, db: 'RdfDatabase', scenario: str) -> None:
        self._db = db
        self._scenario = _check_scenario(scenario)

    def __repr__(self) -> str:
        return f'{self.__class__.__name__}(scenario={self._scenario!r})'

    @property
    def scenario(self) -> str:
        """The scenario this registry belongs to."""
        return self._scenario

    def _edit(self, op: str, name: str = '', other: str = '', names: Optional[Sequence[str]] = None,
              ranks: Optional[Sequence[int]] = None, flag: bool = False) -> Dict[str, str]:
        return _pp.edit_rdf_db_registry(self._db._check_open(),  # pylint: disable=protected-access
                                        self._scenario, op, name, other, list(names or []), list(ranks or []), flag)

    def dataframe(self) -> DataFrame:
        """
        The registered names, lowest rank first.

        Returns:
            a dataframe indexed by ``name`` with the columns ``rank`` (``int64``) and ``transient`` (bool: deleting
            the name drops the snapshots that carry it). Empty for a scenario that has no registry yet
        """
        frame = create_data_frame_from_series_array(
            _pp.get_rdf_db_registry(self._db._check_open(), self._scenario))  # pylint: disable=protected-access
        frame['rank'] = frame['rank'].astype('int64')
        return frame

    @property
    def names(self) -> List[str]:
        """The registered names, lowest rank first."""
        return list(self.dataframe().index)

    def rank(self, name: str) -> Optional[int]:
        """The rank of a registered name, ``None`` for a name the registry does not hold."""
        frame = self.dataframe()
        return int(cast(int, frame.loc[name, 'rank'])) if name in frame.index else None

    @property
    def permissive(self) -> bool:
        """Whether a write may append a name the registry does not hold (``True`` for a scenario without one)."""
        return self._edit('refresh')['permissive'] == 'true'

    def create(self, names: Sequence[str], permissive: bool = False) -> None:
        """
        Create the registry of a scenario that has none, before its first root is written.

        Args:
            names: the version names, lowest rank first; they get the ranks 10, 20, ...
            permissive: whether a write may append a name that is not registered; strict by default
        """
        if isinstance(names, str):
            raise TypeError(f'names takes a sequence of version names, for instance [{names!r}], not one string')
        self._edit('create', names=[_version_to_str(name) for name in names], flag=permissive)

    def add(self, name: str) -> int:
        """Register a name above every other one; returns its rank (the highest rank plus 10)."""
        return int(self._edit('add', _version_to_str(name))['rank'])

    def insert(self, name: str, after: Optional[str] = None) -> int:
        """
        Register a name right after another one - at the midpoint between it and its successor - or before the
        first one when ``after`` is ``None``; returns its rank. Refused when no integer lies between the two: rerank
        first.
        """
        return int(self._edit('insert', _version_to_str(name), _version_to_str(after))['rank'])

    def rerank(self, ranks: Mapping[str, int]) -> None:
        """
        Give registered names new ranks; the names not listed keep theirs. Refused when a stored version would end up
        at or below the version it was written on, or when two names would share a rank.
        """
        names = [_version_to_str(name) for name in ranks]
        values = list(ranks.values())
        for value in values:
            if isinstance(value, bool) or not isinstance(value, int):
                raise TypeError(f'a rank is an int, got {type(value).__name__} {value!r}')
        self._edit('rerank', names=names, ranks=values)

    def rename(self, old: str, new: str) -> None:
        """Rename a name no snapshot carries yet (a snapshot's IRI carries its version name; rerank to reorder)."""
        self._edit('rename', _version_to_str(old), _version_to_str(new))

    def mark_transient(self, name: str, transient: bool = True) -> None:
        """Mark a name transient (or not): deleting a transient name drops the snapshots that carry it."""
        self._edit('mark_transient', _version_to_str(name), flag=transient)

    def delete(self, name: str) -> None:
        """
        Delete a name. An unused name simply goes; a transient one takes its snapshots with it, each of which must be
        one nothing was built on; any other name a snapshot carries is refused.
        """
        self._edit('delete', _version_to_str(name))


class RdfDatabase:  # pylint: disable=too-many-public-methods  # the catalogue of a database is its API
    """
    An open connection to an RDF graph database holding CGMES instance files as named graphs.

    Instances are created by :func:`connect_rdf_db` (also exported as ``connect``). Used as a context manager, the
    connection is closed when the block is left, even if an exception is raised inside it::

        with pp.network.connect('memory:demo') as db:
            db.load_cgmes('grid.zip', '2021-02-09')
            network = pp.network.from_rdf_db(db, '2021-02-09')

    The connection owns an HTTP client and, for the ``memory:`` backend, the in-process store; :meth:`close` releases
    them and is idempotent. Every call on a closed connection raises :class:`pypowsybl.PyPowsyblError`. One
    connection may be shared by several networks and is safe to use from several threads; it must not be shared
    across processes.

    **The vocabulary of the catalogue.** A *scenario* is one base grid model - in practice one day - and a
    difference never crosses scenarios. Inside it, every *modelling authority* (one TSO's ``modelingAuthoritySet``)
    has a tree of its own, sharing the scenario's boundary: a *root*, the *timestamps* derived from it - the moment
    the root describes is the *base timestamp* - and per timestamp a chain of *versions*, each a study state of that
    moment. A version is a name (``'1'``, ``'DA'``, ...) that the scenario's version registry ranks
    (:meth:`registry`); a chain only grows upwards in rank. A *snapshot* is a consistent grid state addressed by
    ``(scenario, version, timestamp, modelling_authority)``. Timestamps are instants: pass timezone-aware datetimes,
    the dataframes answer in UTC.

    The catalogue is read as dataframes: :meth:`scenarios`, :meth:`snapshots`, :meth:`versions`,
    :meth:`timestamps`, :meth:`assembly` and :meth:`models`; :meth:`modelling_authorities` lists the trees of a
    scenario and :meth:`registry` its version names.
    """

    def __init__(self, url: str, handle: _pp.JavaHandle) -> None:
        self._url = url
        self._handle: Optional[_pp.JavaHandle] = handle  # None once closed

    @property
    def url(self) -> str:
        """The URL this connection was opened with."""
        return self._url

    def __enter__(self) -> 'RdfDatabase':
        return self

    def __exit__(self, exc_type: Optional[Type[BaseException]], exc_value: Optional[BaseException],
                 traceback: Optional[TracebackType]) -> None:
        self.close()

    def __repr__(self) -> str:
        return f'{self.__class__.__name__}(url={self._url!r}, closed={self.closed})'

    def close(self) -> None:
        """
        Close the connection. Calling it twice, or after the object was used as a context manager, does nothing.
        """
        handle, self._handle = self._handle, None
        if handle is not None:
            _pp.close_rdf_db_connection(handle)

    def __del__(self) -> None:
        # An interpreter shutdown can take the extension module away before the last reference dies
        try:
            self.close()
        except Exception:  # pylint: disable=broad-except
            pass

    def _check_open(self) -> _pp.JavaHandle:
        if self._handle is None:
            raise _pp.PyPowsyblError(f'RDF database connection to {self._url} is closed')
        return self._handle

    @property
    def closed(self) -> bool:
        """Whether :meth:`close` has already been called."""
        return self._handle is None

    def scenarios(self) -> DataFrame:
        """
        One row per scenario stored in the database.

        Returns:
            a dataframe indexed by ``scenario`` (str, as given at upload) with the columns
            ``modelling_authorities`` (the authorities holding a tree in the scenario, ``;``-joined, empty when the
            scenario is un-versioned), ``versioned`` (bool) and ``snapshot_count`` (int). Sorted by name.
        """
        return create_data_frame_from_series_array(_pp.get_rdf_db_scenario_table(self._check_open()))

    def versioned(self, scenario: str) -> bool:
        """
        Whether a scenario holds snapshots, and can therefore be addressed by version, timestamp and modelling
        authority.

        Args:
            scenario: the scenario to ask about

        Returns:
            ``True`` when the scenario has a root snapshot
        """
        return _pp.is_rdf_db_versioned(self._check_open(), _check_scenario(scenario))

    def snapshots(self, scenario: str) -> DataFrame:
        """
        One row per stored snapshot of a scenario.

        Args:
            scenario: the scenario to list

        Returns:
            a dataframe indexed by ``snapshot`` (the snapshot IRI) with the columns ``scenario``,
            ``modelling_authority``, ``timestamp`` (``datetime64[ns, UTC]``), ``version`` (str, the name), ``rank``
            (``int64``, the rank the registry gives that name), ``profiles`` (the CGMES profiles the snapshot's state covers, ``;``-joined), ``kind``
            (``full``/``diff``), ``parent`` (snapshot IRI or empty), ``edge`` (``version``/``timestamp``/empty),
            ``depth`` (int), ``has_full`` (bool), ``fast`` (bool: reachable by the in-place diff route), ``members``
            (the stored model ids, ``;``-joined), ``created`` (ISO) and ``description``. Sorted by modelling
            authority, timestamp, depth, then rank. A scenario the database does not hold gives an empty frame with those
            columns.
        """
        return _typed(create_data_frame_from_series_array(
            _pp.get_rdf_db_snapshots(self._check_open(), _check_scenario(scenario))))

    def versions(self, scenario: str, timestamp: Optional[datetime.datetime] = None,
                 modelling_authority: Optional[str] = None) -> DataFrame:
        """
        The version chain of one timestamp of one modelling authority of a scenario.

        Args:
            scenario: the scenario to list
            timestamp: the moment, a timezone-aware datetime; ``None`` for the base timestamp
            modelling_authority: the tree to list; ``None`` for the only one of the scenario (refused, with the
                list, when the scenario holds several)

        Returns:
            the same columns as :meth:`snapshots`, restricted to one timestamp and ordered by depth
        """
        return _typed(create_data_frame_from_series_array(
            _pp.get_rdf_db_versions(self._check_open(), _check_scenario(scenario), _timestamp_to_str(timestamp),
                                    _authority_to_str(modelling_authority))))

    def timestamps(self, scenario: str, modelling_authority: Optional[str] = None) -> DataFrame:
        """
        One row per timestamp of one modelling authority's tree in a scenario.

        Args:
            scenario: the scenario to list
            modelling_authority: the tree to list; ``None`` for the only one of the scenario

        Returns:
            a dataframe indexed by ``timestamp`` (``datetime64[ns, UTC]``) with the columns
            ``modelling_authority``, ``root`` (IRI of the timestamp's root snapshot), ``head`` (IRI of its newest
            version), ``version_count`` (int) and ``pinned_base`` (IRI of the base-chain snapshot the timestamp
            derives from, empty for the base timestamp). Oldest first
        """
        return _typed(create_data_frame_from_series_array(
            _pp.get_rdf_db_timestamps(self._check_open(), _check_scenario(scenario),
                                      _authority_to_str(modelling_authority))))

    def modelling_authorities(self, scenario: str) -> List[str]:
        """
        The modelling authorities a scenario holds a snapshot tree of.

        Every call that reads a snapshot needs one; when this list has exactly one entry, ``None`` means it.

        Args:
            scenario: the scenario to ask about

        Returns:
            the ``md:Model.modelingAuthoritySet`` values, sorted; empty for an un-versioned or unknown scenario
        """
        return _pp.get_rdf_db_modelling_authorities(self._check_open(), _check_scenario(scenario))

    def assembly(self, scenario: str, timestamp: datetime.datetime, version: Optional[str] = None) -> DataFrame:
        """
        Every modelling authority of a scenario at one moment: what a common grid model is assembled from.

        A query, not a stored assembly. Loading the result as *one* network is not supported: load each row
        ``(scenario, version, timestamp, modelling_authority)`` with :func:`from_rdf_db` and merge the networks.

        Args:
            scenario: the scenario
            timestamp: the moment, a timezone-aware datetime; required
            version: the version every authority is taken at - for each, the highest ranking version at or below
                it -, ``None`` for the head of each

        Returns:
            a dataframe with **one row per modelling authority of the scenario**, indexed by
            ``modelling_authority``, with the columns ``snapshot``, ``timestamp`` (``datetime64[ns, UTC]``),
            ``version`` (str), ``rank`` (nullable ``Int64``), ``profiles``, ``kind``, ``depth``, ``has_full``,
            ``fast`` and ``members``. An authority without a snapshot at that moment keeps its row: empty
            ``snapshot``, ``NaT`` timestamp, ``None`` version, ``<NA>`` rank, ``depth`` -1
        """
        if timestamp is None:
            raise TypeError('assembly() needs a timestamp: it is the state of every modelling authority at one '
                            'moment')
        return _typed(create_data_frame_from_series_array(
            _pp.get_rdf_db_assembly(self._check_open(), _check_scenario(scenario), _timestamp_to_str(timestamp),
                                    _version_to_str(version))))

    def models(self, scenario: str) -> DataFrame:
        """
        One row per stored CGMES model of a scenario.

        Args:
            scenario: the scenario to list

        Returns:
            a dataframe indexed by ``id`` (the CGMES model id) with the columns ``scenario``, ``subset``, ``kind``
            (``full``/``diff``), ``version`` (the CGMES ``md:Model.version``), ``supersedes`` and ``depends_on``
            (``;``-joined), ``fast`` (bool), ``triple_count`` (int), ``chain_depth`` (int) and ``created``
        """
        return create_data_frame_from_series_array(
            _pp.get_rdf_db_models(self._check_open(), _check_scenario(scenario)))

    def registry(self, scenario: str) -> VersionRegistry:
        """
        The version registry of a scenario: its version names and the ranks they are compared by.

        Args:
            scenario: the scenario

        Returns:
            a :class:`VersionRegistry`; it reads and edits the database on every call, so it never goes stale
        """
        return VersionRegistry(self, scenario)

    def checkpoint(self, scenario: str, version: Optional[str] = None,
                   timestamp: Optional[datetime.datetime] = None, modelling_authority: Optional[str] = None) -> str:
        """
        Materialise a snapshot as a full state, so that loading it needs no walk down the difference chain.

        A checkpoint changes nothing about what the snapshot *is*: the same network comes back before and after. It
        only trades storage for load time, and is worth it at the end of a long chain of timestamps.

        Args:
            scenario: the scenario holding the snapshot
            version: the version name, ``None`` for the newest one; otherwise the highest ranking version at or
                below it
            timestamp: the moment, a timezone-aware datetime; ``None`` for the base timestamp
            modelling_authority: the tree; ``None`` for the only one of the scenario

        Returns:
            the IRI of the snapshot that was materialised
        """
        return _pp.create_rdf_db_checkpoint(self._check_open(), _check_scenario(scenario),
                                            _version_to_str(version), _timestamp_to_str(timestamp),
                                            _authority_to_str(modelling_authority))

    def graphs(self, scenario: str) -> DataFrame:
        """
        The catalogue of one scenario: one row per instance file.

        Args:
            scenario: the scenario to list

        Returns:
            a dataframe indexed by ``name`` (the context name, i.e. the instance file name) with the columns
            ``subset`` (the CGMES subset: ``EQ``, ``SSH``, ``TP``, ``SV``, ``EQ_BD``, ...) and ``graph`` (the IRI
            of the named graph in the database)
        """
        return create_data_frame_from_series_array(
            _pp.get_rdf_db_graphs(self._check_open(), _check_scenario(scenario)))

    def clear(self, scenario: str) -> None:
        """
        Drop every graph of a scenario. Other scenarios of the same database are untouched.

        Args:
            scenario: the scenario to empty
        """
        _pp.clear_rdf_db(self._check_open(), _check_scenario(scenario))

    def load_cgmes(self, file: Union[str, PathLike], scenario: str, version: Optional[str] = None,
                   timestamp: Optional[datetime.datetime] = None, modelling_authority: Optional[str] = None,
                   profiles: Optional[Sequence[Profile]] = None, *, parameters: Optional[Dict[str, str]] = None,
                   report_node: Optional[ReportNode] = None) -> List[str]:
        """
        Read CGMES instance files into a scenario of the database.

        This is the expensive half of the split loading: the files are parsed here and never again.

        With nothing addressed - ``version``, ``timestamp`` and ``modelling_authority`` all ``None`` - the files
        are stored un-versioned: a graph of the same name that is already in the scenario is *replaced*, so
        re-running an upload is idempotent rather than cumulative, and the return value is the graph names. A
        scenario that already holds snapshots refuses that form - its instance files belong to a snapshot and are
        never overwritten.

        As soon as any part of the address is given the files become a snapshot, and the return value is the
        stored model ids:

        * the **root** of their modelling authority's tree when it has none yet - which is how a new day gets in,
          with ``version='1'``. The scenario's first root stores the boundary; every later root (another TSO of the
          same day, named by ``modelling_authority``) must carry the same boundary;
        * one **further snapshot** otherwise. The state the new files derive from is materialised, the files are
          compared against it profile by profile, and the difference is stored. That is how a day of timestamps
          exported by a TSO reaches the database without anyone recording anything on a network: one call per
          timestamp, each naming the moment it describes.

        Args:
            file: the CGMES data source: a zip archive, or a directory holding the instance files
            scenario: the scenario to write into, for instance ``"2021-02-09"``
            version: the version name of the snapshot. It must rank above the head of the timestamp's chain; a
                name the registry does not hold is appended to a permissive registry and refused by a strict one.
                ``None`` takes the lowest registered name ranking above the head (a permissive registry without one
                appends the next number it lacks: ``'1'`` for a scenario's first root, then ``'2'``, ...)
            timestamp: the moment the files describe, a timezone-aware datetime. ``None`` is the base timestamp,
                which for a root is taken from ``md:Model.scenarioTime`` of the steady state file
            modelling_authority: the tree the files belong to. ``None`` is the only tree of a scenario that holds
                one, whatever the files' headers state - unless their equipment and steady state hypothesis agree on
                another authority: such files are refused, to be named either way; for the first root of a scenario,
                or a scenario of several trees, it is the authority the equipment and steady state hypothesis
                headers agree on (refused when they do not). Adding the tree of a *second* authority to a versioned
                scenario names it
            profiles: for a root, the profiles to store (``None``: every profile the files carry); for a further
                snapshot, the profiles to compare (``None``: ``EQ`` and ``SSH``)
            parameters: a dictionary of CGMES import parameters; only the ones that influence how identifiers are
                read matter here, and the very same ones must be passed to :func:`from_rdf_db`
            report_node: the reporter to be used to create an execution report, default is None (no report)

        Returns:
            the names of the graphs that were written (un-versioned), or the stored model ids (a snapshot)

        Raises:
            TypeError: a naive ``timestamp``, or a ``version`` that is not a str
            ValueError: a ``profiles`` entry that is not a :data:`Profile`
            pypowsybl.PyPowsyblError: the scenario is versioned and nothing was addressed, the version does not
                rank above the head's or is not registered in a strict registry, the boundary is not the one the scenario shares, or the files changed
                nothing

        Note:
            By default the ingestion compares the **equipment model and the steady state hypothesis**. State
            variables and topology change wholesale between timestamps, so their files are left alone and the
            snapshot inherits the ones of the state it derives from - unless ``profiles`` names them.
        """
        return _pp.load_cgmes_to_rdf_db(self._check_open(), path_to_str(file), _check_scenario(scenario),
                                        _version_to_str(version), _timestamp_to_str(timestamp),
                                        _authority_to_str(modelling_authority), _profiles_to_list(profiles),
                                        {} if parameters is None else parameters, _report_handle(report_node))

    def load_cgmes_from_binary_buffers(self, buffers: List[io.BytesIO], scenario: str, version: Optional[str] = None,
                                       timestamp: Optional[datetime.datetime] = None,
                                       modelling_authority: Optional[str] = None,
                                       profiles: Optional[Sequence[Profile]] = None, *,
                                       parameters: Optional[Dict[str, str]] = None,
                                       report_node: Optional[ReportNode] = None) -> List[str]:
        """
        Read CGMES instance files held in memory into a scenario of the database.

        Args:
            buffers: the data buffers, each holding a **zip** archive of instance files
            scenario: the scenario to write into
            version: the version name of the snapshot, ``None`` for the next one; see :meth:`load_cgmes`
            timestamp: the moment the files describe, a timezone-aware datetime; ``None`` for the base timestamp
            modelling_authority: the tree the files belong to; ``None`` as in :meth:`load_cgmes`
            profiles: the profiles to store or compare, ``None`` for the default
            parameters: a dictionary of CGMES import parameters
            report_node: the reporter to be used to create an execution report, default is None (no report)

        Returns:
            the names of the graphs that were written, or the stored model ids; see :meth:`load_cgmes`, whose
            behaviour this shares exactly - including ingesting a further timestamp of a versioned scenario
        """
        buffer_list = [buffer.getbuffer() for buffer in buffers]
        return _pp.load_cgmes_buffers_to_rdf_db(self._check_open(), buffer_list, _check_scenario(scenario),
                                                _version_to_str(version), _timestamp_to_str(timestamp),
                                                _authority_to_str(modelling_authority), _profiles_to_list(profiles),
                                                {} if parameters is None else parameters, _report_handle(report_node))


def connect_rdf_db(url: str, *, update_url: Optional[str] = None, graph_store_url: Optional[str] = None,
                   user: Optional[str] = None, password: Optional[str] = None,
                   headers: Optional[Dict[str, str]] = None, query_mode: str = 'local',
                   fetch_parallelism: int = 4, upload_parallelism: int = 4, gzip: Optional[bool] = None,
                   connect_timeout: float = 10.0, read_timeout: float = 300.0,
                   cache: bool = True) -> RdfDatabase:
    """
    Open a connection to an RDF graph database.

    Args:
        url: where the database is. ``memory:<name>`` gives an in-process store, which needs no server and is the
            right thing for tests and small experiments. Otherwise the URL of a SPARQL 1.1 endpoint: a Fuseki
            dataset (``http://host:3030/ds``), or an rdf4j-server / GraphDB repository
            (``http://host:8080/rdf4j-server/repositories/<id>``)
        update_url: the SPARQL update endpoint, when it is not where its URL layout says it is
        graph_store_url: the Graph Store Protocol endpoint, when it is not where its URL layout says it is. Without
            one, uploads and downloads fall back on SPARQL requests, which is correct but slower
        user: the user name for HTTP basic authentication
        password: the password for HTTP basic authentication
        headers: additional HTTP headers sent with every request, for instance a bearer token
        query_mode: ``'local'`` (the default) fetches the graphs and runs the CGMES queries in this process;
            ``'remote'`` runs them on the server. Local is faster for anything but a huge model on a thin client
        fetch_parallelism: how many graphs are downloaded at once (default 4)
        upload_parallelism: how many graphs are uploaded at once (default 4)
        gzip: compress the transferred graphs; the default compresses unless the server is on the loopback interface
        connect_timeout: the connection timeout in seconds
        read_timeout: the read timeout in seconds; a big model takes a while to serialise
        cache: keep parsed graphs in memory between loads, on by default. The graphs of a versioned scenario are
            written once and never overwritten, so a cached graph stays valid; loading a second snapshot of the
            same day then only transfers the differences

    Returns:
        the open connection

    Examples:
        .. code-block:: python

            with pp.network.connect('memory:demo') as db:
                db.load_cgmes('grid.zip', '2021-02-09', '1')
                network = pp.network.from_rdf_db(db, '2021-02-09')

            with pp.network.connect('http://localhost:3030/ds', user='admin', password='admin') as db:
                db.load_cgmes('grid.zip', '2021-02-09')
    """
    if query_mode not in _QUERY_MODES:
        raise ValueError(f'query_mode must be one of {list(_QUERY_MODES)}, got {query_mode!r}')
    options: Dict[str, str] = {
        'query_mode': query_mode,
        'fetch_parallelism': str(fetch_parallelism),
        'upload_parallelism': str(upload_parallelism),
        'connect_timeout_ms': str(int(connect_timeout * 1000)),
        'read_timeout_ms': str(int(read_timeout * 1000)),
        'cache': str(cache).lower(),
    }
    if update_url is not None:
        options['update_url'] = update_url
    if graph_store_url is not None:
        options['graph_store_url'] = graph_store_url
    if user is not None:
        options['user'] = user
    if password is not None:
        options['password'] = password
    if gzip is not None:
        options['gzip'] = str(gzip).lower()
    for name, value in (headers or {}).items():
        options[f'header.{name}'] = value
    if url.startswith('memory:'):
        # The in-process backend has no endpoint: nothing about HTTP applies to it. The Java side would drop these
        # keys itself; stripping them here keeps the option map honest about what the backend actually uses.
        for key in ('update_url', 'graph_store_url', 'user', 'password', 'connect_timeout_ms', 'read_timeout_ms'):
            options.pop(key, None)
        for key in [k for k in options if k.startswith('header.')]:
            options.pop(key)
    return RdfDatabase(url, _pp.create_rdf_db_connection(url, options))


connect = connect_rdf_db
"""Alias of :func:`connect_rdf_db`, for ``with connect(database) as db:``."""


class _VariantRequests:
    """The four parallel lists a bulk load hands the native layer: one entry per requested snapshot."""

    def __init__(self) -> None:
        self.ids: List[str] = []
        self.versions: List[str] = []
        self.timestamps: List[str] = []
        self.authorities: List[str] = []

    def add(self, variant_id: str, version: Optional[str], timestamp: Optional[datetime.datetime],
            modelling_authority: Optional[str]) -> None:
        self.ids.append(variant_id)
        self.versions.append(_version_to_str(version))
        self.timestamps.append(_timestamp_to_str(timestamp))
        self.authorities.append(_authority_to_str(modelling_authority))


def _variant_requests(version: Optional[str], modelling_authority: Optional[str],
                      timestamps: Optional[Sequence[datetime.datetime]],
                      variants: Optional[Mapping[str, Union[datetime.datetime, SnapshotAddress]]]) -> _VariantRequests:
    """
    Flatten ``timestamps=[...]`` or ``variants={...}`` into the parallel lists the native layer takes.

    Raises:
        ValueError: the request list is empty, or a mapping value is neither a timestamp nor a
            ``(version, timestamp, modelling_authority)`` triple
    """
    requests = _VariantRequests()
    if timestamps is not None:
        for timestamp in timestamps:
            requests.add('', version, timestamp, modelling_authority)
    else:
        assert variants is not None
        for variant_id, address in variants.items():
            _check_variant(variant_id)
            if isinstance(address, (tuple, list)):
                if len(address) != 3:
                    raise ValueError(f"Address {address!r} of variant '{variant_id}' must be a timestamp or a "
                                     '(version, timestamp, modelling_authority) triple')
                requests.add(variant_id, address[0], address[1], address[2])
            else:
                requests.add(variant_id, version, address, modelling_authority)
    if not requests.ids:
        raise ValueError('At least one snapshot is needed to load a network as variants')
    return requests


def _check_refusals(network: 'Network', on_refusal: str) -> None:
    """Turn the refused rows a bulk load left on the network into an error, unless the caller asked to skip them."""
    binding = network.variants_binding()
    refused = binding[binding['status'] == 'refused']
    if refused.empty or on_refusal == 'skip':
        return
    reasons = [line for reason in refused['reasons'] for line in _split_reasons(str(reason))]
    named = ', '.join(f"{variant} ({timestamp})" for variant, timestamp
                      in zip(refused.index, refused['timestamp']))
    raise RdfDbVariantRefusedError(
        f'{len(refused)} of the requested snapshots cannot be reached inside a variant: {named}. '
        f'{"; ".join(reasons)}. Pass on_refusal="skip" to get the network with the variants that worked, '
        'or load the refused snapshots as networks of their own.',
        variant=str(refused.index[0]), reasons=reasons, refused=refused)


def from_rdf_db(db: RdfDatabase, scenario: str, version: Optional[str] = None,
                timestamp: Optional[datetime.datetime] = None, modelling_authority: Optional[str] = None,
                profiles: Optional[Sequence[Profile]] = None, *, exact: bool = False,
                timestamps: Optional[Sequence[datetime.datetime]] = None,
                variants: Optional[Mapping[str, Union[datetime.datetime, SnapshotAddress]]] = None,
                on_refusal: str = 'raise', parameters: Optional[Dict[str, str]] = None,
                post_processors: Optional[List[str]] = None, report_node: Optional[ReportNode] = None,
                allow_variant_multi_thread_access: bool = False) -> 'Network':
    """
    Build a network from one snapshot of one scenario of an RDF database.

    The network is the one the instance files themselves would have produced. One consequence of loading from a
    database rather than from files: a combined grid model is *not* split into subnetworks, because that split
    happens at file level, above any triple store. Every modelling authority of a scenario is a tree of its own and
    is loaded on its own; :meth:`RdfDatabase.assembly` lists what a common grid model of one moment is made of.

    ``version``, ``timestamp`` and ``modelling_authority`` address a snapshot inside the scenario:
    ``(scenario, None, None)`` is the newest version of the base timestamp, ``(scenario, None, t)`` the newest
    version of the moment ``t``, and ``(scenario, 'ID', t)`` the highest ranking version at or below ``'ID'`` that the
    moment holds - exactly ``'ID'`` with ``exact=True``. ``modelling_authority=None``
    is the only authority of the scenario; a scenario holding several refuses it and names them. On an
    un-versioned scenario nothing may be addressed.

    **A whole day in one network.** With ``timestamps=[...]`` - or ``variants={...}`` for explicit names and mixed
    addresses - every requested snapshot becomes a *variant* of one network: the first one is converted and the
    others are clones plus the stored differences between them, which is one chain query, one statement fetch and
    one conversion whatever the number of timestamps. Switch between them with
    :meth:`pypowsybl.network.Network.set_working_variant` and see what they stand for with
    :meth:`pypowsybl.network.Network.variants_binding`.

    Args:
        db: an open connection, see :func:`connect_rdf_db`
        scenario: the name of the base scenario (grid model / day) to load from, for instance ``'2021-02-09'``;
            required, never guessed
        version: the snapshot version, a name (``'2'``, ``'ID'``); ``None`` is the newest one, a name the highest
            ranking version at or below it. With ``timestamps``/``variants`` it is the version every requested
            timestamp is taken at
        timestamp: the moment, a timezone-aware :class:`datetime.datetime`; ``None`` is the base timestamp. A
            naive datetime raises :class:`TypeError`
        modelling_authority: the tree to load from; ``None`` for the only one of the scenario
        profiles: the CGMES profiles to load, see :data:`Profile`; ``None`` loads every profile of the snapshot.
            With ``timestamps``/``variants`` they are the profiles each variant is brought forward by
        exact: read exactly ``version`` (and every named version of ``variants``) instead of the highest ranking
            version at or below it; a timestamp that does not hold it is an error. Needs a ``version``
        timestamps: load these timestamps as the variants of one network, each at ``version`` in the tree of
            ``modelling_authority``. The variants are named after their ISO instant (``'2021-02-09T08:30:00Z'``).
            That is the short form of the default naming of the core library, which says ``version@instant`` when
            two requests share an instant and ``authority/version@instant`` (for instance
            ``'http://elia.be/CGMES/1@2021-02-09T08:30:00Z'``) when the requests span several modelling
            authorities - decided by the requests, never by what else the scenario holds. Exclusive with
            ``timestamp`` and with ``variants``
        variants: the same, with the variant names chosen by the caller: ``{'morning': t}``, or
            ``{'morning': ('2', t, None)}`` - ``(version, timestamp, modelling_authority)`` - when that variant is at
            another address than the call's
        on_refusal: what to do when a requested snapshot cannot be reached inside a variant (an equipment drift,
            or a value IIDM does not store per variant). ``'raise'`` (the default) raises
            :class:`RdfDbVariantRefusedError` listing every refused snapshot; ``'skip'`` returns the network with
            the variants that worked and leaves the refusals in :meth:`Network.variants_binding`. It is validated
            whichever form of the call is used
        parameters: a dictionary of CGMES import parameters; pass the same ones that were used for
            :meth:`RdfDatabase.load_cgmes`
        post_processors: a list of import post processors (added to the ones defined by the platform config). They
            are only honoured when nothing is addressed and no ``profiles`` are given, because the snapshot entry
            points of the core library take no load options; giving both raises an error rather than ignoring them.
            They are never honoured together with ``timestamps``/``variants``
        report_node: the reporter to be used to create an execution report, default is None (no report)
        allow_variant_multi_thread_access: allow multi-thread access to variant (default: False). With
            ``timestamps``/``variants`` it is set once all variants exist, which is the only safe moment

    Returns:
        the network

    Raises:
        TypeError: a naive ``timestamp``, or a ``version`` that is not a str
        ValueError: ``timestamp``, ``timestamps`` and ``variants`` are combined, a bad ``on_refusal``, an
            unknown profile, or ``exact`` without a ``version``
        RdfDbVariantRefusedError: a requested snapshot cannot be reached inside a variant and ``on_refusal`` is
            ``'raise'``
        pypowsybl.PyPowsyblError: the scenario or the snapshot does not exist (the message lists what does), the
            modelling authority is ambiguous, or the database cannot be reached

    Note:
        Graphs are fetched once per connection and cached, so loading a second snapshot of the same day only
        transfers the differences between them.

    Examples:
        .. code-block:: python

            from datetime import datetime, timedelta, timezone
            t = datetime(2021, 2, 9, 8, 30, tzinfo=timezone.utc)
            network = pp.network.from_rdf_db(db, '2021-02-09', '2', t)
            day = pp.network.from_rdf_db(db, '2021-02-09', timestamps=[t, t + timedelta(minutes=15)])
            day.set_working_variant('2021-02-09T08:45:00Z')
    """
    from .network import Network  # pylint: disable=import-outside-toplevel,cyclic-import
    _check_scenario(scenario)
    if on_refusal not in _ON_REFUSAL:
        raise ValueError(f'on_refusal must be one of {list(_ON_REFUSAL)}, got {on_refusal!r}')
    if isinstance(timestamps, datetime.datetime):
        raise ValueError('timestamps= takes a list of timestamps, not one timestamp; write timestamps=[t] or '
                         'timestamp=t')
    if timestamps is not None and variants is not None:
        raise ValueError('timestamps=[...] names the snapshots and variants={...} names them and their variants; '
                         'give one of the two')
    if (timestamps is not None or variants is not None) and timestamp is not None:
        raise ValueError('timestamp= addresses one snapshot and timestamps=/variants= address several; '
                         'give one of the two')
    profile_list = _profiles_to_list(profiles)
    if exact and version is None and timestamps is None and variants is None:
        raise ValueError('exact=True reads exactly the named version: give a version')
    if timestamps is None and variants is None:
        return Network(_pp.load_network_from_rdf_db(db._check_open(),  # pylint: disable=protected-access
                                                    scenario, _version_to_str(version), exact,
                                                    _timestamp_to_str(timestamp),
                                                    _authority_to_str(modelling_authority), profile_list,
                                                    {} if parameters is None else parameters,
                                                    [] if post_processors is None else post_processors,
                                                    _report_handle(report_node),
                                                    allow_variant_multi_thread_access))
    if post_processors:
        raise ValueError('post_processors are not supported when loading snapshots as variants; the snapshot '
                         'entry points of the core library take no load options')
    requests = _variant_requests(version, modelling_authority, timestamps, variants)
    network = Network(_pp.load_network_variants_from_rdf_db(
        db._check_open(), scenario, requests.ids,  # pylint: disable=protected-access
        requests.versions, exact, requests.timestamps, requests.authorities, profile_list,
        {} if parameters is None else parameters, _report_handle(report_node),
        allow_variant_multi_thread_access))
    _check_refusals(network, on_refusal)
    return network
