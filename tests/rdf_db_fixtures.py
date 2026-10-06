#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Fixtures of the versioned RDF database tests: the base grid model, a copy of it describing the next day, the
steady state of a timestamp and a timestamp whose equipment model drifted.

They are the generators the notebooks use, ``examples/notebooks/notebook_utils.py``: that copy is the one users
read and it has to run without the test suite, so the tests import it rather than keep a second one.
``data/CGMES_Full.zip`` is one day (``md:Model.scenarioTime 2021-02-09T19:30:00Z``); a second day is a second
scenario with its own model identifiers, which :func:`next_day_zip` produces.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'examples' / 'notebooks'))

# pylint: disable=wrong-import-position,unused-import
from notebook_utils import (AUTHORITY, BASE, CGMES_ZIP, NEXT_DAY, at, drifted_name, eq_drift,  # noqa: E402,F401
                            next_day_zip, ssh_variant)

__all__ = ['AUTHORITY', 'BASE', 'CGMES_ZIP', 'NEXT_DAY', 'at', 'drifted_name', 'eq_drift', 'next_day_zip',
           'ssh_variant']
