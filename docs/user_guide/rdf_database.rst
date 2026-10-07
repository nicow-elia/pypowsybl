Loading CGMES through an RDF database
=====================================

.. currentmodule:: pypowsybl.network

Importing a CGMES model is two things in one: reading the instance files into an RDF triple store, and running the
CGMES queries over that store to build the network. The first half is the expensive one, and it is repeated every
single time the same grid model is loaded.

This page is about splitting the two. :meth:`RdfDatabase.load_cgmes` parses the files **once** and writes them into
a SPARQL 1.1 graph database as named graphs; :func:`from_rdf_db` builds a network from those graphs, as often as you
like, without ever looking at a file again. The network it produces is the network the files would have produced.

Scenarios
---------

A database is expected to hold many grid models side by side - typically one per day. Every call therefore names a
**scenario**: a free-form, non-blank string identifying the base model the graphs belong to, such as
``'2021-02-09'`` or ``'DACF-2021-02-09'``. It is always the argument right after the database or the file, it is
required, and it is never guessed. Two scenarios in one database never see each other's statements.

Getting a database
------------------

The URL decides the backend:

``memory:<name>``
    an in-process store. No server, nothing to install; it lives as long as a connection to it is open. Good for
    trying things out, for tests, and for scripts that load the same model several times in one run.

``http://host:3030/ds``
    an `Apache Jena Fuseki <https://jena.apache.org/documentation/fuseki2/>`_ dataset.

``http://host:8080/rdf4j-server/repositories/<id>``
    an rdf4j-server or GraphDB repository.

For a real server, the quickest start is a container:

.. code-block:: bash

    docker run --rm -p 3030:3030 -e ADMIN_PASSWORD=admin \
        -e ENABLE_DATA_WRITE=true -e ENABLE_UPDATE=true -e ENABLE_UPLOAD=true \
        secoresearch/fuseki

and then ``connect_rdf_db('http://localhost:3030/ds/sparql')``. Two details of that image are worth knowing: its
dataset is read-only unless the three ``ENABLE_`` variables are set, and it publishes its query endpoint as
``/ds/sparql`` rather than ``/ds/query``, which is why the URL names it. The admin password guards the
administration endpoints, not the data, so no credentials are needed for the calls below - pass them with
``user=`` / ``password=`` if your own server asks for them.

Everything below runs on the in-process backend, so it works without any server.

Uploading a model
-----------------

:func:`connect_rdf_db`, also exported as ``connect``, opens a connection. It is a context manager.

.. testcode::

    import pypowsybl as pp

    with pp.network.connect('memory:guide') as db:
        graphs = db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09')
        print(len(graphs))
        print(sorted(db.scenarios().index))

.. testoutput::

    5
    ['2021-02-09']

One graph per instance file. :meth:`RdfDatabase.graphs` is the catalogue of a scenario - what is stored, which
CGMES subset each graph carries, and the IRI it has in the database:

.. testcode::

    with pp.network.connect('memory:guide2') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09')
        print(db.graphs('2021-02-09')['subset'].sort_values().tolist())

.. testoutput::

    ['EQ', 'EQ_BD', 'SSH', 'SV', 'TP']

Uploading the same file name again **replaces** that graph rather than adding to it, so re-running an upload is
idempotent. :meth:`RdfDatabase.clear` drops every graph of one scenario and leaves the others alone.

Building networks from it
-------------------------

.. testcode::

    with pp.network.connect('memory:guide3') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09')

        network = pp.network.from_rdf_db(db, '2021-02-09')
        from_files = pp.network.load(DATA_DIR / 'CGMES_Full.zip')

        print(len(network.get_loads()) == len(from_files.get_loads()))
        print(sorted(network.get_generators().index) == sorted(from_files.get_generators().index))

.. testoutput::

    True
    True

Pass the same CGMES import ``parameters`` to :meth:`RdfDatabase.load_cgmes` and to :func:`from_rdf_db`: the first
decides how identifiers are read into the database, the second how they are read out of it.

Two days side by side
---------------------

.. testcode::

    with pp.network.connect('memory:guide4') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09')
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-10')
        print(sorted(db.scenarios().index))

        monday = pp.network.from_rdf_db(db, '2021-02-09')
        tuesday = pp.network.from_rdf_db(db, '2021-02-10')
        print(len(monday.get_loads()), len(tuesday.get_loads()))

.. testoutput::

    ['2021-02-09', '2021-02-10']
    6 6

Updating a network that is already loaded
-----------------------------------------

:meth:`Network.update_from_rdf_db` is the database equivalent of :meth:`Network.update_from_file`: it applies the
steady state kept in a scenario to a network that is already in memory, instead of rebuilding it. By default it
reads the steady-state pair (``SSH`` and ``SV``); ``profiles`` narrows that down.

.. testcode::

    import io

    sender = pp.network.load(DATA_DIR / 'CGMES_Full.zip')
    load_id = sorted(sender.get_loads().index)[0]
    sender.update_loads(id=load_id, p0=42.0)
    ssh = io.BytesIO(sender.save_to_binary_buffer('CGMES', {'iidm.export.cgmes.profiles': 'SSH'}).getbuffer())

    receiver = pp.network.load(DATA_DIR / 'CGMES_Full.zip')
    with pp.network.connect('memory:guide5') as db:
        db.load_cgmes_from_binary_buffers([ssh], '2021-02-09')
        print(receiver.update_from_rdf_db(db, '2021-02-09', profiles=['SSH']))
        print(receiver.get_loads().loc[load_id]['p0'])

.. testoutput::

    update
    42.0

The return value says how the network was brought up to date. On an un-versioned scenario, with nothing
addressed, this is the profile replacement and it always answers ``'update'``. Inside a versioned scenario there
are three routes; see `Versions and timestamps`_ below.

Query modes and performance
---------------------------

``query_mode='local'`` (the default) downloads the graphs of the scenario - in parallel, as N-Triples, with a Graph
Store Protocol ``GET`` per graph - and runs the CGMES queries on a local copy. ``query_mode='remote'`` sends the
some eighty catalog queries to the server instead, restricted to the scenario's graphs through the SPARQL protocol
dataset parameters. Remote mode keeps the model on the server, which matters for a thin client, but it costs round
trips and is measurably slower; local is the default for that reason.

The knobs worth knowing:

* ``fetch_parallelism`` / ``upload_parallelism`` (4 each): how many graphs travel at once;
* ``gzip``: compress the transfer. On by default unless the server is on the loopback interface, where compressing
  costs more than it saves;
* ``cache=True``: keep parsed graphs in memory between loads in the same process. It is **on** by default: the
  graphs of a versioned scenario are written once and never overwritten, so a cached graph stays valid and loading
  a second snapshot of the same day only transfers the differences between them.


.. _Versions and timestamps:

Versions and timestamps
-----------------------

A scenario is one base grid model - one day. Inside it, a **snapshot** is a consistent grid state with a four-part
address, written in this order everywhere:

.. list-table::
   :header-rows: 1
   :widths: 20 30 50

   * - argument
     - type
     - ``None`` means
   * - ``scenario``
     - ``str``, required
     - not allowed: the scenario is never guessed
   * - ``version``
     - ``str``, a name the scenario's version registry ranks (``'1'``, ``'DA'``)
     - the newest version (a read), the next registered one (a write)
   * - ``timestamp``
     - timezone-aware :class:`datetime.datetime`
     - the base timestamp of the tree
   * - ``modelling_authority``
     - ``str``, the files' ``md:Model.modelingAuthoritySet``
     - the only authority of the scenario, for a read and a write alike (a read is refused, with the list, when
       it holds several); the first root of a scenario - and a write into a scenario of several - takes it from
       the equipment and steady state hypothesis headers of the files when they agree, and is refused otherwise

A **timestamp** is an instant. Pass an aware datetime - ``datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc)``, or
the same moment in any other zone; a naive datetime names no instant and raises :class:`TypeError`. The dataframes
answer in UTC (``datetime64[ns, UTC]``); showing a moment in a local zone is the caller's choice.

A **version** is a name - ``'1'``, ``'2'``, or ``'DA'``, ``'ID'``, ``'RT'`` for day-ahead, intraday and real time -
and the scenario's **version registry** gives every name a *rank* (see `The version registry`_ below). Versions are
compared by rank, never by name: the versions of one timestamp only grow upwards in rank, and a **read at a name
takes the highest ranking version at or below it** that the timestamp holds - ``'RT'`` at a moment that only went as
far as ``'ID'`` is that ``'ID'``. ``exact=True`` on :func:`from_rdf_db` and :meth:`Network.update_from_rdf_db` takes
that version or nothing. A write without a version takes the lowest registered name ranking above the head; a
scenario whose registry is permissive (the default, see below) appends an unregistered name on top, so writing
``'1'``, ``'2'``, ``'3'`` in that order just works. An int is refused (``TypeError: version is a name``). The
``md:Model.version`` of the CGMES headers is unrelated to it.

A **modelling authority** is a TSO's tree. Every authority of a scenario has a tree of its own - its root, its
timestamps, its versions - and all of them share the scenario's boundary. A scenario of a single TSO, which is the
usual case, never needs to name it: ``None`` resolves to the one there is. :meth:`RdfDatabase.modelling_authorities`
lists them, and :meth:`RdfDatabase.assembly` answers which snapshot every authority has at one moment - one row
per authority, an empty one where an authority has no snapshot then - which is what a common grid model is
assembled from; ``authorities=[...]`` loads it as one network (see `Loading a CGM at once`_).

``profiles`` is **not** part of the address. It is the projection of a call - which profiles it loads, updates,
stores or compares - and ``None`` is each call's default. A profile is a name: the nine the CGMES conversion reads
(:data:`PROFILES`: ``'EQ'``, ``'SSH'``, ``'TP'``, ``'SV'``, ...) or a custom one of the same shape,
``[A-Z][A-Z0-9_]*`` (see `Custom profiles`_).

A scenario becomes versioned when its instance files are uploaded with an address: that upload is the **root** of
their authority's tree, and the ``md:Model.scenarioTime`` of the steady state file becomes its **base timestamp**.
Everything after that is a difference, and it can be written in two ways:

* from a **recorder** - :meth:`NetworkEventRecorder.to_rdf_updates` stores what a network changed, which is how
  one client tells another what it did;
* from **files** - :meth:`RdfDatabase.load_cgmes` with an address stores a set of CGMES instance files as a
  difference against the state they derive from, which is how a TSO's day of ninety-six timestamps gets in.

Both end up in the same chain and are read back the same way. A new version of the same timestamp grows the chain
of that moment; an address whose timestamp the tree does not hold yet starts a new timestamp, hanging off a
**pin** - another snapshot of the tree (see `Pins and rollovers`_).

How an address is written (``t`` an aware datetime):

===========================================  ===============================================================
``from_rdf_db(db, s)``                       the newest version of the scenario's base timestamp
``from_rdf_db(db, s, '2')``                  version ``'2'`` of the base timestamp, or the highest below it
``from_rdf_db(db, s, None, t)``              the newest version of the timestamp ``t``
``from_rdf_db(db, s, 'ID', t)``              the highest version at or below ``'ID'`` of the timestamp ``t``
``from_rdf_db(db, s, 'ID', t, exact=True)``  exactly that study state, or an error
``from_rdf_db(db, s, 'ID', t, mas)``         the same, in the tree of the modelling authority ``mas``
===========================================  ===============================================================

Storing changes from a recorder
-------------------------------

One snapshot is stored under one modelling authority; the files it carries may come from several. A write that
names the authority takes it whatever the files state. One that leaves it open writes into the scenario's tree
when the scenario holds exactly one, as a read does - a further set of files, a recorder's changes and a
checkpoint alike; files whose equipment and steady state hypothesis agree on *another* authority are refused
there ("... state modelling authority X but the scenario's only tree is Y: pass Y in the address to store them
under it, or X to open a second tree"). Where there is no single tree - the first root of a scenario, or a scenario of several - it
takes the authority the equipment and the steady state hypothesis files agree on, and is refused otherwise,
naming the authority of every profile. ``data/CGMES_Full.zip`` is such a case - its EQ and TP say
``powsybl.org``, its SSH Elia, its SV TenneT - so the examples below name Elia when they store a root.
Everything after that may leave it open: the scenario holds one tree.

.. testcode::

    from datetime import datetime, timezone

    elia = 'http://elia.be/CGMES'
    with pp.network.connect('memory:demo') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09', '1', modelling_authority=elia)
        network = pp.network.from_rdf_db(db, '2021-02-09', '1')
        load_id = sorted(network.get_loads().index)[0]

        with network.event_recorder() as recorder:
            network.update_loads(id=load_id, p0=42.0)
            stored = recorder.to_rdf_updates(db, '2021-02-09', '1', datetime(2021, 2, 9, 20, 30, tzinfo=timezone.utc))

        snapshots = db.snapshots('2021-02-09')
        print(len(stored) > 0)
        print(sorted(snapshots['version']))
        print(sorted(str(t) for t in snapshots['timestamp']))
        print(sorted(snapshots['kind']))
        print(db.modelling_authorities('2021-02-09'))

.. testoutput::

    True
    ['1', '1']
    ['2021-02-09 19:30:00+00:00', '2021-02-09 20:30:00+00:00']
    ['diff', 'full']
    ['http://elia.be/CGMES']

The version registry
--------------------

Every scenario has a **version registry**: the version names it knows, each with a *rank*. Ranks are sparse
(10, 20, 30, ...) so that a name can later be put between two others, and they are what versions are compared by.
:meth:`RdfDatabase.registry` returns a :class:`VersionRegistry` that reads and edits it:

* :meth:`VersionRegistry.dataframe` - one row per name, lowest rank first: ``rank`` and ``transient``;
  :attr:`VersionRegistry.names`, :meth:`VersionRegistry.rank` and :attr:`VersionRegistry.permissive` read it too;
* :meth:`VersionRegistry.create` - the registry of a scenario that has none yet, **strict** by default: a write under
  a name it does not hold is refused ("version 'X' is not registered in scenario 'S' (registry: [...]); register it
  or write into a permissive scenario");
* :meth:`VersionRegistry.add` registers a name on top, :meth:`VersionRegistry.insert` between a name and its
  successor (at the midpoint of their ranks; refused when no integer is left - rerank first);
* :meth:`VersionRegistry.rerank` gives names new ranks, refused when a stored version would end up at or below the
  version it was written on;
* :meth:`VersionRegistry.rename` renames a name no snapshot carries yet (a snapshot's IRI carries its version name,
  so a used name keeps it; rerank to reorder);
* :meth:`VersionRegistry.mark_transient` and :meth:`VersionRegistry.delete`: deleting a *transient* name drops every
  snapshot that carries it - each must be one nothing was built on - which is how scratch studies are cleaned up;
  any other name a snapshot carries is refused.

A scenario whose first root is written **without** a registry gets a **permissive** one holding the root's version
name at rank 10: a write under a name it does not hold appends that name on top. That is why the examples of this
page never create one. Every edit is guarded by the registry's revision in the database, so an edit that lost a race
against another writer is refused and changes nothing; a write whose registry changed in the meantime is retried by
the library.

.. testcode::

    with pp.network.connect('memory:registry') as db:
        registry = db.registry('2021-02-09')
        registry.create(['DA', 'ID', 'RT'])
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09', 'DA', modelling_authority=elia)
        network = pp.network.from_rdf_db(db, '2021-02-09', 'DA')
        with network.event_recorder() as recorder:
            network.update_loads(id=sorted(network.get_loads().index)[0], p0=42.0)
            recorder.to_rdf_updates(db, '2021-02-09', 'ID')
        print(registry.dataframe()['rank'].to_dict())
        print(pp.network.from_rdf_db(db, '2021-02-09', 'RT').rdf_db_identity()['version'])
        print(db.versions('2021-02-09')[['version', 'rank']].values.tolist())

.. testoutput::

    {'DA': 10, 'ID': 20, 'RT': 30}
    ID
    [['DA', 10], ['ID', 20]]

Ingesting a day from files
--------------------------

A schedule is not recorded on a network: it arrives as one set of instance files per timestamp. Each set goes in
with one call, naming the moment it describes::

    from datetime import datetime, timedelta, timezone

    base = datetime(2021, 2, 9, 20, 0, tzinfo=timezone.utc)
    with pp.network.connect('http://localhost:3030/ds') as db:
        db.load_cgmes('day/base.zip', '2021-02-09', '1')                       # the root
        for i in range(3):
            moment = base + timedelta(minutes=15 * i)
            db.load_cgmes(f'day/{moment:%H%M}.zip', '2021-02-09', None, moment)

The state each set derives from - its pin, by default the latest **rollover** at or before the timestamp, the root
until another one is flagged - is materialised, the files are compared against it profile by profile - the
equipment model and the steady state hypothesis unless ``profiles`` names others - and the difference is stored, so
what the database holds is the base plus what each timestamp changed, not ninety-six copies of the grid. The
boundary has to be the one the scenario was rooted with; a changed boundary is refused rather than silently mixed in.

Custom profiles
---------------

An application may keep data of its own next to the grid model - operational settings, a study's parameters - as
one more instance file of the upload. Its profile is read off its name: a file the conversion does not recognise
is named ``<base>_<PROFILE>.xml`` (``Grid_OP.xml`` holds the profile ``OP``; a version-like last token such as
``V2`` is refused) and carries an ``md:FullModel`` header and the RDF and a CIM namespace declaration. Such a profile
is **stored whole**, never as a difference, and the CGMES conversion **never reads it**: the network loaded from the
snapshot is the network of the standard files alone. A root stores every custom profile its files carry; a further
timestamp stores the custom files ``profiles`` names (a changed file becomes a new whole graph of that snapshot)
and inherits the others from the state it derives from.

:meth:`RdfDatabase.profiles` names, for a snapshot, every profile that is one whole graph - every custom one, and a
standard one while it is still its instance file - with that graph; :meth:`RdfDatabase.fetch_profile` reads one
graph as a dataframe of statements (``subject``, ``predicate``, ``object``, ``is_iri``)::

    db.load_cgmes('day/base_with_OP.zip', '2021-02-09', '1', modelling_authority=elia)
    graphs = db.profiles('2021-02-09')                  # {'EQ': ..., 'SSH': ..., 'OP': ...}
    settings = db.fetch_profile('2021-02-09', graphs['OP'])
    db.load_cgmes('day/2000_with_OP.zip', '2021-02-09', None, t2000, profiles=['EQ', 'SSH', 'OP'])

A recorder writes network changes, which only touch the nine; naming a custom profile in
:meth:`NetworkEventRecorder.to_rdf_updates` is refused.

Pins and rollovers
------------------

Every timestamp but the base one hangs off a **pin**: a snapshot of another timestamp of the same tree, which is
the state the timestamp is the difference against. :meth:`RdfDatabase.timestamps` shows it in the ``pin`` column.
The defaults:

* a timestamp **ingested from files** hangs off the latest **rollover** at or before it. The root is a rollover;
  :meth:`RdfDatabase.rollover` flags another snapshot (and checkpoints it at once), so that the timestamps after it
  are stored as their difference to *it* - roll over where the equipment drifted, and the day stays small;
* a timestamp written by a **recorder** hangs off the snapshot the network is at, when that one states what the
  changes supersede, else off the deepest snapshot that does.

``pin=`` on :meth:`RdfDatabase.load_cgmes` and :meth:`NetworkEventRecorder.to_rdf_updates` names another one - a
timestamp (its newest version) or a ``(version, timestamp, modelling_authority)`` triple. A pin is chosen when a
timestamp is created: naming one for a timestamp that exists is refused, its versions grow on its head::

    rolled = db.rollover('2021-02-09', None, t1200)                     # the afternoon diffs against 12:00
    db.load_cgmes('day/1215.zip', '2021-02-09', None, t1215)            # pinned to 12:00
    db.load_cgmes('day/1230.zip', '2021-02-09', None, t1230, pin=BASE)  # pinned to the root, explicitly
    db.timestamps('2021-02-09')['pin']

:meth:`RdfDatabase.drop_timestamp` drops one timestamp with every version of it and the graphs only it uses; the
base timestamp and a timestamp another one is pinned to are refused (the refusal names them - there is no cascade).

:meth:`RdfDatabase.changes_between` answers what leads from one snapshot of a tree to another, as one composed
difference: one row per statement with ``profile``, ``subject``, ``property``, ``value`` and ``side`` - ``forward``
for what holds at the second snapshot, ``reverse`` for what held at the first::

    changes = db.changes_between('2021-02-09', t1200, t1215)
    changes[changes['side'] == 'forward']

Loading a CGM at once
---------------------

:func:`from_rdf_db` with ``authorities=[...]`` loads the trees of several modelling authorities at one moment as
**one** network - the common grid model, with the tie lines a file import of the assembled model gives::

    cgm = pp.network.from_rdf_db(db, '2021-02-09', 'ID', t, authorities=[elia, tennet])

Each authority is resolved at the moment and version as an ordinary read is (the highest ranking version at or
below it, exactly it with ``exact=True``); one that holds no snapshot there is refused - a CGM with a missing IGM is
not a CGM. The boundary is read once, every tree is brought onto one set of identifiers, and where two authorities
state the same property of the same object the **first one listed wins** (``composition='first-wins'``, the only
rule). The network has no single snapshot: :meth:`Network.rdf_db_identity` answers ``composition`` (the authorities
in precedence order) and ``owned``; its id is the one of the equipment models.

Changes recorded on it are written back by the ordinary :meth:`NetworkEventRecorder.to_rdf_updates`: each change goes
into the tree of the authority that owns the object, as a new version of that tree. Only the trees named in
``owned=`` are written (``None``: the first authority); a change of an object of another authority is refused before
anything is written (*"the change on <mRID> belongs to modelling authority 'X', which this composed network does not
own (owned: [...]); nothing was written"*). A composed network is read-only for :meth:`Network.update_from_rdf_db`
and the variant operations: load it again for another moment.

Archiving the states before a cutoff
------------------------------------

A long-lived database moves old days out. :meth:`RdfDatabase.set_archive_cutoff` records a moment and the place the
earlier states went; from then on a read of a snapshot whose timestamp is before the cutoff - a load, an update, an
assembly, a bulk load, ``changes_between`` - is refused with the message *"snapshot (...) is in the archive at
<location>: states before <cutoff> are not served by this store"*. The listings still show those snapshots. A root is
not exempt, so set the cutoff at a rollover: the timestamps after it start from its full state. A network standing
at an archived snapshot is reloaded (``'full'``) instead of walked from it::

    db.rollover('2021-02-09', None, t1200)
    db.set_archive_cutoff('2021-02-09', t1200, 's3://archive/2021-02-09')
    db.archive_cutoff('2021-02-09')         # (t1200, 's3://archive/2021-02-09')
    db.clear_archive_cutoff('2021-02-09')

:meth:`RdfDatabase.scenarios` shows the cutoff of every scenario in ``archive_cutoff`` and ``archive_location``.

Loading and updating
--------------------

:func:`from_rdf_db` builds a network at an address; :meth:`Network.update_from_rdf_db` brings a network that is
already in memory to one. One query decides how, and the return value names the route:

* ``'noop'`` - the network is already there, nothing was read and nothing was changed;
* ``'diff'`` - the stored differences on the way to the target are fetched in one request and applied in place,
  forwards, backwards, or up one branch of the chain and down another;
* ``'full'`` - the target cannot be reached that way and the network is rebuilt from the database. A target in
  **another scenario** or of **another modelling authority** is always a full reload, decided without a query,
  because differences never cross them.

.. testcode::

    with pp.network.connect('memory:demo2') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09', '1', modelling_authority=elia)

        sender = pp.network.from_rdf_db(db, '2021-02-09', '1')
        with sender.event_recorder() as recorder:
            sender.update_loads(id=sorted(sender.get_loads().index)[0], p0=42.0)
            recorder.to_rdf_updates(db, '2021-02-09')   # version '2': the next number the registry lacks

        receiver = pp.network.from_rdf_db(db, '2021-02-09', '1')
        print(receiver.update_from_rdf_db(db, '2021-02-09', '2'))
        print(receiver.update_from_rdf_db(db, '2021-02-09', '2'))
        print(receiver.update_from_rdf_db(db, '2021-02-09', '1'))
        print(receiver.rdf_db_identity()['scenario'])

.. testoutput::

    diff
    noop
    diff
    2021-02-09

A target in another scenario is the ``'full'`` case: ``receiver.update_from_rdf_db(db, '2021-02-10', '1')``
reloads the network from that day, and ``receiver.rdf_db_identity()['scenario']`` then says ``'2021-02-10'``.

What happens on a full reload
-----------------------------

The Python object stays valid and keeps its ``id``, its per-unit setting and its threading mode, but it now wraps a
*new* Java network. That has consequences worth knowing:

* variants are **not** carried over - the reloaded network has only the initial variant, and the working variant
  resets;
* :class:`Network` objects obtained from :meth:`Network.get_sub_network` before the reload still point at the old
  Java network;
* :class:`NetworkEventRecorder`\ s created before the reload are bound to the old Java network. Every export of
  such a recorder raises :class:`pypowsybl.PyPowsyblError` telling you to create a new one;
  :meth:`NetworkEventRecorder.stop` still works, so nothing leaks;
* dataframes taken before the reload are plain data and are unaffected.

Timestamps and versions as network variants
--------------------------------------------

A day walked by one network keeps no history: after the last timestamp the first one is gone. A day loaded as one
network per timestamp keeps all of them, but converts the same equipment once per timestamp. The third way is one
network with one **variant per timestamp**.

:func:`from_rdf_db` with ``timestamps=[...]`` converts the first requested snapshot, clones it once per further
snapshot and applies the stored differences on the clones. That is one chain query, one statement fetch and one
conversion whatever the number of timestamps. The variants are named after the ISO instant of their timestamp
(``'2021-02-09T20:00:00Z'``); ``variants={...}`` names them yourself and lets each one sit at an address of its
own, ``(version, timestamp, modelling_authority)``. The ISO instant is the short form of the core library's default
name: ``version@instant`` when two requests share an instant, and ``authority/version@instant`` - for instance
``'http://elia.be/CGMES/1@2021-02-09T20:00:00Z'`` - when the requests span several modelling authorities. The
requests decide, not the scenario: ``timestamps=[...]`` takes one version and one authority, so its names stay
the instants even in a scenario that holds the trees of several authorities.

.. testcode::

    from datetime import timedelta

    t2000 = datetime(2021, 2, 9, 20, 0, tzinfo=timezone.utc)
    t2015 = t2000 + timedelta(minutes=15)
    with pp.network.connect('memory:variants') as db:
        db.load_cgmes(DATA_DIR / 'CGMES_Full.zip', '2021-02-09', '1', modelling_authority=elia)
        sender = pp.network.from_rdf_db(db, '2021-02-09', '1')
        load_id = sorted(sender.get_loads().index)[0]
        for moment, value in [(t2000, 42.0), (t2015, 84.0)]:
            sender.update_from_rdf_db(db, '2021-02-09', '1')
            with sender.event_recorder() as recorder:
                sender.update_loads(id=load_id, p0=value)
                recorder.to_rdf_updates(db, '2021-02-09', '1', moment)

        day = pp.network.from_rdf_db(db, '2021-02-09', '1', timestamps=[t2000, t2015])
        print(sorted(day.get_variant_ids()))
        print(list(day.variants_binding()['status']))
        for variant in ['2021-02-09T20:00:00Z', '2021-02-09T20:15:00Z']:
            day.set_working_variant(variant)
            print(variant, round(float(day.get_loads().loc[load_id, 'p0']), 1))
        day.set_working_variant('InitialState')

.. testoutput::

    ['2021-02-09T20:00:00Z', '2021-02-09T20:15:00Z', 'InitialState']
    ['primary', 'bound', 'bound']
    2021-02-09T20:00:00Z 42.0
    2021-02-09T20:15:00Z 84.0

Each variant is **exactly** the network a separate :func:`from_rdf_db` of that snapshot gives, and the variants are
isolated from each other: moving one leaves the others untouched.

:meth:`Network.variants_binding` says what each variant stands for - the scenario, the snapshot IRI, the modelling
authority, the timestamp, the version, the stored model per profile and the case date. Its ``status`` column has four values: ``primary``
for the network's own identity (a bulk load leaves it at the first requested snapshot), ``bound`` for a variant
that stands for a snapshot, ``unbound`` for one that stands for none, and ``refused`` for a snapshot that could
not be reached, which is not a variant of the network at all but is where its reasons survive.

Moving one variant
^^^^^^^^^^^^^^^^^^

:meth:`Network.update_from_rdf_db` with ``variant=`` creates or updates **one** variant so that it stands for a
snapshot, leaving every other variant - and the working variant of the caller - as it is. A variant that does not
exist is created by cloning the one nearest to the target in difference terms; a variant that exists is moved from
wherever it stands. The answers are the familiar ``'noop'`` and ``'diff'``::

    day.update_from_rdf_db(db, '2021-02-09', '1', t2030, variant='study')   # created
    day.update_from_rdf_db(db, '2021-02-09', '2', t2030, variant='study')   # moved

The route describes the **difference**, not whether anything happened: with ``variant=``, a ``'noop'`` still
creates the variant when it did not exist, by cloning one that already stands for the target. Ask
:meth:`Network.variants_binding` or :meth:`Network.get_variant_ids`, not the route, to learn what is there::

    day.update_from_rdf_db(db, '2021-02-09', '1', t2030, variant='copy')    # 'noop', and 'copy' now exists

Variant mode is an explicit opt-in with exactly three doors: :func:`from_rdf_db` with ``timestamps=``/
``variants=``, :meth:`Network.update_from_rdf_db` with ``variant=``, and
:meth:`NetworkEventRecorder.to_rdf_updates` with ``variant=`` or ``per_variant=True``. From then on it is
**sticky** - it stays on after a refusal and after every variant has been removed again, because a caller who
asked for variant semantics once must not get classic ones back under their feet. Every operation of this module
is then a variant operation: the full-reload route is never taken behind your back, and even a
``to_rdf_updates`` without a ``variant`` writes the working variant's history and refuses a change that belongs
to all of them.

Cloning a variant with :meth:`Network.clone_variant` does *not* switch it on - it is the ordinary IIDM idiom - and
the clone inherits the binding of its source, because its state *is* that snapshot. But that binding is only
tracked until the next **classic** operation: while the network is not in variant mode, an
``update_from_rdf_db`` without a ``variant``, a profile replacement or a classic export may have written into
every variant at once, so they forget what your clones stood for and :meth:`Network.variants_binding` no longer
lists them. Opting in on such a variant afterwards is a clear error rather than a silent plan; the ``primary``
row is never dropped. Overwriting the primary from a bound variant moves the network-level identity with the
state, so the network is afterwards at that variant's snapshot.

What a variant cannot be
^^^^^^^^^^^^^^^^^^^^^^^^

IIDM stores only part of a grid model per variant. A difference that writes something shared would change every
other variant of the network at the same time, so in variant mode it is **refused**:
:class:`RdfDbVariantRefusedError` carries ``.variant`` and ``.reasons``, and the network, its variants and their
bindings are exactly as they were.

.. code-block:: python

    try:
        day.update_from_rdf_db(db, '2021-02-09', '1', t2100, variant='21:00')
    except pp.network.RdfDbVariantRefusedError as refusal:
        print(refusal.variant, refusal.reasons)
        drifted = pp.network.from_rdf_db(db, '2021-02-09', '1', t2100)   # a network of its own

What is per variant and what is not:

=====================================================================  ==========================
 what a difference writes                                               per variant in IIDM
=====================================================================  ==========================
 switch positions, terminal and DC terminal connections                 yes
 load and equivalent-injection setpoints, load details                  yes
 generator ``targetP`` / ``targetQ`` / ``targetV``, regulation on/off   yes
 static var compensator and shunt setpoints, section count              yes
 tap positions, tap-changer regulation values and dead bands            yes
 control area ``netInterchange``                                        yes
 ``ControlArea.pTolerance`` (an IIDM property)                          no
 operational limit values (CIM 16 EQ **and** CIM 100 SSH)               no
 voltage limits, voltage level high/low limits                          no
 line, series compensator and equivalent branch impedances              no
 HVDC ``maxP``, converter loss factor and LCC power factor              no
=====================================================================  ==========================

Some verdicts depend on the network rather than on the attribute, and those are decided on the fetched statements:
switching regulation on for a tap changer that has no load-tap-changing capability sets a flag shared by all
variants; a generating unit's ``normalPF`` is per variant only when every generator of the unit already carries an
``ActivePowerControl`` extension; a non-zero reference priority on a generator without a ``ReferencePriorities``
extension creates it. Anything that is not a steady-state change at all - an equipment drift such as a renamed
line, an object added or removed, topology or state variables - is refused as well.

.. note::

   **HVDC.** With the default simplified DC model, writing a voltage source converter's active power setpoint also
   recomputes ``HvdcLine.maxP`` and the converter's loss factor, neither of which IIDM stores per variant. A
   timestamp that moves an HVDC setpoint is therefore refused in variant mode even when the recomputed values
   happen to come out unchanged; the verdict is taken per family, not per value. Importing with
   ``iidm.import.cgmes.use-detailed-dc-model`` set to ``'true'`` puts the converter controls into per-variant
   fields and lifts the restriction for voltage source converters; line commutated converters stay unsafe either
   way. The plain way out is to load that timestamp as a network of its own.

Exporting a variant
^^^^^^^^^^^^^^^^^^^

A day of parallel histories needs its changes written after the snapshot they belong to, not after whatever the
primary variant happens to be at. :meth:`NetworkEventRecorder.to_rdf_updates` therefore takes ``variant=`` - write
one variant's changes as the successor of *that variant's* snapshot - and ``per_variant=True``, which writes every
variant the changes were recorded on and answers with a dataframe::

    with day.event_recorder() as recorder:
        for variant in ['2021-02-09T20:00:00Z', '2021-02-09T20:15:00Z']:
            day.set_working_variant(variant)
            day.update_loads(id=load_id, p0=100.0)
        day.set_working_variant('InitialState')
    written = recorder.to_rdf_updates(db, '2021-02-09', '2', per_variant=True)

The timestamp and the modelling authority of such a write are the variant's own and must not be given; ``version``
still names the version the new snapshots get (``None``: the next registered name above each chain's head). Everything that can refuse happens before anything is written, so an unsupported change under
``unsupported='raise'`` leaves the database untouched. Both forms **opt the network into variant mode**.

On a network that is already in variant mode, a plain ``to_rdf_updates`` without ``variant=`` is a variant
operation too: it writes the working variant's history, and a change IIDM does not store per variant is an
unsupported change there as well, raised or skipped according to ``unsupported``.

The three file exports -
:meth:`NetworkEventRecorder.to_ssh`, :meth:`NetworkEventRecorder.to_cgmes_diff` and
:meth:`NetworkEventRecorder.to_cgmes_diffs` - take the same ``variant=``: the values written are that variant's,
and for a bound variant the header supersedes that variant's model and carries its scenario time. Leaving
``variant=`` out exports the **working** variant, and on a network **in variant mode** that variant is treated
exactly as if it had been named - so the natural gesture (select a variant, change it, export) writes a document
that supersedes the right snapshot, and a change that belongs to every variant is refused there as it is by
``to_rdf_updates``, whichever variant is selected, the primary included.

Outside variant mode an unnamed export is exactly what it was before variants existed, also while a variant you
cloned yourself is the working one: the clone is tracked, but tracking is not an opt-in, and such an export still
writes a shared change such as a line impedance.

Memory and threads
^^^^^^^^^^^^^^^^^^

A variant costs one slot in every per-variant array - setpoints, switch states, terminal and bus state variables -
not a copy of the topology. The core benchmark measures about **1 MB for 96 variants** of the MicroGrid test
configuration, read off the heap after a collection, so treat it as an order of magnitude rather than an exact
figure; it grows with the number of per-variant values, not with the file size.

**Threads.** Two facts decide how a parallel study has to be written, and neither is obvious.

*With* ``allow_variant_multi_thread_access=True`` - which :func:`from_rdf_db` sets once every variant exists, the
only safe moment, because creating a variant grows the per-variant arrays of the whole network - IIDM keeps one
working variant per thread. But pypowsybl attaches a **fresh Java thread to every call** made outside the main
thread, so a Python worker cannot *hold* a working variant: ``set_working_variant(name)`` in a worker succeeds
and the next call in that worker fails with *"Variant index not set for current thread"*. Parallel studies
therefore go through the APIs that take the variant as an argument, for instance
:func:`pypowsybl.loadflow.run_ac_async` (``run_ac_async(network, variant_id)``), which runs several variants of
one network concurrently.

*Without* the flag - the default - there is **one** working variant for the whole network, so another thread
changes what your thread is looking at. And while an operation with ``variant=`` runs, the values of the variant
being written are in flux and the network-level identity is swapped: a reader in another thread can see another
variant's values, half-applied values, or another variant's identity. Do not touch a network from another thread
while such an operation runs unless the network was loaded with the flag and the reader passes its variant
explicitly.

The catalogue
-------------

Seven views, all dataframes: :meth:`RdfDatabase.scenarios` (the days in the database, with the modelling
authorities each holds), :meth:`RdfDatabase.snapshots`, :meth:`RdfDatabase.versions` (one timestamp's chain),
:meth:`RdfDatabase.timestamps` (one authority's tree), :meth:`RdfDatabase.assembly` (every authority at one
moment), :meth:`RdfDatabase.models` (the stored CGMES models, with the chain each difference belongs to) and
:meth:`VersionRegistry.dataframe` (the version names of a scenario); :meth:`RdfDatabase.modelling_authorities`
lists the trees of a scenario. ``timestamp`` columns are ``datetime64[ns, UTC]``, ``version`` columns hold the
version name (``str``) and every table that has one carries the ``rank`` the registry gives it (``int64``,
nullable where a row may stand for no snapshot). A scenario the database does not hold gives an empty
frame with the documented columns rather than an error. Every stored difference names the **capability version** of the library that wrote it - the
``capabilities`` column of :meth:`RdfDatabase.models`, ``<12 hex>/<core version>``: a hash of what that library could
apply in place, and its version. A reader trusts a difference written by its own or an older version; one written by
a newer library is re-checked against the reader's own table before it is applied in place, and when the reader
cannot apply it, :meth:`Network.update_from_rdf_db` falls back to ``'full'`` with a reason naming both versions.

Checkpoints
-----------

:meth:`RdfDatabase.checkpoint` materialises a snapshot as a full state, so that loading it needs no walk down the
difference chain. It changes nothing about what the snapshot *is* - the same network comes back before and after -
and only trades storage for load time. It is worth it at the end of a long day of timestamps.

Performance
-----------

Measured on 2026-09-21 with ``data/CGMES_Full.zip`` (~1 MB, 6 loads) on eight cores, once against the in-process
store and once against a Fuseki subprocess on the loopback interface, with two scenarios in the database. Every
number is the median of five runs after two warm-ups, except the two marked *single run*; the benchmark that
produces them is ``tests/test_rdf_db_benchmark.py``.

=====================================================  ==========  ==========
 what                                                   memory:     Fuseki
=====================================================  ==========  ==========
 file import, ``pp.network.load``                        82 ms       78 ms
 upload of the root snapshot                             51 ms      183 ms
 cold load of a snapshot (``cache=False``)               35 ms       92 ms
 warm load of a snapshot                                 34 ms       56 ms
 ``to_rdf_updates`` of 1 change                         1.9 ms       37 ms
 ``to_rdf_updates`` of 500 changes                      2.3 ms       56 ms
 500 ``update_loads`` calls **and** the export          110 ms      156 ms
 update over 1 difference                               8.6 ms       52 ms
 update over 10 differences                             7.8 ms       53 ms
 full route inside one scenario (equipment drift)        40 ms       96 ms
 full route across scenarios                             34 ms       61 ms
 a day of 24 timestamps as **variants**                 111 ms      161 ms
 the same day as 24 separate networks *(single run)*    832 ms     1192 ms
 the same day walked by one network *(single run)*      115 ms      412 ms
 one variant moved to another snapshot                  5.1 ms       17 ms
=====================================================  ==========  ==========

.. note::

   **What day these numbers describe.** Each of the 24 timestamps is a *one-value* steady-state difference on a
   six-load model, so the per-timestamp apply is as small as it gets and the bulk load looks as good as it can.
   The core benchmark measures the same feature on a thin and on a **rich** day (six loads, two generators and a
   tap changer per timestamp) over 96 timestamps and reports about 25 % more for the rich one; see the core
   documentation on network variants. The numbers were measured before the addressing became
   ``(scenario, version, timestamp, modelling_authority)``; the key is matched once per operation, so the change does
   not move them. The ratio against 24 separate loads, which is
   what the feature is about, is dominated by the one conversion either way.

The two export rows measure different things on purpose. The ``to_rdf_updates`` rows time the **export alone** -
the changes are recorded before the clock starts - while the third is the whole gesture a user makes, five hundred
one-row :meth:`Network.update_loads` calls and then the export, and it is dominated by the five hundred calls.

Four things are worth taking away. A warm load of a snapshot is faster than importing the same model from files,
and moving a network from one state to another costs 8-53 ms rather than a full import - which is the number that
matters for a day of timestamps. Storing a difference is one round trip whether it carries one change or five
hundred. Walking ten differences costs the same as walking one: the planner answers with one query whatever the
chain length, and every difference on the path is fetched in one request. And a day loaded **as variants** is
7-8 times faster than the same day loaded as one network per timestamp, because the equipment is converted once;
against a single network walked through the day - which keeps no history - it comes out level in process and is
2.6 times faster over HTTP, where the walk pays one plan query per step.

The bottleneck is not the binding layer. The Java split reported in ``get_rdf_db_update_info`` accounts for 45 ms
of the 52 ms an update takes over HTTP, dominated by the round trip of the planning query; in process the same call
is 8.6 ms of which 1-2 ms is the binding (the option and parameter maps, the outcome handle, the string map).
The upload is dearer over HTTP for a simpler reason: the archive has to be parsed *and* its graphs transferred.

Notebooks
---------

Three notebooks in `examples/notebooks <https://github.com/powsybl/pypowsybl/tree/main/examples/notebooks>`_ walk
through all of this with a real grid model:

* ``01_split_loading_file_to_db_to_iidm.ipynb`` - files into the database once, networks out of it many times;
* ``02_diff_round_trip_with_versions.ipynb`` - a sender, a receiver, the three routes, and a second day;
* ``03_timesteps_day_run.ipynb`` - a day of timestamps with a load flow at each step, checkpoints, and midnight.

They run against ``memory:`` out of the box. To run them::

    python -m venv .venv && . .venv/bin/activate
    pip install pypowsybl jupyterlab matplotlib
    python -m ipykernel install --user --name pypowsybl-rdfdb
    jupyter lab examples/notebooks

For a real database, start one and point the notebooks at it::

    docker run --rm -p 3030:3030 -e ADMIN_PASSWORD=admin \
        -e ENABLE_DATA_WRITE=true -e ENABLE_UPDATE=true -e ENABLE_UPLOAD=true secoresearch/fuseki
    export PYPOWSYBL_RDF_DB=http://localhost:3030/ds/sparql

``examples/notebooks/run_all.py`` executes all three with ``nbconvert`` and can start a Fuseki subprocess itself
(``--fuseki``). See the README in that folder.

Limitations
-----------

* A combined grid model loads as one flat network (``authorities=[...]``), never with subnetworks, and its network
  id is one of the equipment model ids. It is read-only for updates and variants; custom profiles are not composed.
  A write-back that touches two owned trees writes two snapshots, one after the other, not in one transaction.
* Authentication is HTTP basic (``user``/``password``) or a custom header (``headers=``). Nothing else is wired up.
* Restricting a load to some profiles is honoured against a real server; on the ``memory:`` backend a remote-mode
  load always sees the whole scenario.
* Ingesting a timestamp from files compares the **equipment model and the steady state hypothesis** by default.
  State variables and topology change wholesale between timestamps, so a difference of them would be as large as
  the data itself; their files are left alone and the snapshot inherits the ones of the state it derives from,
  unless ``profiles`` names them. Run a load flow if you need flows consistent with the setpoints you just
  ingested.
* The un-versioned upload (``load_cgmes`` without an address) is refused on a scenario that holds snapshots: its
  instance files belong to a snapshot and are never overwritten.
* The version chain of a timestamp is **linear**: one successor per snapshot, and versions only grow in rank. A
  writer whose network is not at the head is refused and has to reload the head and record again.
* A version name a snapshot carries cannot be renamed (the snapshot's IRI carries it); rerank to change the order.
* One base day per scenario. Several days are several scenarios, and a walk from one to another is a full reload,
  never a difference - so "23:45 of day one to 00:00 of day two" is not a diff.
* Stores written before the addressing became ``(scenario, version, timestamp, modelling_authority)``, and stores
  of an older layout (schema 3, integer versions), are refused with a message saying so; there is no migration -
  clear the scenario and ingest it again.
* ``query_mode='remote'`` cannot read a scenario that holds differences or snapshots.
* Variants are lost on a full reload, and recorders created before one are refused (see above).
* Rollovers are flagged explicitly; the library never rolls over by itself, and flagging one re-pins nothing that
  is already stored.
* ``post_processors`` are only honoured when nothing is addressed and no ``profiles`` are given; the snapshot entry
  points of the core library take no load options, and neither does the variant bulk load.
* In variant mode, a difference that writes something IIDM does not store per variant - limits, impedances, the
  HVDC values of the simplified DC model, an equipment drift - is refused, never applied and never turned into a
  silent full reload. The table above is the list; the way out is a network of its own.
* ``case_date`` and ``forecast_distance`` are network-level in IIDM. The binding carries each variant's case date
  and it is swapped in for the duration of an operation on that variant - which is what dates an export of that
  variant correctly - but ``network.get_case_date()`` outside such an operation always reports the primary's.
* Variant bindings are not serialised: writing a multi-variant network to XIIDM keeps neither the variants nor
  what they stood for.
* Creating a variant while other threads read the network is unsupported; that is an IIDM property, not a
  property of this binding.
* The ``SEPARATE_NETWORK`` fallback of the core library - answer a refusal with a single-variant network of that
  snapshot - is not bound in Python. Catch :class:`RdfDbVariantRefusedError` and call :func:`from_rdf_db`.

See also
--------

The Java side of all this, the graph naming and the measured numbers are documented in the PowSyBl core
documentation, under CGMES / "RDF database".
