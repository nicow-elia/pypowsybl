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

Inside a scenario, a grid state is a **snapshot** addressed by a *timestep* (a moment of the day) and a *version*
(a study state of that moment). The three together - ``(scenario, timestep, version)`` - are the address of every
call: :func:`from_rdf_db` reads one, :meth:`pypowsybl.network.Network.update_from_rdf_db` walks to one, and
:meth:`pypowsybl.network.NetworkEventRecorder.to_rdf_updates` writes one.
"""
from __future__ import annotations  # Necessary for type alias like _DataFrame to work with sphinx

import datetime
import io
from os import PathLike
from types import TracebackType
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple, Type, Union, TYPE_CHECKING

from pandas import DataFrame

import pypowsybl._pypowsybl as _pp
from pypowsybl.report import ReportNode
from pypowsybl.utils import create_data_frame_from_series_array, path_to_str

if TYPE_CHECKING:
    from .network import Network

_QUERY_MODES = ('local', 'remote')
_ON_REFUSAL = ('raise', 'skip')

Timestep = Union[str, datetime.datetime, None]
"""
How a moment of a scenario's day is written.

``None`` is the base timestep of the scenario (the moment its root snapshot describes). A ``str`` is either an ISO
instant (``"2021-02-09T08:30:00Z"``), an offset date-time, a local date-time read as UTC, or a **label** of the
scenario's base day (``"8:30"``, ``"08:30"``, ``"08:30:00"``). A :class:`datetime.datetime` is converted to its ISO
text; a naive one is read as UTC.

A label is always resolved against the base day of the scenario it is used in, so ``"8:30"`` means two different
moments in two scenarios describing two days.
"""


def _timestep_to_str(timestep: Timestep) -> str:
    """
    Turn a timestep into the text the native layer takes; labels and ISO texts pass through untouched.

    Raises:
        TypeError: the timestep is neither a string, a datetime nor None
    """
    if timestep is None:
        return ''
    if isinstance(timestep, datetime.datetime):
        if timestep.tzinfo is None:
            return timestep.replace(tzinfo=datetime.timezone.utc).isoformat()
        return timestep.isoformat()
    if not isinstance(timestep, str):
        raise TypeError(f'A timestep must be a string, a datetime or None, got {type(timestep).__name__}')
    return timestep


def _version_to_str(version: Optional[str]) -> str:
    """
    Turn a version label into the text the native layer takes; ``None`` means "the newest one".

    Raises:
        TypeError: the version is not a string
    """
    if version is None:
        return ''
    if not isinstance(version, str):
        raise TypeError(f'A version label must be a string, for instance "1.1", got {type(version).__name__}')
    return version


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
                network.update_from_rdf_db(db, '2021-02-09', '1.1', '9:30', variant='9:30')
            except pp.network.RdfDbVariantRefusedError as e:
                print(e.variant, e.reasons)
                network = pp.network.from_rdf_db(db, '2021-02-09', '1.1', '9:30')  # a network of its own
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


class RdfDatabase:
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

    **The vocabulary of the catalogue.** A *scenario* is one base grid model - in practice one day - with its own
    root, its own timesteps and its own versions; several days in a database are several scenarios, and a
    difference never crosses them. A *timestep* is a moment of that day, and the *base timestep* is the moment the
    root describes. A *version* is one study state of a timestep. A *snapshot* is a consistent grid state addressed
    by the triple ``(scenario, timestep, version)``. Labels such as ``"8:30"`` are wall times of the scenario's own
    base day, so the same label means two different moments in two scenarios.

    The catalogue is read as dataframes: :meth:`scenarios`, :meth:`snapshots`, :meth:`versions`, :meth:`timesteps`
    and :meth:`models`.
    """

    def __init__(self, url: str, handle: _pp.JavaHandle) -> None:
        self._url = url
        self._handle = handle
        self._closed = False

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
        return f'{self.__class__.__name__}(url={self._url!r}, closed={self._closed})'

    def close(self) -> None:
        """
        Close the connection. Calling it twice, or after the object was used as a context manager, does nothing.
        """
        if not self._closed:
            self._closed = True
            _pp.close_rdf_db_connection(self._handle)

    def __del__(self) -> None:
        # An interpreter shutdown can take the extension module away before the last reference dies
        try:
            self.close()
        except Exception:  # pylint: disable=broad-except
            pass

    def _check_open(self) -> _pp.JavaHandle:
        if self._closed:
            raise _pp.PyPowsyblError(f'RDF database connection to {self._url} is closed')
        return self._handle

    @property
    def closed(self) -> bool:
        """Whether :meth:`close` has already been called."""
        return self._closed

    def scenarios(self) -> DataFrame:
        """
        One row per scenario stored in the database.

        Returns:
            a dataframe indexed by ``scenario`` (str, as given at upload) with the columns ``base_timestep``
            (ISO instant of the root snapshot, empty when the scenario is un-versioned), ``versioned`` (bool) and
            ``snapshot_count`` (int). Sorted by name.
        """
        return create_data_frame_from_series_array(_pp.get_rdf_db_scenario_table(self._check_open()))

    def versioned(self, scenario: str) -> bool:
        """
        Whether a scenario holds snapshots, and can therefore be addressed by version and timestep.

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
            a dataframe indexed by ``snapshot`` (the snapshot IRI) with the columns ``scenario``, ``version`` (str),
            ``timestep`` (ISO instant, UTC, the key), ``timestep_label`` (``HH:MM`` of the scenario's base day,
            display only), ``kind``
            (``full``/``diff``), ``parent`` (snapshot IRI or empty), ``edge`` (``version``/``timestep``/empty),
            ``depth`` (int), ``has_full`` (bool), ``fast`` (bool: reachable by the in-place diff route), ``members``
            (the stored model ids, ``;``-joined), ``created`` (ISO) and ``description``. Sorted by timestep, then by
            depth. A scenario the database does not hold gives an empty frame with those columns.
        """
        return create_data_frame_from_series_array(
            _pp.get_rdf_db_snapshots(self._check_open(), _check_scenario(scenario)))

    def versions(self, scenario: str, timestep: Timestep = None) -> DataFrame:
        """
        The version chain of one timestep of a scenario.

        Args:
            scenario: the scenario to list
            timestep: the timestep, ``None`` for the base timestep of the scenario

        Returns:
            the same columns as :meth:`snapshots`, restricted to one timestep and ordered by depth
        """
        return create_data_frame_from_series_array(
            _pp.get_rdf_db_versions(self._check_open(), _check_scenario(scenario), _timestep_to_str(timestep)))

    def timesteps(self, scenario: str) -> DataFrame:
        """
        One row per timestep of a scenario.

        Args:
            scenario: the scenario to list

        Returns:
            a dataframe indexed by ``timestep`` with the columns ``scenario``, ``label``, ``root`` (IRI of the
            timestep's root snapshot), ``head`` (IRI of its newest version), ``version_count`` (int) and
            ``pinned_base`` (IRI of the base-chain snapshot the timestep derives from, empty for the base timestep)
        """
        return create_data_frame_from_series_array(
            _pp.get_rdf_db_timesteps(self._check_open(), _check_scenario(scenario)))

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

    def checkpoint(self, scenario: str, version: Optional[str] = None, timestep: Timestep = None) -> str:
        """
        Materialise a snapshot as a full state, so that loading it needs no walk down the difference chain.

        A checkpoint changes nothing about what the snapshot *is*: the same network comes back before and after. It
        only trades storage for load time, and is worth it at the end of a long chain of timesteps.

        Args:
            scenario: the scenario holding the snapshot
            version: the version label, ``None`` for the newest one
            timestep: the timestep, ``None`` for the base timestep

        Returns:
            the IRI of the snapshot that was materialised
        """
        return _pp.create_rdf_db_checkpoint(self._check_open(), _check_scenario(scenario),
                                            _version_to_str(version), _timestep_to_str(timestep))

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
                   timestep: Timestep = None, *, parameters: Optional[Dict[str, str]] = None,
                   report_node: Optional[ReportNode] = None) -> List[str]:
        """
        Read CGMES instance files into a scenario of the database.

        This is the expensive half of the split loading: the files are parsed here and never again.

        Without a ``version`` the files are stored un-versioned: a graph of the same name that is already in the
        scenario is *replaced*, so re-running an upload is idempotent rather than cumulative, and the return value
        is the graph names. A scenario that already holds snapshots refuses that form - its instance files belong
        to a snapshot and are never overwritten.

        With a ``version`` the files become a snapshot, and the return value is the stored model ids:

        * the **root** of a scenario that is still empty - which is how a new day gets in, with ``version='1.0'``;
        * one **further snapshot** otherwise. The state the new files derive from is materialised, the files are
          compared against it profile by profile, and the difference is stored. That is how a day of timesteps
          exported by a TSO reaches the database without anyone recording anything on a network: one call per
          timestep, each naming the moment it describes.

        Args:
            file: the CGMES data source: a zip archive, or a directory holding the instance files
            scenario: the scenario to write into, for instance ``"2021-02-09"``
            version: the version label of the snapshot, for instance ``'1.0'``; ``None`` stores the files
                un-versioned
            timestep: the moment the files describe, see :data:`Timestep` - an instant, or a ``'20:30'`` label of
                the scenario's base day. ``None`` is the base timestep, which for a root is taken from
                ``md:Model.scenarioTime`` of the steady state file
            parameters: a dictionary of CGMES import parameters; only the ones that influence how identifiers are
                read matter here, and the very same ones must be passed to :func:`from_rdf_db`
            report_node: the reporter to be used to create an execution report, default is None (no report)

        Returns:
            the names of the graphs that were written (un-versioned), or the stored model ids (a snapshot)

        Raises:
            pypowsybl.PyPowsyblError: the scenario is versioned and no version was given, the address is already
                taken, the boundary is not the one the scenario was rooted with, or the files changed nothing

        Note:
            The ingestion compares the **equipment model and the steady state hypothesis**. State variables and
            topology change wholesale between timesteps, so their files are left alone and the snapshot inherits
            the ones of the state it derives from.
        """
        return _pp.load_cgmes_to_rdf_db(self._check_open(), path_to_str(file), _check_scenario(scenario),
                                        _version_to_str(version), _timestep_to_str(timestep),
                                        {} if parameters is None else parameters, _report_handle(report_node))

    def load_cgmes_from_binary_buffers(self, buffers: List[io.BytesIO], scenario: str, version: Optional[str] = None,
                                       timestep: Timestep = None, *,
                                       parameters: Optional[Dict[str, str]] = None,
                                       report_node: Optional[ReportNode] = None) -> List[str]:
        """
        Read CGMES instance files held in memory into a scenario of the database.

        Args:
            buffers: the data buffers, each holding a **zip** archive of instance files
            scenario: the scenario to write into
            version: the version label of the snapshot, ``None`` for an un-versioned upload
            timestep: the moment the files describe, ``None`` for the base timestep - which for a root is taken
                from the steady state file
            parameters: a dictionary of CGMES import parameters
            report_node: the reporter to be used to create an execution report, default is None (no report)

        Returns:
            the names of the graphs that were written, or the stored model ids; see :meth:`load_cgmes`, whose
            behaviour this shares exactly - including ingesting a further timestep of a versioned scenario
        """
        buffer_list = [buffer.getbuffer() for buffer in buffers]
        return _pp.load_cgmes_buffers_to_rdf_db(self._check_open(), buffer_list, _check_scenario(scenario),
                                                _version_to_str(version), _timestep_to_str(timestep),
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
                db.load_cgmes('grid.zip', '2021-02-09')
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


VariantAddress = Union[Timestep, Tuple[Optional[str], Timestep], List[Any]]
"""
Where one variant of a :func:`from_rdf_db` ``variants={...}`` mapping stands: a timestep, or a
``(version, timestep)`` pair when that variant is at another version than the call's ``version``.
"""


def _variant_requests(version: Optional[str], timesteps: Optional[Sequence[Timestep]],
                      variants: Optional[Mapping[str, VariantAddress]]) -> Tuple[List[str], List[str], List[str]]:
    """
    Flatten ``timesteps=[...]`` or ``variants={...}`` into the three parallel string arrays the native layer takes.

    Raises:
        ValueError: the request list is empty, or a mapping value is not a timestep nor a ``(version, timestep)``
    """
    ids: List[str] = []
    versions: List[str] = []
    steps: List[str] = []
    if timesteps is not None:
        for step in timesteps:
            ids.append('')
            versions.append(_version_to_str(version))
            steps.append(_timestep_to_str(step))
    else:
        assert variants is not None
        for variant_id, address in variants.items():
            if not isinstance(variant_id, str) or not variant_id.strip():
                raise ValueError(f'A variant identifier must be a non-blank string, got {variant_id!r}')
            if isinstance(address, (tuple, list)):
                if len(address) != 2:
                    raise ValueError(f"Address {address!r} of variant '{variant_id}' must be a timestep or a "
                                     '(version, timestep) pair')
                variant_version, variant_step = address[0], address[1]
            else:
                variant_version, variant_step = version, address
            ids.append(variant_id)
            versions.append(_version_to_str(variant_version))
            steps.append(_timestep_to_str(variant_step))
    if not steps:
        raise ValueError('At least one snapshot is needed to load a network as variants')
    return ids, versions, steps


def _check_refusals(network: 'Network', on_refusal: str) -> None:
    """Turn the refused rows a bulk load left on the network into an error, unless the caller asked to skip them."""
    binding = network.variants_binding()
    refused = binding[binding['status'] == 'refused']
    if refused.empty or on_refusal == 'skip':
        return
    reasons = [line for reason in refused['reasons'] for line in _split_reasons(str(reason))]
    named = ', '.join(f"{variant} ({timestep})" for variant, timestep
                      in zip(refused.index, refused['timestep']))
    raise RdfDbVariantRefusedError(
        f'{len(refused)} of the requested snapshots cannot be reached inside a variant: {named}. '
        f'{"; ".join(reasons)}. Pass on_refusal="skip" to get the network with the variants that worked, '
        'or load the refused snapshots as networks of their own.',
        variant=str(refused.index[0]), reasons=reasons, refused=refused)


def from_rdf_db(db: RdfDatabase, scenario: str, version: Optional[str] = None, timestep: Timestep = None, *,
                timesteps: Optional[Sequence[Timestep]] = None,
                variants: Optional[Mapping[str, VariantAddress]] = None, on_refusal: str = 'raise',
                parameters: Optional[Dict[str, str]] = None, post_processors: Optional[List[str]] = None,
                report_node: Optional[ReportNode] = None,
                allow_variant_multi_thread_access: bool = False) -> 'Network':
    """
    Build a network from one snapshot of one scenario of an RDF database.

    The network is the one the instance files themselves would have produced. One consequence of loading from a
    database rather than from files: a combined grid model is *not* split into subnetworks, because that split
    happens at file level, above any triple store. Load each individual model into its own scenario if you need the
    subnetworks.

    ``version`` and ``timestep`` address a snapshot inside the scenario: ``(scenario, None, None)`` is the newest
    version of the scenario's base timestep, ``(scenario, None, "8:30")`` the newest version of that moment, and
    ``(scenario, "1.1", "8:30")`` exactly that study state. On an un-versioned scenario both must be ``None``.

    **A whole day in one network.** With ``timesteps=[...]`` - or ``variants={...}`` for explicit names and mixed
    versions - every requested snapshot becomes a *variant* of one network: the first one is converted and the
    others are clones plus the stored differences between them, which is one chain query, one statement fetch and
    one conversion whatever the number of timesteps. Switch between them with
    :meth:`pypowsybl.network.Network.set_working_variant` and see what they stand for with
    :meth:`pypowsybl.network.Network.variants_binding`.

    Args:
        db: an open connection, see :func:`connect_rdf_db`
        scenario: the name of the base scenario (grid model / day) to load from, for instance ``'2021-02-09'``;
            required, never guessed
        version: the snapshot version label, for instance ``'1.1'``; ``None`` is the newest one. With
            ``timesteps``/``variants`` it is the version every requested timestep is taken at
        timestep: the moment of the scenario's day, see :data:`Timestep`; ``None`` is the base timestep
        timesteps: load these timesteps as the variants of one network, each at ``version``. The variants are named
            after the timestep labels (``'08:30'``), or ``version@label`` when two requests share a label.
            Exclusive with ``timestep`` and with ``variants``
        variants: the same, with the variant names chosen by the caller: ``{'morning': '8:30'}``, or
            ``{'morning': ('1.1', '8:30')}`` when that variant is at another version than ``version``
        on_refusal: what to do when a requested snapshot cannot be reached inside a variant (an equipment drift,
            or a value IIDM does not store per variant). ``'raise'`` (the default) raises
            :class:`RdfDbVariantRefusedError` listing every refused snapshot; ``'skip'`` returns the network with
            the variants that worked and leaves the refusals in :meth:`Network.variants_binding`. It is validated
            whichever form of the call is used
        parameters: a dictionary of CGMES import parameters; pass the same ones that were used for
            :meth:`RdfDatabase.load_cgmes`
        post_processors: a list of import post processors (added to the ones defined by the platform config). They
            are only honoured when no snapshot is addressed - ``version`` and ``timestep`` both ``None`` - because
            the snapshot entry points of the core library take no load options; giving both raises an error rather
            than ignoring them. They are never honoured together with ``timesteps``/``variants``
        report_node: the reporter to be used to create an execution report, default is None (no report)
        allow_variant_multi_thread_access: allow multi-thread access to variant (default: False). With
            ``timesteps``/``variants`` it is set once all variants exist, which is the only safe moment

    Returns:
        the network

    Raises:
        ValueError: ``timestep``, ``timesteps`` and ``variants`` are combined, or a bad ``on_refusal``
        RdfDbVariantRefusedError: a requested snapshot cannot be reached inside a variant and ``on_refusal`` is
            ``'raise'``
        pypowsybl.PyPowsyblError: the scenario or the snapshot does not exist (the message lists what does), or the
            database cannot be reached

    Note:
        Graphs are fetched once per connection and cached, so loading a second snapshot of the same day only
        transfers the differences between them.

    Examples:
        .. code-block:: python

            network = pp.network.from_rdf_db(db, '2021-02-09', '1.1', '8:30')
            day = pp.network.from_rdf_db(db, '2021-02-09', '1.1', timesteps=['8:00', '8:15', '8:30'])
            day.set_working_variant('08:15')
    """
    from .network import Network  # pylint: disable=import-outside-toplevel,cyclic-import
    _check_scenario(scenario)
    if on_refusal not in _ON_REFUSAL:
        raise ValueError(f'on_refusal must be one of {list(_ON_REFUSAL)}, got {on_refusal!r}')
    if isinstance(timesteps, str):
        raise ValueError(f'timesteps= takes a list of timesteps, not one timestep; write timesteps=[{timesteps!r}] '
                         f'or timestep={timesteps!r}')
    if timesteps is not None and variants is not None:
        raise ValueError('timesteps=[...] names the snapshots and variants={...} names them and their variants; '
                         'give one of the two')
    if (timesteps is not None or variants is not None) and timestep is not None:
        raise ValueError('timestep= addresses one snapshot and timesteps=/variants= address several; '
                         'give one of the two')
    if timesteps is None and variants is None:
        return Network(_pp.load_network_from_rdf_db(db._check_open(),  # pylint: disable=protected-access
                                                    scenario, _version_to_str(version),
                                                    _timestep_to_str(timestep),
                                                    {} if parameters is None else parameters,
                                                    [] if post_processors is None else post_processors,
                                                    _report_handle(report_node),
                                                    allow_variant_multi_thread_access))
    if post_processors:
        raise ValueError('post_processors are not supported when loading snapshots as variants; the snapshot '
                         'entry points of the core library take no load options')
    ids, versions, steps = _variant_requests(version, timesteps, variants)
    network = Network(_pp.load_network_variants_from_rdf_db(
        db._check_open(), scenario, ids, versions, steps,  # pylint: disable=protected-access
        {} if parameters is None else parameters, _report_handle(report_node),
        allow_variant_multi_thread_access))
    _check_refusals(network, on_refusal)
    return network
