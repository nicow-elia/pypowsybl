Exchanging network changes (partial SSH, CGMES difference models)
=================================================================

.. currentmodule:: pypowsybl.network

Two systems that already share the same grid model do not have to exchange whole CGMES files whenever a setpoint
moves. If both sides have loaded the same model, the sender can describe *only what it changed* and the receiver can
apply that description to the model it already holds. This page is both the tutorial and the reference of how
pypowsybl does that.

Two document kinds carry such a change:

* a **partial Steady State Hypothesis file**, an ordinary ``_SSH`` instance file holding only the objects that
  changed. Every CGMES tool can read it;
* a **CGMES difference model** (IEC 61970-552 ``dm:DifferenceModel``), which holds the change in both directions,
  forward and reverse, so the receiver can also check that it applies and undo it.

Recording the changes
---------------------

:meth:`Network.event_recorder` returns a :class:`NetworkEventRecorder`. Used as a context manager it records every
change made to the network inside the block.

.. testcode::

    import pypowsybl as pp

    sender = pp.network.load(DATA_DIR / 'CGMES_Full.zip')
    receiver = pp.network.load(DATA_DIR / 'CGMES_Full.zip')

    load_id = sorted(sender.get_loads().index)[0]
    generator_id = sorted(sender.get_generators().index)[0]

    with sender.event_recorder() as recorder:
        sender.update_loads(id=load_id, p0=11.0, q0=3.0)
        sender.update_generators(id=generator_id, target_p=42.0)

    print(len(recorder))

.. testoutput::

    3

Three, not two: a recorder records one event per changed *attribute*, not per call. What was recorded can be
inspected as a dataframe, which is mostly useful while debugging. The attribute names are the IIDM ones, which is
not always the name of the dataframe column that changed it (``target_p`` is written as ``targetP``):

.. testcode::

    print(recorder.events[['type', 'attribute']].to_string(index=False))

.. testoutput::

      type attribute
    UPDATE        p0
    UPDATE        q0
    UPDATE   targetP

Exporting a partial SSH file
----------------------------

:meth:`NetworkEventRecorder.to_ssh` writes the recorded changes as a partial SSH document. With no argument it
returns the document as a string; given a path or an open file it writes there and returns ``None``.

.. testcode::

    ssh = recorder.to_ssh()
    print(ssh.startswith('<?xml'))

.. testoutput::

    True

The receiver applies it like any other CGMES update. :meth:`Network.update_from_string` wraps the document in an
in-memory archive; the name it is given matters, because CGMES recognises the profile of an instance file from its
name.

.. important::

   A partial SSH is still an SSH file, and the ordinary CGMES update resets what an SSH file does not mention. The
   receiver therefore has to be told to keep those values, with the import parameter
   ``iidm.import.cgmes.use-previous-values-during-update``. Without it every setpoint the partial file leaves out
   falls back to its default. A difference model needs nothing of the sort, because it states which objects it
   talks about.

.. testcode::

    keep_the_rest = {'iidm.import.cgmes.use-previous-values-during-update': 'true'}

    receiver.update_from_string(ssh, 'update_SSH.xml', parameters=keep_the_rest)
    print(receiver.get_loads().loc[load_id]['p0'], receiver.get_generators().loc[generator_id]['target_p'])

.. testoutput::

    11.0 42.0

Exporting a difference model
----------------------------

:meth:`NetworkEventRecorder.to_cgmes_diff` writes one ``dm:DifferenceModel`` document of one profile. It is applied
exactly like the partial SSH, under a name ending in ``_SSH_DIFF.xml``:

.. testcode::

    other_receiver = pp.network.load(DATA_DIR / 'CGMES_Full.zip')
    diff = recorder.to_cgmes_diff()
    other_receiver.update_from_string(diff, 'update_SSH_DIFF.xml')
    print(other_receiver.get_loads().loc[load_id]['p0'])

.. testoutput::

    11.0

``granularity`` decides how much of each touched object the document describes. ``'full_object'`` (the default)
writes the complete consistency group of every touched object in both directions, which is what keeps the property
groups a CGMES receiver reads together consistent; ``'changed_only'`` writes the strict delta, which is shorter but
gives the receiver less to check against.

A change may touch more than one profile. :meth:`NetworkEventRecorder.to_cgmes_diffs` writes one document per
touched profile, and :meth:`NetworkEventRecorder.touched_profiles` says which those are:

.. testcode::

    print(recorder.touched_profiles())
    print(sorted(recorder.to_cgmes_diffs()))

.. testoutput::

    ['SSH']
    ['SSH']

``to_cgmes_diffs`` can write into a directory, into a zip archive, or into a binary buffer, which is what a sender
handing the change over in memory wants:

.. testcode::

    import io

    buffer = io.BytesIO()
    recorder.to_cgmes_diffs(buffer)
    buffer.seek(0)

    buffered_receiver = pp.network.load(DATA_DIR / 'CGMES_Full.zip')
    buffered_receiver.update_from_binary_buffer(buffer)
    print(buffered_receiver.get_loads().loc[load_id]['q0'])

.. testoutput::

    3.0

Writing the same thing to files:

.. testcode::

    import tempfile
    import pathlib

    with tempfile.TemporaryDirectory() as directory:
        recorder.to_cgmes_diffs(pathlib.Path(directory) / 'out')          # one file per profile in a directory
        recorder.to_cgmes_diffs(pathlib.Path(directory) / 'out.zip')      # the same files inside a zip archive
        recorder.to_ssh(pathlib.Path(directory) / 'update_SSH.xml')       # the partial SSH as one file
        print(sorted(p.name for p in (pathlib.Path(directory) / 'out').iterdir()))

.. testoutput::

    ['update_SSH_DIFF.xml']

Equipment changes: operational limits, voltage limits and impedances
--------------------------------------------------------------------

A difference model is not limited to the steady state hypothesis. Three kinds of *equipment* change can be
exchanged as well: the value of an operational limit (:meth:`Network.update_loading_limits`), the voltage limits of
a voltage level (:meth:`Network.update_voltage_levels`) and the series impedance and shunt admittance of a line or
boundary line (:meth:`Network.update_lines`, :meth:`Network.update_boundary_lines`). They are described in an **EQ**
difference model, written under a name ending in ``_EQ_DIFF.xml``.

Which profile carries a *limit value* depends on the CIM version of the model, because CGMES moved it: in CGMES 3
(CIM100) a limit value is steady state data, so it travels in the SSH difference model and in a partial SSH file;
in CGMES 2.4.15 it is equipment data and only an EQ difference model can carry it. An impedance is equipment data
in both versions, so ``to_ssh`` refuses it and names the EQ profile.
:meth:`NetworkEventRecorder.touched_profiles` answers the question for the model at hand, and
:meth:`NetworkEventRecorder.to_cgmes_diffs` writes one document per touched profile, so a recording mixing
setpoints and equipment needs no special handling:

.. testcode::

    eq_sender = pp.network.load(DATA_DIR / 'CGMES_Full.zip')      # a CGMES 3 model
    eq_receiver = pp.network.load(DATA_DIR / 'CGMES_Full.zip')

    # one CGMES ACLineSegment: a merged line is two of them under a single IIDM id, and neither its impedance
    # nor its limits belong to one CGMES object, so those cannot be exchanged
    line_id = next(i for i in sorted(eq_sender.get_lines().index) if ' + ' not in i)

    with eq_sender.event_recorder() as equipment:
        eq_sender.update_lines(id=line_id, r=2.5, x=30.0)
        print(equipment.touched_profiles())

        try:
            equipment.to_ssh()
        except pp.PyPowsyblError as e:
            print('no SSH file can carry it:', 'EQ profile' in str(e))

        eq_diff = equipment.to_cgmes_diff(profile='EQ')

    eq_receiver.update_from_string(eq_diff, 'update_EQ_DIFF.xml')
    applied = eq_receiver.get_lines().loc[line_id]
    print(applied['r'], applied['x'])

.. testoutput::

    ['EQ']
    no SSH file can carry it: True
    2.5 30.0

What CGMES can hold constrains what such a change may look like. A line's shunt admittance is one total ``gch`` /
``bch`` that the import splits equally between the two sides, so ``g1`` and ``g2`` (and ``b1`` and ``b2``) have to
be changed together and to the same value; a one-sided change is unsupported. Transformer impedances are
**not** exported at all: CGMES holds them per transformer end together with the tap step corrections, and the
import folds all of that into the single IIDM value, so the way back is not unique. The details and the full list
are in the
`CGMES export documentation of powsybl-core <https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/grid_exchange_formats/cgmes/export.html>`_.

What can be exported, and what cannot
-------------------------------------

A partial SSH file describes steady state hypothesis changes: setpoints, tap positions, switch positions,
regulation flags, section counts, the extensions that map onto them, and — in a CGMES 3 model — operational limit
values. A difference model describes those and the equipment changes of the section above. The full list, and what
a receiver has to have configured the same way, is in the
`CGMES export documentation of powsybl-core <https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/grid_exchange_formats/cgmes/export.html>`_.

Everything else is *unsupported*: creating or removing elements, adding or removing a limit or changing its
duration, selecting another limit set, changing equipment attributes such as ``max_p`` or a transformer impedance,
connecting or disconnecting a terminal, and the state values ``p``, ``q``, ``v`` and ``angle`` — which is why
**running a load flow inside the recorded block** produces unsupported changes, since a load flow writes its results
through the ordinary setters.

The ``unsupported`` argument decides what happens then. ``'raise'``, the default, refuses to write anything and
names the change; ``'ignore'`` skips it and logs a warning:

.. testcode::

    with sender.event_recorder() as second:
        sender.update_loads(id=load_id, p0=12.0)
        sender.update_generators(id=generator_id, max_p=999.0)

        try:
            second.to_ssh()
        except pp.PyPowsyblError as e:
            print('refused:', 'maxP' in str(e))

        print(load_id in second.to_ssh(unsupported='ignore'))

.. testoutput::

    refused: True
    True

Header metadata
---------------

Every export accepts the header values of the model it writes as keyword arguments. Each has a default derived from
the model the network was loaded from, so a plain ``to_ssh()`` already produces a well formed, chainable header.

===========================  ===========================  ======================================================
Argument                     Type                         Meaning / default
===========================  ===========================  ======================================================
``model_id``                 ``str``                      ``md:Model`` id (``urn:uuid:...``); generated by default
``version``                  ``int``                      version of the superseded model + 1
``created``                  ``datetime`` or ISO-8601     now; a naive ``datetime`` is taken as UTC
``scenario_time``            ``datetime`` or ISO-8601     the case date of the network
``supersedes``               ``str`` or sequence          *replaces* the default (the model the network was loaded from); ``[]`` means none
``depends_on``               ``str`` or sequence          *replaces* the dependencies inherited from that model; ``[]`` means none
``modeling_authority_set``   ``str``                      the one of the source model
``description``              ``str``                      —
===========================  ===========================  ======================================================

Passing ``created=`` makes an export reproducible, which is what lets two exports be compared byte for byte. In the
difference model exports a value may also be a dict keyed by profile, for example
``model_id={'SSH': 'urn:uuid:...'}``; a plain value applies to every written profile.

Chaining several changes from one sender
----------------------------------------

An export does not change the sender's own model metadata. Two consecutive exports from the same sender therefore
both declare that they supersede the *original* model, and a receiver that checks supersedes rejects the second one.
There are two ways out:

* give the first difference an explicit ``model_id=`` and let the second one declare ``supersedes=[that id]`` (and a
  ``version=``), which is the correct chain; or
* tell the receiver not to check, with the import parameter
  ``{'iidm.import.cgmes.diff.check-supersedes': 'false'}``.

A partial SSH has no such check.

Limitations
-----------

* **Values are read at export time.** The recorded events say *what* changed, not what it became; the exporter reads
  the current value. Export before making further unrelated changes.
* **A load flow inside the block** writes ``p``, ``q``, ``v`` and ``angle`` through the setters, so it produces
  unsupported changes. Export before running it, or use ``unsupported='ignore'``.
* **Other variants.** A recorder records the changes of all variants; a change made on a variant other than the
  working variant at export time is unsupported. Creating, cloning or removing a variant inside the block is
  itself an unsupported change, even when every attribute change is on the working variant.
* **Merged networks** cannot be exported: a partial file and a difference model each describe one individual grid
  model. Export one subnetwork at a time.
* **Not thread safe.** One recorder belongs to one thread. An export pauses the recording while it runs, so that
  the export cannot record its own writes; a change made concurrently by another thread during an export is
  therefore not recorded either.
* **Per-unit is transparent.** ``network.per_unit = True`` converts values before the setter, so what is recorded
  and exported is always the SI value.
* **Recordings cost memory.** A load flow inside a block records thousands of events; :meth:`NetworkEventRecorder.clear`
  drops them.
* **Text files must be UTF-8.** The exported document declares UTF-8, and a CGMES model description is rarely
  ASCII, so a text stream opened without ``encoding='utf-8'`` produces a file whose declaration lies wherever the
  platform default is not UTF-8. Writing to a path or to a binary stream is always UTF-8; a text stream with
  another encoding is reported as a warning.
* **A partial SSH needs the receiver to keep what it does not mention**, with the import parameter
  ``iidm.import.cgmes.use-previous-values-during-update``, see above.
* **Equipment changes have shapes CGMES cannot hold**: a one-sided shunt admittance, a transformer impedance, and
  anything structural about a limit (adding or removing one, its duration or name, selecting another limit set),
  see the section on equipment changes.

Performance
-----------

Recording is a listener call per changed attribute and is cheap: in the benchmark the same 1000 ``update_*``
changes take the same 3.8 ms whether a recorder is attached or not. The cost of an export grows with the number of
*distinct* attributes changed, not with the number of calls: repeated changes of one attribute are compacted to the
last one before anything is written.

On a 2026 eight-core Linux machine, over an ieee300 network exported to CGMES and re-imported, 1000 recorded
changes spread over 267 elements export in **1.9 ms** as a partial SSH and in **4.2 ms** as a difference model; a
single change exports in about **0.1 ms**. Applying either document on the receiver costs about **12 ms**, which is
dominated by the fixed cost of the CGMES update workflow rather than by the size of the change. The numbers are
reproduced by ``tests/test_network_event_recorder_benchmark.py``, whose module docstring holds the full table.

Straight into a database
------------------------

The documents above are not the only destination. The same recorder can write what it holds **straight into an RDF
graph database** with :meth:`NetworkEventRecorder.to_rdf_updates`, where the changes become one addressable
snapshot of a scenario rather than a file somebody has to transfer and re-import: no serialisation to XML, no
parsing on the other side, and the receiver reaches the new state with one
:meth:`Network.update_from_rdf_db` call. See :doc:`rdf_database`.
