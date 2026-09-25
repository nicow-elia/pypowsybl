# pypowsybl and an RDF graph database

Three notebooks about loading CGMES through a SPARQL graph database, exchanging changes as differences instead of
files, and addressing a whole day of grid states.

| Notebook | What it shows |
| --- | --- |
| [`01_split_loading_file_to_db_to_iidm.ipynb`](01_split_loading_file_to_db_to_iidm.ipynb) | CGMES files into the database once, networks out of it many times; the catalogue views; what the two paths cost |
| [`02_diff_round_trip_with_versions.ipynb`](02_diff_round_trip_with_versions.ipynb) | a sender records a change and writes it as a new version; a receiver walks to it; `noop` / `diff` / `full`; a second day as a second scenario |
| [`03_timesteps_day_run.ipynb`](03_timesteps_day_run.ipynb) | a day of timesteps, one network walking it with a load flow at each step, checkpoints, and crossing midnight into the next scenario |

They build on each other but each one is self-contained: every notebook connects, writes what it needs and closes.

## Scenarios are days

Every call into the database names a **scenario**: the base grid model the data belongs to, in practice one day.
It is a required argument and never guessed, because a database is expected to hold many days side by side. Inside
a scenario a state is addressed by a **timestep** (a moment of that day) and a **version** (one study state of that
moment). Differences never cross scenarios: walking a network from one day to another reloads it.

## Running them

The notebooks need pypowsybl, JupyterLab and matplotlib:

```bash
python -m venv .venv && . .venv/bin/activate
pip install pypowsybl jupyterlab matplotlib
python -m ipykernel install --user --name pypowsybl-rdfdb
jupyter lab examples/notebooks
```

Pick the `pypowsybl-rdfdb` kernel - the notebooks are committed with the default `python3` kernelspec, so any
kernel that can import pypowsybl runs them. Without any further setup they use `memory:notebook`, an in-process
store that needs no server, which is enough for everything they show.

### Against a real database

Any SPARQL 1.1 endpoint works. A throwaway Apache Jena Fuseki is one line:

```bash
docker run --rm -p 3030:3030 -e ADMIN_PASSWORD=admin \
    -e ENABLE_DATA_WRITE=true -e ENABLE_UPDATE=true -e ENABLE_UPLOAD=true secoresearch/fuseki
export PYPOWSYBL_RDF_DB=http://localhost:3030/ds/sparql
export PYPOWSYBL_RDF_DB_USER=admin PYPOWSYBL_RDF_DB_PASSWORD=admin   # only if your server asks for them
```

Two details of that image, both verified: its dataset is read-only unless the three `ENABLE_` variables are set,
and it publishes its query endpoint as `/ds/sparql`, not `/ds/query` - hence the URL. Its admin password guards
the administration endpoints, not the data, so the credential variables are optional here and only matter on a
server that really asks.

Every notebook opens its connection with `notebook_utils.connect()`, which reads `PYPOWSYBL_RDF_DB` through
`database_url()` and applies `credentials()`; nothing else in the notebooks changes.

### All three at once

```bash
python examples/notebooks/run_all.py                       # in-process store
python examples/notebooks/run_all.py --fuseki              # starts a Fuseki subprocess from tests/fuseki_server.py
python examples/notebooks/run_all.py --output-dir /tmp/out # keep the executed copies
```

`run_all.py` executes every notebook with `nbconvert` and exits non-zero on the first cell that raises. The
notebooks in this folder are committed **without outputs**; the executed copies go to `--output-dir`.

## Files

* `notebook_utils.py` - the database URL, the data directory, the two scenario names, and `next_day_zip()`, which
  makes a second day out of `data/CGMES_Full.zip` by moving every `md:Model.scenarioTime` and giving the models new
  identifiers. Self-contained, so a checkout without the test suite still runs the notebooks.
* `run_all.py` - the runner described above.

## Where to read more

The user guide chapter *Loading CGMES through an RDF database* in the pypowsybl documentation covers the same
ground in prose, including the limitations - the linear version chain, one base day per scenario, and what a full
reload does to a `Network` object.
