#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Shared fixtures for the RDF database tests.

Two backends are exercised by the same tests: the in-process ``memory:`` store, which is always available, and a
real Apache Jena Fuseki server started as a **subprocess** (never as a binding - Jena must not enter the native
image). One server is started per test session and shared; the tests keep out of each other's way by using a
different *scenario* name each, which is the isolation the database API is built on anyway.
"""
from typing import Iterator
from uuid import uuid4

import pytest

import fuseki_server


@pytest.fixture(scope='session')
def fuseki_url() -> Iterator[str]:
    """The dataset URL of a Fuseki server running for the length of the test session."""
    reason = fuseki_server.unavailable_reason()
    if reason is not None:
        pytest.skip(reason)
    server = fuseki_server.start()
    try:
        yield server.dataset_url
    finally:
        server.stop()


@pytest.fixture(params=['memory', 'fuseki'])
def rdf_db_url(request: pytest.FixtureRequest) -> str:
    """A database URL, once for each backend: a fresh in-process store, and the shared Fuseki server."""
    if request.param == 'memory':
        return f'memory:{uuid4().hex}'
    return request.getfixturevalue('fuseki_url')


@pytest.fixture
def scenario() -> str:
    """A unique name for the base day a test works on, so that tests never share graphs on the shared server."""
    return f'2021-02-09-{uuid4().hex[:8]}'


@pytest.fixture
def scenario2() -> str:
    """A unique name for a second day, to show that two base models live side by side in one database."""
    return f'2021-02-10-{uuid4().hex[:8]}'
