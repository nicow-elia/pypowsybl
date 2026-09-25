#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from __future__ import annotations  # Necessary for type alias like _DataFrame to work with sphinx

import codecs
import datetime
import io
import os
import warnings
import zipfile
from os import PathLike
from types import TracebackType
from typing import (
    IO,
    Any,
    Dict,
    List,
    Optional,
    Sequence,
    Type,
    Union,
    TYPE_CHECKING,
)

from pandas import DataFrame

import pypowsybl._pypowsybl as _pp
from pypowsybl.utils import create_data_frame_from_series_array, path_to_str

from .rdf_db import Timestep, _check_scenario, _timestep_to_str, _version_to_str

if TYPE_CHECKING:
    from .network import Network
    from .rdf_db import RdfDatabase

ProfileValue = Union[str, int, Sequence[str], datetime.datetime, Dict[str, Any]]
"""The type of a metadata keyword argument of an export: a plain value, or a value per profile."""

FileTarget = Union[str, PathLike, IO[str], IO[bytes], None]
"""Where an export writes: a path, an open file, or ``None`` to get the document back as a string."""

_UNSUPPORTED_VALUES = ('raise', 'ignore')
_GRANULARITY_VALUES = ('full_object', 'changed_only')

_PROFILES = ('EQ', 'SSH')


class NetworkEventRecorder:
    """
    Records the changes made to a :class:`Network` and exports them as CGMES update documents.

    Instances are created by :meth:`Network.event_recorder`. Used as a context manager, recording starts when the
    block is entered and stops when it is left, even if an exception is raised inside the block. The recorded
    events stay available after the block, so the export may happen inside or after it. Entering the same recorder
    again resumes recording and appends to the events already recorded; use :meth:`clear` to start from scratch.

    Every modification that goes through the network is recorded (``update_*`` methods, ``open_switch``, element
    creation and removal, extensions, and also the results written by a load flow). Only steady state hypothesis
    changes can be exported, see the user guide for the list; anything else is *unsupported* and is either
    rejected or skipped, depending on the ``unsupported`` argument of the export methods.

    A recorder is not thread safe and records the changes of all variants; changes made on another variant than
    the working variant at export time are unsupported. An export pauses the recording while it runs, so that the
    export cannot record its own writes; changes made concurrently by another thread during an export are
    therefore not recorded either.

    Examples:
        .. code-block:: python

            with network.event_recorder() as recorder:
                network.update_loads(id='LOAD', p0=10.0)
                with open('update_SSH.xml', 'w', encoding='utf-8') as f:
                    recorder.to_ssh(f)
    """

    def __init__(self, network: 'Network') -> None:
        self._network = network
        self._handle = _pp.create_network_event_recorder(network._handle)  # pylint: disable=protected-access
        # The recorder listens to the Java network behind this handle. A full reload of the Python object
        # (Network.update_from_rdf_db taking the 'full' route) swaps that handle, and the recorder then records
        # changes of a network nobody looks at any more: comparing the two is how an export notices.
        self._network_handle = network._handle  # pylint: disable=protected-access
        self._recording = False

    def _check_network(self) -> None:
        """
        Raises:
            pypowsybl.PyPowsyblError: the network this recorder was created on has been reloaded
        """
        if self._network._handle is not self._network_handle:  # pylint: disable=protected-access
            raise _pp.PyPowsyblError('The network was reloaded (full route of update_from_rdf_db); '
                                     'create a new event recorder')

    def __enter__(self) -> 'NetworkEventRecorder':
        self.start()
        return self

    def __exit__(self, exc_type: Optional[Type[BaseException]], exc_value: Optional[BaseException],
                 traceback: Optional[TracebackType]) -> None:
        self.stop()

    def __del__(self) -> None:
        # best effort: at interpreter shutdown the native isolate may already be gone
        try:
            self.stop()
        except Exception:  # pylint: disable=broad-except
            pass

    # pylint cannot infer the return type of a C extension function, hence the disable
    def __len__(self) -> int:  # pylint: disable=invalid-length-returned
        return _pp.get_network_event_count(self._handle)

    def __repr__(self) -> str:
        return (f"{self.__class__.__name__}(network='{self._network.id}', events={len(self)}, "
                f'recording={self._recording})')

    def start(self) -> None:
        """
        Start recording, if it is not already running.

        Changes made before this call are not recorded; the changes recorded so far are kept.
        """
        _pp.start_network_event_recorder(self._handle)
        self._recording = True

    def stop(self) -> None:
        """
        Stop recording, if it is running.

        The recorded changes are kept and can still be exported; only :meth:`clear` drops them.
        """
        _pp.stop_network_event_recorder(self._handle)
        self._recording = False

    @property
    def recording(self) -> bool:
        """
        Whether the recorder is currently recording.

        The flag reflects the last :meth:`start` or :meth:`stop` that succeeded: if one of them raises, the
        recorder is left in the state it was in and so is this flag.
        """
        return self._recording

    @property
    def network(self) -> 'Network':
        """The network whose changes are recorded."""
        return self._network

    @property
    def events(self) -> DataFrame:
        """
        The recorded events as a dataframe, mainly for debugging.

        Index ``index`` (recording order); columns ``type`` (``UPDATE``, ``EXTENSION_UPDATE``, ``CREATION``,
        ``REMOVAL``, ``PROPERTIES_UPDATE``, ``EXTENSION_CREATION``, ``EXTENSION_REMOVAL``, ``VARIANT``), ``id``,
        ``extension``, ``attribute`` (IIDM attribute name, e.g. ``p0``, ``open``,
        ``ratioTapChanger.tapPosition``), ``variant``, ``old_value``, ``new_value`` (values as strings, empty when
        not applicable). The exports compact repeated changes of one attribute to the last one; this dataframe is
        not compacted.
        """
        return create_data_frame_from_series_array(_pp.create_network_events_series_array(self._handle))

    def clear(self) -> None:
        """Drop the recorded changes. Whether the recorder is recording does not change."""
        _pp.clear_network_event_recorder(self._handle)

    def to_ssh(self, file: FileTarget = None, unsupported: str = 'raise', *, variant: Optional[str] = None,
               **metadata: ProfileValue) -> Optional[str]:
        """
        Export the recorded changes as a partial Steady State Hypothesis (SSH) instance file.

        The values are read from the network **at export time**: the recorded events say *what* changed, not what
        the value was. Export before making further unrelated changes.

        A partial SSH is still an SSH file, so the receiver has to be told to keep the values the file does not
        mention, with the import parameter ``iidm.import.cgmes.use-previous-values-during-update`` set to
        ``'true'``; otherwise the ordinary CGMES update resets them. A difference model needs nothing of the sort.

        Only steady state hypothesis changes fit in an SSH file. An operational limit value is one of them in a
        CGMES 3 (CIM100) model, where it is steady state data, and is not in a CGMES 2.4.15 one; an impedance or a
        voltage level limit never is. Such a change is unsupported here and needs
        :meth:`to_cgmes_diff`/:meth:`to_cgmes_diffs` with the ``EQ`` profile.

        Args:
            file: path (``str``/``PathLike``, written as UTF-8), an open text or binary file object, or ``None``
                to get the document as a string. Open text files with ``encoding='utf-8'``: the document declares
                UTF-8, so a text stream with another encoding produces a file whose declaration lies, which is
                reported as a :class:`UserWarning`.
            unsupported: ``'raise'`` (default): a change that cannot be expressed in an SSH file raises
                :class:`pypowsybl.PyPowsyblError` naming the change, nothing is written; ``'ignore'``: such
                changes are skipped (logged as warnings on the ``powsybl`` logger).
            variant: the network variant to export. The values written are that variant's values, changes recorded
                on other variants are dropped, and for a variant bound to a stored snapshot the header supersedes
                *that variant's* model and carries its scenario time. With more than one variant in the network a
                change IIDM does not store per variant belongs to all of them and is therefore unsupported. The
                working variant of the caller is unchanged.

                ``None`` (the default) exports the **working** variant, and on a network in **variant mode**
                that variant is treated exactly as if it had been named here - otherwise the document would carry
                its values under the *primary's* identity - including its refusal of a shared change, whichever
                variant is selected. Outside variant mode nothing changes: an unnamed export is what it was
                before variants existed, also while a variant you cloned yourself is the working one.
            **metadata: the header values of the exported model, see the user guide. Supported names are
                ``model_id``, ``version``, ``created``, ``scenario_time``, ``supersedes``, ``depends_on``,
                ``modeling_authority_set`` and ``description``.

        Returns:
            the XML document if ``file`` is ``None``, else ``None``.

        Raises:
            pypowsybl.PyPowsyblError: an unsupported change, a merged network, an unknown metadata name, or an
                unknown variant.
            ValueError: a bad ``unsupported`` value, or a comma inside an identifier.
        """
        self._check_network()
        options = self._flatten_options(unsupported, None, metadata, variant)
        return self._write(file, _pp.export_network_events_to_partial_ssh(self._handle, options))

    def to_cgmes_diff(self, file: FileTarget = None, profile: Optional[str] = None, unsupported: str = 'raise',
                      granularity: str = 'full_object', *, variant: Optional[str] = None,
                      **metadata: ProfileValue) -> Optional[str]:
        """
        Export the recorded changes as one ``dm:DifferenceModel`` document of one profile.

        Args:
            file: as in :meth:`to_ssh`.
            profile: ``'SSH'`` or ``'EQ'`` (case-insensitive), or ``None`` for the single touched profile. If the
                changes touch several profiles and ``profile`` is ``None`` a :class:`pypowsybl.PyPowsyblError`
                names them; with a profile given, changes of other profiles count as unsupported.
            unsupported: as in :meth:`to_ssh`.
            granularity: ``'full_object'`` (default; forward and reverse hold the complete consistency group of
                each touched object, directly applicable by ``update_from_*``) or ``'changed_only'`` (strict
                delta).
            variant: as in :meth:`to_ssh`.
            **metadata: as in :meth:`to_ssh`; a value may also be a dict keyed by profile, e.g.
                ``model_id={'SSH': 'urn:uuid:...'}``.

        Returns:
            the XML document if ``file`` is ``None``, else ``None``. With no recorded change at all the result is
            a valid, empty difference model document.

        Raises:
            pypowsybl.PyPowsyblError: an unsupported change, several touched profiles, or an unknown metadata name.
            ValueError: a bad ``unsupported`` or ``granularity`` value.
        """
        self._check_network()
        options = self._flatten_options(unsupported, granularity, metadata, variant)
        return self._write(file, _pp.export_network_events_to_cgmes_diff(self._handle, profile or '', options))

    def to_cgmes_diffs(self, target: Union[str, PathLike, IO[bytes], None] = None, base_name: str = 'update',
                       unsupported: str = 'raise', granularity: str = 'full_object', *,
                       variant: Optional[str] = None,
                       **metadata: ProfileValue) -> Optional[Dict[str, str]]:
        """
        Export the recorded changes as one difference model document per touched profile.

        Args:
            target: ``None`` to get a ``dict`` profile -> XML (insertion order ``EQ``, ``SSH``; empty when nothing
                changed); a ``str``/``PathLike`` ending in ``.zip`` for one zip archive holding the entries
                ``<base_name>_<PROFILE>_DIFF.xml``; another ``str``/``PathLike`` for a directory (created if
                missing) holding those files; a binary file object (e.g. ``io.BytesIO``) for the zip written into
                it, leaving its position at the end, so that the caller does ``seek(0)`` before passing it to
                :meth:`Network.update_from_binary_buffer`.
            base_name: the common prefix of the written file names.
            unsupported: as in :meth:`to_ssh`.
            granularity: as in :meth:`to_cgmes_diff`.
            variant: as in :meth:`to_ssh`.
            **metadata: as in :meth:`to_cgmes_diff`.

        Returns:
            the documents keyed by profile if ``target`` is ``None``, else ``None``.
        """
        self._check_network()
        options = self._flatten_options(unsupported, granularity, metadata, variant)
        # dict(): the C extension already returns a dict, but this is what tells a static checker so
        documents: Dict[str, str] = dict(_pp.export_network_events_to_cgmes_diffs(self._handle, options))
        ordered = {profile: documents[profile] for profile in _PROFILES if profile in documents}
        if target is None:
            return ordered
        self._write_documents(target, base_name, ordered)
        return None

    def to_rdf_updates(self, db: 'RdfDatabase', scenario: str, version: Optional[str] = None,
                       timestep: 'Timestep' = None, *, variant: Optional[str] = None, per_variant: bool = False,
                       unsupported: str = 'raise', granularity: str = 'full_object', clear: bool = True,
                       **metadata: ProfileValue) -> Union[List[str], DataFrame]:
        """
        Store the recorded changes straight into an RDF database, as a new snapshot of a scenario.

        One difference model per touched profile is written and tied together into the snapshot
        ``(scenario, timestep, version)``. The changes are never re-parsed: they travel as triples, which is what
        makes this cheaper than writing files and importing them again.

        ``scenario`` names the **base scenario** the difference is made against, and the network this recorder
        watches must be *in* that scenario - loaded from it, or updated to it. A difference never crosses
        scenarios.

        ``timestep`` decides what the new snapshot is. Left ``None``, or equal to the timestep the network is at,
        it is a new **version** of that timestep, written on top of its head - so the network has to *be* at the
        head, otherwise the database refuses the write and says which version superseded it. A timestep the
        scenario does not hold yet becomes a new **timestep root**, and then the network has to be at the head of
        the base timestep.

        On a network that is in **variant mode** this write is a variant operation even without ``variant=``: it
        writes the working variant's history, and a change IIDM does not store per variant - an impedance, a
        limit, a property - belongs to every variant and is therefore an unsupported change, raised or skipped
        according to ``unsupported``.

        Args:
            db: an open connection, see :func:`pypowsybl.network.connect_rdf_db`
            scenario: the base scenario the difference is made against, for instance ``"2021-02-09"``; required
            version: the version label of the new snapshot, for instance ``'1.1'``; ``None`` takes the next label
                of that timestep's chain
            timestep: the moment the new snapshot describes, see :data:`pypowsybl.network.Timestep`; ``None`` is
                the base timestep of the scenario
            variant: write the changes recorded on **one variant** as the successor of *that variant's* snapshot.
                The timestep is then the variant's own and must not be given; ``version`` still names the label
                the new snapshot gets. A change IIDM does not store per variant belongs to every variant and is
                therefore unsupported when the network holds more than one. Naming a variant here switches the
                network into **variant mode**, exactly as
                :meth:`Network.update_from_rdf_db` with ``variant=`` does, and that is sticky: every later
                operation of this module on that network is a variant operation
            per_variant: write the changes of **every** variant they were recorded on, each as the successor of
                its own snapshot, and answer with a dataframe instead of a list. ``timestep`` and ``variant`` must
                then be ``None``. Everything that can refuse happens before anything is written, so an unsupported
                change under ``unsupported='raise'`` leaves the database untouched. It switches the network into
                variant mode like ``variant=``
            unsupported: as in :meth:`to_ssh`
            granularity: as in :meth:`to_cgmes_diff`
            clear: drop the recorded events after a successful write (the default), so that the next
                ``to_rdf_updates`` chains cleanly on top of this one. With ``clear=False`` the events stay, and a
                second write would store the very same changes again as another version
            **metadata: ``description``, ``modeling_authority_set``, ``model_id`` (plain or a dict per profile) and
                ``created``. ``version``, ``scenario_time``, ``supersedes`` and ``depends_on`` are **rejected**:
                the snapshot decides them

        Returns:
            the ids of the stored models, in the order ``EQ``, ``SSH``. With ``per_variant=True`` a dataframe
            indexed by ``variant`` with the columns ``snapshot``, ``version``, ``timestep``, ``models``
            (``;``-joined ids), ``exported_events`` (int) and ``rejected``

        Raises:
            pypowsybl.PyPowsyblError: nothing was recorded, the network belongs to another scenario, the network is
                not at the head the write applies on, the address is already taken, a variant stands for no
                snapshot, or a metadata name the database decides was given
            ValueError: a bad ``unsupported`` or ``granularity`` value, or ``per_variant`` combined with a
                ``timestep`` or a ``variant``

        Examples:
            .. code-block:: python

                with pp.network.connect('memory:demo') as db:
                    network = pp.network.from_rdf_db(db, '2021-02-09', '1.0')
                    with network.event_recorder() as recorder:
                        network.update_loads(id='LOAD', p0=420.0)
                        ids = recorder.to_rdf_updates(db, '2021-02-09', '1.1', '8:30')
        """
        self._check_network()
        _check_scenario(scenario)
        if per_variant and (timestep is not None or variant is not None):
            raise ValueError('per_variant=True writes every variant the changes were recorded on, each at its own '
                             'timestep; it takes neither a timestep nor a variant')
        if per_variant:
            options = self._flatten_options(unsupported, granularity, metadata, None)
            rows = create_data_frame_from_series_array(_pp.export_network_events_to_rdf_db_per_variant(
                self._handle, db._check_open(), scenario,  # pylint: disable=protected-access
                _version_to_str(version), options))
            if clear:
                self.clear()
            return rows
        if variant is not None and timestep is not None:
            raise ValueError('a variant export writes the successor of the snapshot that variant stands for, so '
                             'its timestep is that variant\'s own; drop the timestep')
        options = self._flatten_options(unsupported, granularity, metadata, variant)
        ids = _pp.export_network_events_to_rdf_db(
            self._handle, db._check_open(), scenario,  # pylint: disable=protected-access
            _version_to_str(version), _timestep_to_str(timestep), options)
        if clear:
            self.clear()
        return ids

    def touched_profiles(self) -> List[str]:
        """
        The CGMES profiles the recorded changes describe, among ``'EQ'`` and ``'SSH'``.

        Unsupported changes are ignored, so this never raises on a recording holding changes that cannot be
        exported.
        """
        return list(self.to_cgmes_diffs(unsupported='ignore') or {})

    # helpers

    @staticmethod
    def _write_documents(target: Union[str, PathLike, IO[bytes]], base_name: str,
                         documents: Dict[str, str]) -> None:
        """Write one file per profile, into a zip archive, a binary stream, or a directory."""
        if isinstance(target, (str, PathLike)):
            path = path_to_str(target)
            if path.endswith('.zip'):
                with open(path, 'wb') as stream:
                    NetworkEventRecorder._write_zip(stream, base_name, documents)
                return
            try:
                os.makedirs(path, exist_ok=True)
            except FileExistsError as e:
                raise NotADirectoryError(f"Cannot write the difference models into '{path}': it exists and is not"
                                         ' a directory. Give a directory, a path ending in .zip, or a binary file'
                                         ' object') from e
            for profile, xml in documents.items():
                file_name = os.path.join(path, f'{base_name}_{profile}_DIFF.xml')
                with open(file_name, 'w', encoding='utf-8', newline='') as text_file:
                    text_file.write(xml)
            return
        if not hasattr(target, 'write'):
            raise TypeError(f'Cannot write difference models to {type(target).__name__}: expected a path, a binary'
                            ' file object, or None')
        NetworkEventRecorder._write_zip(target, base_name, documents)

    @staticmethod
    def _write_zip(stream: IO[bytes], base_name: str, documents: Dict[str, str]) -> None:
        # ZIP_STORED: the archive only exists to cross the in-memory boundary, compressing it costs time
        with zipfile.ZipFile(stream, 'w', zipfile.ZIP_STORED) as archive:
            for profile, xml in documents.items():
                archive.writestr(f'{base_name}_{profile}_DIFF.xml', xml.encode('utf-8'))

    @staticmethod
    def _write(file: FileTarget, xml: str) -> Optional[str]:
        """Write one document where the caller asked, or return it when the caller asked for a string."""
        if file is None:
            return xml
        if isinstance(file, (str, PathLike)):
            with open(path_to_str(file), 'w', encoding='utf-8', newline='') as text_file:
                text_file.write(xml)
            return None
        if isinstance(file, io.TextIOBase):
            NetworkEventRecorder._check_text_encoding(file)
            file.write(xml)
            return None
        if isinstance(file, (io.RawIOBase, io.BufferedIOBase)):
            file.write(xml.encode('utf-8'))
            return None
        if not hasattr(file, 'write'):
            raise TypeError(f'Cannot write to {type(file).__name__}: expected a path, a file object, or None')
        # an object that is neither a path nor one of the standard io classes: try text, fall back to bytes
        writer: Any = file
        try:
            writer.write(xml)
        except TypeError:
            writer.write(xml.encode('utf-8'))
        return None

    @staticmethod
    def _check_text_encoding(file: IO[str]) -> None:
        """
        Warn when a text stream does not encode as UTF-8.

        The document declares UTF-8 in its XML declaration, so writing it into, say, a cp1252 stream produces a
        file whose declaration lies and which the receiver cannot read. This is a warning and not an error because
        the tender's ``open(path, 'w')`` keeps working wherever the platform default already is UTF-8.
        """
        encoding = getattr(file, 'encoding', None)
        if not encoding:
            return
        try:
            normalised = codecs.lookup(encoding).name
        except LookupError:  # an exotic stream naming an encoding Python does not know: nothing to check against
            return
        if normalised != 'utf-8':
            warnings.warn(f"Writing a UTF-8 declared CGMES document into a text stream encoded as '{encoding}'; "
                          "reopen the file with encoding='utf-8', or pass the path itself, or a binary stream",
                          stacklevel=4)

    @staticmethod
    def _flatten_options(unsupported: str, granularity: Optional[str], metadata: Dict[str, ProfileValue],
                         variant: Optional[str] = None) -> Dict[str, str]:
        """
        Flatten the Python arguments into the string map the native layer takes.

        Unknown metadata names are *not* rejected here: Java owns the list of supported names and raises the error
        that names them, so that there is a single source of truth. ``variant`` travels in the same map and is
        taken out of it again on the Java side, because it steers the export rather than the document.
        """
        if unsupported not in _UNSUPPORTED_VALUES:
            raise ValueError(f"Unknown value '{unsupported}' of 'unsupported', expected one of "
                             f'{list(_UNSUPPORTED_VALUES)}')
        options: Dict[str, str] = {'unsupported': unsupported}
        if variant is not None:
            if not isinstance(variant, str) or not variant.strip():
                raise ValueError(f'A variant identifier must be a non-blank string, got {variant!r}')
            options['variant'] = variant
        if granularity is not None:
            if granularity not in _GRANULARITY_VALUES:
                raise ValueError(f"Unknown value '{granularity}' of 'granularity', expected one of "
                                 f'{list(_GRANULARITY_VALUES)}')
            options['granularity'] = granularity
        for name, value in metadata.items():
            if value is None:
                continue
            if isinstance(value, dict):
                for profile, profile_value in value.items():
                    if profile_value is None:
                        continue
                    if not isinstance(profile, str):
                        raise TypeError(f"Profile key {profile!r} of '{name}' is not a string; a per-profile value"
                                        f' is keyed by a profile name, one of {list(_PROFILES)}')
                    options[f'{profile.lower()}.{name}'] = NetworkEventRecorder._flatten_value(name, profile_value)
            else:
                options[name] = NetworkEventRecorder._flatten_value(name, value)
        return options

    @staticmethod
    def _flatten_value(name: str, value: Any) -> str:
        """One metadata value as a string: a time as ISO-8601, a sequence of identifiers comma separated."""
        if isinstance(value, datetime.datetime):
            if value.tzinfo is None:
                value = value.replace(tzinfo=datetime.timezone.utc)
            return value.isoformat()
        if isinstance(value, str):
            return value
        if isinstance(value, bool):
            return str(value).lower()
        if isinstance(value, int):
            return str(value)
        if isinstance(value, Sequence):
            ids = [str(item) for item in value]
            for identifier in ids:
                if ',' in identifier:
                    raise ValueError(f"Value '{identifier}' of '{name}' contains a comma, which separates the "
                                     'identifiers of a list')
            return ','.join(ids)
        return str(value)
