#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Start an Apache Jena Fuseki server as a **subprocess**, for the tests and the demo of the RDF database bindings.

Fuseki is a subprocess and never a binding: Apache Jena must not enter the pypowsybl native image (the client side
of everything pypowsybl does with a graph database is RDF4J plus the JDK HTTP client). A separate process also
proves rather more than an embedded server would: the requests really travel over HTTP.

The server is the standalone ``jena-fuseki-server`` jar. It is looked for, in order, at

1. ``$PYPOWSYBL_FUSEKI_JAR`` - an explicit path to the jar,
2. every Maven repository of ``$PYPOWSYBL_MAVEN_REPO`` (several are separated the way ``$PATH`` separates its
   entries; a relative one is resolved against this checkout), then ``~/.m2/repository``, under
   ``org/apache/jena/jena-fuseki-server/<version>/``; the highest version wins.

If it is nowhere to be found, :func:`find_fuseki_jar` returns ``None`` and the tests skip with that reason; fetch
it once with ``mvn dependency:get -Dartifact=org.apache.jena:jena-fuseki-server:6.2.0``.

The server writes its console output to a log file, ``fuseki.log`` in its own working directory by default, which
is removed together with that directory when the server is stopped. ``$PYPOWSYBL_FUSEKI_LOG`` - a file, or a
directory in which ``fuseki-<port>.log`` is created - keeps the log outside it, for diagnosing a server that
refuses to come up. The tail of the log is part of the error message either way.

Usage::

    with fuseki_server.start() as server:
        print(server.dataset_url)      # http://127.0.0.1:<port>/ds
"""
import os
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from types import TracebackType
from typing import List, Optional, Tuple, Type

DEFAULT_DATASET = 'ds'
STARTUP_TIMEOUT_SECONDS = 120.0
LOG_TAIL_LINES = 20

_CHECKOUT = Path(__file__).resolve().parents[1]


def _maven_repositories() -> List[Path]:
    """The Maven repositories to look in: the configured ones first, then the user's own."""
    repositories = []
    for entry in os.environ.get('PYPOWSYBL_MAVEN_REPO', '').split(os.pathsep):
        if entry.strip():
            configured = Path(entry.strip())
            repositories.append(configured if configured.is_absolute() else _CHECKOUT / configured)
    repositories.append(Path.home() / '.m2' / 'repository')
    return repositories


def _version_key(version: str) -> Tuple[int, ...]:
    """Order version directories numerically, so that ``6.10.0`` beats ``6.2.0``; a qualifier counts as its digits."""
    return tuple(int(match.group()) if match else 0
                 for match in (re.match(r'\d+', part) for part in version.split('.')))


def find_fuseki_jar() -> Optional[Path]:
    """
    Locate the standalone Fuseki server jar.

    Returns:
        the path to the jar, or None when it is not installed anywhere this knows about
    """
    configured = os.environ.get('PYPOWSYBL_FUSEKI_JAR')
    if configured:
        jar = Path(configured)
        return jar if jar.is_file() else None
    for repository in _maven_repositories():
        directory = repository / 'org' / 'apache' / 'jena' / 'jena-fuseki-server'
        if not directory.is_dir():
            continue
        jars = sorted(directory.glob('*/jena-fuseki-server-*.jar'), key=lambda jar: _version_key(jar.parent.name))
        if jars:
            return jars[-1]
    return None


def find_java() -> Optional[str]:
    """
    Locate the java launcher, preferring ``$JAVA_HOME``.

    Returns:
        the path to the java executable, or None when there is none
    """
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = Path(java_home) / 'bin' / 'java'
        if candidate.is_file():
            return str(candidate)
    return shutil.which('java')


def unavailable_reason() -> Optional[str]:
    """
    Say why a Fuseki server cannot be started here, if it cannot.

    Returns:
        a message for ``pytest.skip``, or None when everything needed is there
    """
    if find_java() is None:
        return 'no java on the PATH and no JAVA_HOME - cannot start a Fuseki server'
    if find_fuseki_jar() is None:
        return ('Fuseki jar not found - set PYPOWSYBL_FUSEKI_JAR, or install it with '
                '"mvn dependency:get -Dartifact=org.apache.jena:jena-fuseki-server:6.2.0"')
    return None


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(('127.0.0.1', 0))
        return int(sock.getsockname()[1])


def _log_destination(port: int, base: Path) -> Path:
    """Where the server's console output goes: ``$PYPOWSYBL_FUSEKI_LOG`` when set, else inside its own base."""
    configured = os.environ.get('PYPOWSYBL_FUSEKI_LOG')
    if not configured:
        return base / 'fuseki.log'
    destination = Path(configured)
    if not destination.is_absolute():
        destination = _CHECKOUT / destination
    if destination.is_dir():
        return destination / f'fuseki-{port}.log'
    destination.parent.mkdir(parents=True, exist_ok=True)
    return destination


def _log_tail(log_file: Path) -> str:
    """The last lines of the server log, as a suffix for an error message."""
    try:
        lines = log_file.read_text(encoding='utf-8', errors='replace').splitlines()
    except OSError as error:
        return f'; its log {log_file} could not be read ({error})'
    if not lines:
        return f'; its log {log_file} is empty'
    tail = '\n'.join(lines[-LOG_TAIL_LINES:])
    return f'; the last lines of {log_file} are:\n{tail}'


class FusekiServer:
    """A running Fuseki subprocess with one in-memory dataset. Use it as a context manager, or call :meth:`stop`."""

    def __init__(self, process: subprocess.Popen, port: int, dataset: str, base: str, log_file: Path) -> None:
        self._process = process
        self._port = port
        self._dataset = dataset
        self._base = base
        self._log_file = log_file

    @property
    def port(self) -> int:
        """The port the server listens on."""
        return self._port

    @property
    def log_file(self) -> Path:
        """The file the server's console output is written to."""
        return self._log_file

    @property
    def base_url(self) -> str:
        """The server root, without the dataset."""
        return f'http://127.0.0.1:{self._port}'

    @property
    def dataset_url(self) -> str:
        """The dataset URL, which is what :func:`pypowsybl.network.connect_rdf_db` wants."""
        return f'{self.base_url}/{self._dataset}'

    def __enter__(self) -> 'FusekiServer':
        return self

    def __exit__(self, exc_type: Optional[Type[BaseException]], exc_value: Optional[BaseException],
                 traceback: Optional[TracebackType]) -> None:
        self.stop()

    def stop(self) -> None:
        """
        Stop the server and remove its working directory; a log file outside that directory survives. Calling it
        twice does nothing.
        """
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=30)
        shutil.rmtree(self._base, ignore_errors=True)


def start(dataset: str = DEFAULT_DATASET, port: Optional[int] = None,
          timeout: float = STARTUP_TIMEOUT_SECONDS) -> FusekiServer:
    """
    Start a Fuseki server on a free port with one writable in-memory dataset.

    Args:
        dataset: the dataset name, served at ``/<dataset>``
        port: the port to listen on; a free one is picked when None
        timeout: how long to wait for the server to answer its ping

    Returns:
        the running server

    Raises:
        RuntimeError: when the jar or java is missing, or the server does not come up in time; the message
            carries the last lines of the server log
    """
    reason = unavailable_reason()
    if reason is not None:
        raise RuntimeError(reason)
    java = find_java()
    jar = find_fuseki_jar()
    assert java is not None and jar is not None  # already checked by unavailable_reason
    chosen_port = _free_port() if port is None else port
    # Fuseki writes a "run" directory (configuration, logs, shiro.ini) into FUSEKI_BASE, which defaults to the
    # working directory. Point it at a temporary directory so that a test run leaves nothing in the repository.
    base = tempfile.mkdtemp(prefix='pypowsybl-fuseki-')
    log_file = _log_destination(chosen_port, Path(base))
    environment = dict(os.environ, FUSEKI_BASE=str(Path(base) / 'run'))
    try:
        # Both streams go to one file: a server that refuses to start (port clash, bad JVM option, wrong jar) says
        # why there, and the tail of it goes into the error below.
        with open(log_file, 'wb') as log:
            process = subprocess.Popen(  # pylint: disable=consider-using-with
                [java, '-jar', str(jar), '--port', str(chosen_port), '--update', '--mem', f'/{dataset}'],
                cwd=base, env=environment, stdout=log, stderr=subprocess.STDOUT)
    except OSError as error:
        shutil.rmtree(base, ignore_errors=True)
        raise RuntimeError(f'the Fuseki server could not be started with {java}: {error}') from error
    server = FusekiServer(process, chosen_port, dataset, base, log_file)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            message = (f'the Fuseki server died at startup (exit code {process.returncode})'
                       f'{_log_tail(log_file)}')
            server.stop()
            raise RuntimeError(message)
        try:
            with urllib.request.urlopen(f'{server.base_url}/$/ping', timeout=2) as response:
                if response.status == 200:
                    return server
        except (urllib.error.URLError, OSError):
            time.sleep(0.2)
    message = (f'the Fuseki server did not answer {server.base_url}/$/ping within {timeout} s'
               f'{_log_tail(log_file)}')
    server.stop()
    raise RuntimeError(message)
