/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.cgmes.rdfdb.Checkpoint;
import com.powsybl.cgmes.rdfdb.GraphInfo;
import com.powsybl.cgmes.rdfdb.RdfDatabase;
import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.cgmes.rdfdb.RdfDbException;
import com.powsybl.cgmes.rdfdb.RdfDbExport;
import com.powsybl.cgmes.rdfdb.RdfDbLoadOptions;
import com.powsybl.cgmes.rdfdb.RdfDbNetworkLoader;
import com.powsybl.cgmes.rdfdb.RdfDbProvenance;
import com.powsybl.cgmes.rdfdb.RdfDbUpdateOptions;
import com.powsybl.cgmes.rdfdb.RdfDbVariantLoadOptions;
import com.powsybl.cgmes.rdfdb.SnapshotCatalog;
import com.powsybl.cgmes.rdfdb.SnapshotInfo;
import com.powsybl.cgmes.rdfdb.SnapshotRef;
import com.powsybl.cgmes.rdfdb.StoredModel;
import com.powsybl.cgmes.rdfdb.Timesteps;
import com.powsybl.cgmes.rdfdb.UpdateResult;
import com.powsybl.cgmes.rdfdb.UpdateStatistics;
import com.powsybl.cgmes.rdfdb.VariantBinding;
import com.powsybl.cgmes.rdfdb.VariantLoadResult;
import com.powsybl.cgmes.rdfdb.VariantOutcome;
import com.powsybl.cgmes.rdfdb.VariantRequest;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.dataframe.DataframeMapper;
import com.powsybl.dataframe.DataframeMapperBuilder;
import com.powsybl.iidm.network.ImportConfig;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Everything the RDF database bindings do, expressed in plain Java so that it can be unit tested on the JVM.
 *
 * <p>The native entry points in {@link RdfDbCFunctions} cannot be tested outside a GraalVM image, so they are
 * one-liners over this class. What lives here is the translation between the flat, string-typed world of the C API
 * and the typed Java API of {@code powsybl-cgmes-rdfdb}: option maps become an {@link RdfDatabase}, subset names
 * become a {@link CgmesSubset} set, and a list of graphs becomes a dataframe.</p>
 *
 * <p><b>Addressing.</b> Every call names a <em>scenario</em>: the free-form name of the base grid model (typically a
 * day, {@code "2021-02-09"}) whose instance files live together in the database. It is required and never defaulted -
 * a database is expected to hold many days side by side, and silently picking one of them would be a trap. Inside a
 * scenario a state is addressed by a <em>version</em> label and a <em>timestep</em>; both may be left open, which
 * means "the newest version" and "the base timestep of the scenario". A scenario that holds no snapshot at all is
 * the unversioned shape of follow-up work package 1, and then neither may be given.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbUtil {

    private static final DataframeMapper<List<GraphInfo>, Void> GRAPHS_MAPPER =
            new DataframeMapperBuilder<List<GraphInfo>, GraphInfo, Void>()
                    .itemsProvider(graphs -> graphs)
                    .stringsIndex("name", GraphInfo::contextName)
                    .strings("subset", g -> g.subset().getIdentifier())
                    .strings("graph", GraphInfo::remoteGraph)
                    .build();

    private RdfDbUtil() {
    }

    /**
     * Open a connection to an RDF database.
     *
     * @param url     the database URL: {@code memory:<name>} for the in-process backend, otherwise the URL of a
     *                SPARQL 1.1 endpoint (a Fuseki dataset, an rdf4j-server or GraphDB repository)
     * @param options the connection options, see {@link RdfDatabase#fromParameters(String, Map)}
     * @return the open connection; the caller owns it and must close it
     */
    public static RdfDbConnection open(String url, Map<String, String> options) {
        Objects.requireNonNull(url);
        return RdfDbConnection.open(RdfDatabase.fromParameters(url, options));
    }

    /**
     * The scenarios a database holds data for.
     *
     * @param db the open connection
     * @return the scenario names, sorted
     */
    public static List<String> scenarios(RdfDbConnection db) {
        return db.scenarios();
    }

    /**
     * The named graphs of one scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario to list
     * @return one row per instance file
     */
    public static List<GraphInfo> graphs(RdfDbConnection db, String scenario) {
        return db.graphs(requireScenario(scenario));
    }

    /**
     * Drop every graph of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario to empty
     */
    public static void clear(RdfDbConnection db, String scenario) {
        db.clear(requireScenario(scenario));
    }

    /**
     * Read CGMES instance files into a scenario of the database.
     *
     * @param db         the open connection
     * @param scenario   the scenario to write into
     * @param ds         the data source holding the instance files
     * @param parameters the CGMES import parameters
     * @param reportNode where the reader reports, may be {@code null}
     * @return the names of the graphs that were written
     */
    public static List<String> loadCgmes(RdfDbConnection db, String scenario, ReadOnlyDataSource ds,
                                         Map<String, String> parameters, ReportNode reportNode) {
        CgmesTripleStoreLoader.Result result = db.loadCgmes(requireScenario(scenario), ds, null,
                toProperties(parameters), reportNode == null ? ReportNode.NO_OP : reportNode);
        return List.copyOf(result.contextNames());
    }

    /**
     * Build a network from the graphs of a scenario.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param parameters     the CGMES import parameters
     * @param postProcessors the import post processors to run on top of the ones the platform configures, empty for
     *                       exactly the configured ones
     * @param reportNode     where the load reports, may be {@code null}
     * @return the network
     */
    public static Network load(RdfDbConnection db, String scenario, Map<String, String> parameters,
                               List<String> postProcessors, ReportNode reportNode) {
        RdfDbLoadOptions options = new RdfDbLoadOptions();
        if (postProcessors != null && !postProcessors.isEmpty()) {
            // A file import adds the named post processors to the configured ones (ImportConfig.addPostProcessors);
            // RdfDbLoadOptions replaces them, so the two lists are merged here to keep the two paths identical.
            List<String> names = new ArrayList<>(ImportConfig.load().getPostProcessors());
            postProcessors.stream().filter(n -> !names.contains(n)).forEach(names::add);
            options.setPostProcessors(names);
        }
        return RdfDbNetworkLoader.load(db, requireScenario(scenario), options, NetworkFactory.findDefault(),
                toProperties(parameters), reportNode == null ? ReportNode.NO_OP : reportNode);
    }

    /**
     * Apply the graphs of a scenario to a network that is already in memory.
     *
     * @param network    the network to update in place
     * @param db         the open connection
     * @param scenario   the scenario holding the update data
     * @param subsets    the CGMES subsets to read ({@code "SSH"}, {@code "SV"}, ...), empty for the steady-state pair
     * @param parameters the CGMES import parameters
     * @param reportNode where the update reports, may be {@code null}
     * @return the route the update took. Follow-up WP5 adds {@code "noop"} and {@code "diff"}; in this work package
     *         the only route is a plain update, so this is always {@code "update"}
     */
    public static String update(Network network, RdfDbConnection db, String scenario, List<String> subsets,
                                Map<String, String> parameters, ReportNode reportNode) {
        RdfDbLoadOptions options = RdfDbLoadOptions.forUpdate();
        if (subsets != null && !subsets.isEmpty()) {
            options.setSubsets(toSubsets(subsets));
        }
        RdfDbNetworkLoader.update(network, db, requireScenario(scenario), options, toProperties(parameters),
                reportNode == null ? ReportNode.NO_OP : reportNode);
        return "update";
    }

    /**
     * @return the mapper turning the graph catalogue of a scenario into a dataframe
     */
    public static DataframeMapper<List<GraphInfo>, Void> graphsMapper() {
        return GRAPHS_MAPPER;
    }

    /**
     * Translate the CGMES subset identifiers a caller writes into the enum.
     *
     * @param names the identifiers, for instance {@code SSH} or {@code EQ_BD}; case is ignored
     * @return the subsets
     */
    static EnumSet<CgmesSubset> toSubsets(List<String> names) {
        EnumSet<CgmesSubset> subsets = EnumSet.noneOf(CgmesSubset.class);
        for (String name : names) {
            String identifier = name.trim().toUpperCase(Locale.ROOT);
            CgmesSubset subset = Arrays.stream(CgmesSubset.values())
                    .filter(s -> s != CgmesSubset.UNKNOWN && s.getIdentifier().equals(identifier))
                    .findFirst()
                    .orElseThrow(() -> new RdfDbException("Unknown CGMES subset '" + name + "', expected one of "
                            + Arrays.stream(CgmesSubset.values())
                                    .filter(s -> s != CgmesSubset.UNKNOWN)
                                    .map(CgmesSubset::getIdentifier)
                                    .toList()));
            subsets.add(subset);
        }
        return subsets;
    }

    /**
     * Turn the CGMES import parameters of the C API into the {@link Properties} the importers take.
     *
     * <p>The connection options travel in a map of their own and are read by
     * {@link RdfDatabase#fromParameters(String, Map)}; these are the import parameters, which the Python layer keeps
     * separate from them.</p>
     *
     * @param parameters the import parameters, may be {@code null}
     * @return the parameters as {@link Properties}
     */
    static Properties toProperties(Map<String, String> parameters) {
        Properties properties = new Properties();
        if (parameters != null) {
            properties.putAll(parameters);
        }
        return properties;
    }

    /**
     * Refuse a missing scenario before a call leaves this process.
     *
     * <p>Core checks the scenario again ({@code ScenarioGraphNames.requireValidScenario}); doing it here as well
     * means the error names the argument the caller passed rather than whatever the call chain turned it into, and
     * costs nothing. The message is what the Python and JVM tests match on.</p>
     *
     * @param scenario the scenario name a caller gave
     * @return the scenario, unchanged
     */
    private static String requireScenario(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            throw new RdfDbException("A scenario name is required and must not be blank");
        }
        return scenario;
    }

    // ------------------------------------------------------------------ follow-up WP5: versions and timesteps

    /** Key of the route an update took in the map {@link #updateInfo} returns. */
    public static final String ROUTE = "route";
    /** Key of the reasons the fast route was not taken. */
    public static final String REASONS = "reasons";
    /** Key of how many difference models were applied. */
    public static final String DIFF_COUNT = "diff_count";
    /** Key of how many statements were transferred. */
    public static final String STATEMENT_COUNT = "statement_count";
    /** Key of the IRI of the snapshot the network is at. */
    public static final String SNAPSHOT = "snapshot";
    /** Key of the scenario the network belongs to. */
    public static final String SCENARIO = "scenario";
    /** Key of the version label the network is at. */
    public static final String VERSION = "version";
    /** Key of the timestep the network is at. */
    public static final String TIMESTEP = "timestep";
    /** Key of the variant an update created or moved, empty when the update was not a variant operation. */
    public static final String VARIANT = "variant";

    private static final String NOOP = "noop";
    private static final String DIFF = "diff";
    private static final String FULL = "full";
    private static final String REFUSED = "refused";
    private static final String LEGACY_UPDATE = "update";
    private static final String MAX_DIFF_CHAIN = "max_diff_chain";

    /**
     * One row of {@code db.scenarios()} as Python sees it.
     *
     * @param scenario      the scenario name, as it was given at upload
     * @param baseTimestep  the canonical timestep of the scenario's root, empty for an unversioned scenario
     * @param versioned     whether the scenario holds snapshots
     * @param snapshotCount how many snapshots it holds
     */
    public record ScenarioRow(String scenario, String baseTimestep, boolean versioned, int snapshotCount) {
    }

    /**
     * What an update did, in a shape a C entry point can hand out as one handle.
     *
     * <p>The versioned route answers with an {@link UpdateResult}; the legacy profile-replacement route of
     * follow-up WP1 ({@code subsets=[...]}) answers with nothing at all, and is represented by its route name. Exactly
     * one of the two is set.</p>
     *
     * @param result      the result of the versioned route, {@code null} on the legacy route
     * @param legacyRoute the route name of the legacy route, {@code null} on the versioned route
     */
    public record UpdateOutcome(UpdateResult result, String legacyRoute) {
    }

    /**
     * Turn the empty string the C API uses for "not given" into {@code null}.
     *
     * @param text the value a caller gave
     * @return the value, or {@code null} when it was {@code null}, empty or blank
     */
    public static String timestepOrNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * Refuse a missing scenario.
     *
     * @param scenario the scenario name a caller gave
     * @return the scenario, unchanged
     */
    public static String checkScenario(String scenario) {
        return requireScenario(scenario);
    }

    /**
     * Whether a scenario holds snapshots, i.e. whether it can be addressed by version and timestep.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return whether the scenario is versioned
     */
    public static boolean isVersioned(RdfDbConnection db, String scenario) {
        return db.snapshots(requireScenario(scenario)).isVersioned();
    }

    /**
     * One row per scenario the database holds.
     *
     * @param db the open connection
     * @return the rows, sorted by scenario name
     */
    public static List<ScenarioRow> scenarioRows(RdfDbConnection db) {
        List<ScenarioRow> rows = new ArrayList<>();
        for (String scenario : db.scenarios()) {
            SnapshotCatalog catalog = db.snapshots(scenario);
            List<SnapshotInfo> snapshots = catalog.snapshots();
            String base = snapshots.isEmpty() ? "" : catalog.baseTimestep();
            rows.add(new ScenarioRow(scenario, base, !snapshots.isEmpty(), snapshots.size()));
        }
        rows.sort(Comparator.comparing(ScenarioRow::scenario));
        return rows;
    }

    /**
     * The snapshots of a scenario, empty for a scenario the database does not hold.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return one row per snapshot
     */
    public static List<SnapshotInfo> snapshots(RdfDbConnection db, String scenario) {
        return db.snapshots(requireScenario(scenario)).snapshots();
    }

    /**
     * The version chain of one timestep of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param timestep the timestep text, {@code null} for the base timestep
     * @return the snapshots of that timestep, oldest first; empty when the scenario holds none
     */
    public static List<SnapshotInfo> versions(RdfDbConnection db, String scenario, String timestep) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        // versions() resolves the timestep against the scenario's base day, which an empty scenario does not have
        return catalog.isVersioned() ? catalog.versions(timestep) : List.of();
    }

    /**
     * The timesteps of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return one row per timestep, oldest first
     */
    public static List<SnapshotCatalog.TimestepInfo> timesteps(RdfDbConnection db, String scenario) {
        return db.snapshots(requireScenario(scenario)).timesteps();
    }

    /**
     * The stored models of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return one row per stored model
     */
    public static List<StoredModel> models(RdfDbConnection db, String scenario) {
        return db.catalog(requireScenario(scenario)).models();
    }

    /**
     * Build a network from a scenario, at a snapshot when one is addressed.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param version        the version label, {@code null} for the newest one
     * @param timestep       the timestep text, {@code null} for the base timestep
     * @param parameters     the CGMES import parameters
     * @param postProcessors the import post processors, only supported on the unversioned route
     * @param reportNode     where the load reports, may be {@code null}
     * @return the network
     */
    public static Network load(RdfDbConnection db, String scenario, String version, String timestep,
                               Map<String, String> parameters, List<String> postProcessors, ReportNode reportNode) {
        requireScenario(scenario);
        String step = timestepOrNull(timestep);
        String label = timestepOrNull(version);
        if (label == null && step == null) {
            // "The scenario" without an address: the unversioned graphs, or the newest snapshot of a versioned
            // scenario. The loader decides which, and it is the only form that takes load options - so it is also
            // the only form that can honour post processors
            return load(db, scenario, parameters, postProcessors, reportNode);
        }
        if (postProcessors != null && !postProcessors.isEmpty()) {
            throw new PowsyblException("post_processors are not supported when loading one addressed snapshot;"
                    + " the snapshot entry points of cgmes-rdfdb take no load options. Load the scenario without a"
                    + " version and a timestep, or run the post processors yourself");
        }
        return RdfDbNetworkLoader.load(db, scenario, label, step, NetworkFactory.findDefault(),
                toProperties(parameters), reportNode == null ? ReportNode.NO_OP : reportNode);
    }

    /**
     * Bring a network to a snapshot, or replace its profiles from the unversioned graphs of a scenario.
     *
     * @param network    the network to bring up to date
     * @param db         the open connection
     * @param scenario   the scenario holding the target
     * @param version    the version label, {@code null} for the newest one
     * @param timestep   the timestep text, {@code null} for the base timestep
     * @param subsets    the CGMES subsets of the legacy profile-replacement route, empty for the versioned route
     * @param options    the update options: {@code max_diff_chain}, and {@code variant} to create or update one
     *                   variant of the network instead of its working state
     * @param parameters the CGMES import parameters
     * @param reportNode where the update reports, may be {@code null}
     * @return what was done
     */
    public static UpdateOutcome update(Network network, RdfDbConnection db, String scenario, String version,
                                       String timestep, List<String> subsets, Map<String, String> options,
                                       Map<String, String> parameters, ReportNode reportNode) {
        requireScenario(scenario);
        String targetVariant = options == null ? null : timestepOrNull(options.get(VARIANT));
        if (subsets != null && !subsets.isEmpty()) {
            if (targetVariant != null) {
                throw new PowsyblException("'" + VARIANT + "' addresses a snapshot as one variant of the network,"
                        + " and the profile replacement of an un-versioned scenario addresses no snapshot;"
                        + " the two cannot be combined");
            }
            update(network, db, scenario, subsets, parameters, reportNode);
            return new UpdateOutcome(null, LEGACY_UPDATE);
        }
        String step = timestepOrNull(timestep);
        String label = timestepOrNull(version);
        SnapshotCatalog catalog = db.snapshots(scenario);
        if (!catalog.isVersioned() && label == null && step == null) {
            if (targetVariant != null) {
                throw new PowsyblException("scenario '" + scenario + "' holds no snapshot, so there is nothing for"
                        + " variant '" + targetVariant + "' to stand for; store a root snapshot first");
            }
            // An unversioned scenario has no snapshot to address; the profile replacement is what "update" means there
            update(network, db, scenario, List.of(), parameters, reportNode);
            return new UpdateOutcome(null, LEGACY_UPDATE);
        }
        RdfDbUpdateOptions updateOptions = new RdfDbUpdateOptions();
        updateOptions.setAllowFullReload(true);
        updateOptions.setTargetVariant(targetVariant);
        String chain = options == null ? null : options.get(MAX_DIFF_CHAIN);
        if (chain != null && !chain.isBlank()) {
            try {
                updateOptions.setMaxDiffChain(Integer.parseInt(chain.trim()));
            } catch (NumberFormatException e) {
                // The Python layer validates this, but a caller of the C API directly would otherwise get a
                // NumberFormatException where every other bad argument of this class is a PowsyblException
                throw new PowsyblException("'" + MAX_DIFF_CHAIN + "' must be a whole number, got '" + chain + "'", e);
            }
        }
        UpdateResult result = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(label, step, catalog),
                updateOptions, toProperties(parameters), reportNode == null ? ReportNode.NO_OP : reportNode);
        if (result.isReplacement()) {
            // The replacement network is a fresh object built by the materialiser; the threading mode is a property
            // of the handle its owner asked for, so it is carried over rather than silently reset
            result.network().getVariantManager().allowVariantMultiThreadAccess(
                    network.getVariantManager().isVariantMultiThreadAccessAllowed());
        }
        return new UpdateOutcome(result, null);
    }

    /**
     * Describe an update for the Python layer.
     *
     * @param outcome what the update did
     * @return the route and, on the versioned route, the reasons, the counters and where the network now is
     */
    public static Map<String, String> updateInfo(UpdateOutcome outcome) {
        Map<String, String> info = new LinkedHashMap<>();
        if (outcome.legacyRoute() != null) {
            info.put(ROUTE, outcome.legacyRoute());
            return info;
        }
        UpdateResult result = outcome.result();
        info.put(ROUTE, routeName(result.route()));
        info.put(REASONS, String.join("; ", result.reasons()));
        info.put(VARIANT, text(result.variantId()));
        info.put(DIFF_COUNT, Integer.toString(result.diffCount()));
        UpdateStatistics statistics = result.statistics();
        info.put(STATEMENT_COUNT, Long.toString(statistics.statementCount()));
        info.put("plan_ms", millis(statistics.plan()));
        info.put("fetch_ms", millis(statistics.fetch()));
        info.put("compose_ms", millis(statistics.compose()));
        info.put("apply_ms", millis(statistics.apply()));
        info.putAll(identityOf(result.network()));
        return info;
    }

    /**
     * The replacement network of a full reload.
     *
     * @param outcome what the update did
     * @return the new network
     */
    public static Network replacement(UpdateOutcome outcome) {
        if (outcome.result() == null || !outcome.result().isReplacement()) {
            String route = outcome.legacyRoute() != null ? outcome.legacyRoute() : routeName(outcome.result().route());
            throw new PowsyblException("no replacement network: route was " + route);
        }
        return outcome.result().network();
    }

    /**
     * Read CGMES instance files into a scenario, unversioned or as its root snapshot.
     *
     * <p>Without a version this is the unversioned upload of follow-up WP1, which core refuses on a scenario that
     * already holds snapshots. With one, the files become the <em>root</em> snapshot of an empty scenario, or -
     * when the scenario already has a root - one further snapshot: the parent state is materialised, the new files
     * are compared against it and the difference is written ({@code SnapshotCatalog.putAsDiff}). That is how a day
     * of timesteps exported by a TSO reaches the database without anyone recording anything.</p>
     *
     * @param db         the open connection
     * @param ds         the data source holding the instance files
     * @param scenario   the scenario to write into
     * @param version    the version label of the snapshot, {@code null} for an unversioned upload
     * @param timestep   the timestep the files describe as an instant or an {@code "8:30"} label of the scenario's
     *                   base day; {@code null} for the base timestep, which for a root is taken from the steady
     *                   state file
     * @param parameters the CGMES import parameters
     * @param reportNode where the reader reports, may be {@code null}
     * @return the graph names of an unversioned upload, the stored model ids of a snapshot
     */
    public static List<String> loadCgmes(RdfDbConnection db, ReadOnlyDataSource ds, String scenario, String version,
                                         String timestep, Map<String, String> parameters, ReportNode reportNode) {
        requireScenario(scenario);
        String label = timestepOrNull(version);
        String step = timestepOrNull(timestep);
        if (label == null) {
            if (step != null) {
                throw new PowsyblException("a timestep addresses a snapshot, so it needs a version: pass"
                        + " version='1.0' to store the root of scenario '" + scenario + "'");
            }
            return loadCgmes(db, scenario, ds, parameters, reportNode);
        }
        SnapshotCatalog catalog = db.snapshots(scenario);
        Properties props = toProperties(parameters);
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        SnapshotInfo written = catalog.isVersioned()
                // A label is a wall time of this scenario's own base day, so only the catalogue can resolve it
                ? catalog.putAsDiff(ds, null, SnapshotRef.of(label, step, catalog), props, rn)
                : catalog.putFull(ds, null, SnapshotRef.of(scenario, label, step), props, rn);
        return List.copyOf(written.members());
    }

    /**
     * Store the changes a recorder holds as a new snapshot of a scenario.
     *
     * @param recording the recorder holding the changes
     * @param db        the open connection
     * @param scenario  the base scenario the difference is made against
     * @param version   the version label of the new snapshot, {@code null} for the next label of the timestep
     * @param timestep  the timestep of the new snapshot, {@code null} for the base timestep
     * @param options   the export options, see {@link NetworkEventRecording#diffOptions}, plus {@code variant} to
     *                  write the changes of one variant as the successor of <em>that variant's</em> snapshot
     * @return the stored model ids
     */
    public static List<String> exportRecording(NetworkEventRecording recording, RdfDbConnection db, String scenario,
                                               String version, String timestep, Map<String, String> options) {
        requireScenario(scenario);
        Map<String, String> rest = databaseDecidedFree(options);
        String variant = timestepOrNull(rest.remove(VARIANT));
        CgmesDiffExport.ExportOptions exportOptions = NetworkEventRecording.diffOptions(rest);
        if (variant != null) {
            if (timestepOrNull(timestep) != null) {
                throw new PowsyblException("a variant export writes the successor of the snapshot variant '"
                        + variant + "' stands for, so its timestep is that variant's own; drop the timestep");
            }
            Network network = recording.getNetwork();
            if (!network.getVariantManager().getVariantIds().contains(variant)) {
                throw new PowsyblException("network " + network.getId() + " has no variant '" + variant + "'; it has "
                        + network.getVariantManager().getVariantIds());
            }
            checkSameScenario(network, scenario);
            return recording.exportWithSnapshot(events ->
                    RdfDbExport.exportVariant(recording.getNetwork(), events, db, variant, timestepOrNull(version),
                                    exportOptions, ReportNode.NO_OP)
                            .stored().stream()
                            .map(StoredModel::id)
                            .toList());
        }
        // The label-taking form: the timestep is resolved against the base day of this very scenario
        return recording.exportWithSnapshot(events ->
                RdfDbExport.export(recording.getNetwork(), events, db, scenario, timestepOrNull(version),
                                timestepOrNull(timestep), exportOptions, ReportNode.NO_OP)
                        .stored().stream()
                        .map(StoredModel::id)
                        .toList());
    }

    /**
     * One row of the table {@code to_rdf_updates(..., per_variant=True)} answers with.
     *
     * @param variant       the variant the changes were recorded on
     * @param snapshot      the IRI of the snapshot they became, empty when nothing was written
     * @param version       the version label of that snapshot
     * @param timestep      the timestep of that snapshot
     * @param models        the stored model ids, {@code ;} joined
     * @param exportedEvent how many recorded changes reached the database
     * @param rejected      the changes that did not, {@code ; } joined
     */
    public record VariantExportRow(String variant, String snapshot, String version, String timestep, String models,
                                   int exportedEvent, String rejected) {
    }

    /**
     * Store the changes recorded on every variant, each as the successor of its own snapshot.
     *
     * <p>Two phases in core: everything that can refuse runs first with nothing written, so a rejected change
     * under {@code unsupported='raise'} leaves the database untouched.</p>
     *
     * @param recording the recorder holding the changes
     * @param db        the open connection
     * @param scenario  the scenario the variants belong to
     * @param version   the version label every new snapshot gets, {@code null} for the next label of each
     *                  timestep's chain
     * @param options   the export options, see {@link NetworkEventRecording#diffOptions}
     * @return one row per variant the changes were recorded on, in first-occurrence order
     */
    public static List<VariantExportRow> exportRecordingPerVariant(NetworkEventRecording recording,
                                                                   RdfDbConnection db, String scenario,
                                                                   String version, Map<String, String> options) {
        requireScenario(scenario);
        Map<String, String> rest = databaseDecidedFree(options);
        if (rest.containsKey(VARIANT)) {
            throw new PowsyblException("a per-variant export writes every variant the changes were recorded on,"
                    + " so it takes no '" + VARIANT + "' option");
        }
        CgmesDiffExport.ExportOptions exportOptions = NetworkEventRecording.diffOptions(rest);
        checkSameScenario(recording.getNetwork(), scenario);
        Map<String, RdfDbExport.VariantExport> exports = recording.exportWithSnapshot(events ->
                RdfDbExport.exportPerVariant(recording.getNetwork(), events, db, timestepOrNull(version),
                        exportOptions, ReportNode.NO_OP));
        List<VariantExportRow> rows = new ArrayList<>();
        exports.forEach((variantId, export) -> {
            RdfDbExport.SnapshotResult result = export.result();
            SnapshotInfo snapshot = result == null ? null : result.snapshot();
            rows.add(new VariantExportRow(variantId,
                    snapshot == null ? "" : snapshot.iri(),
                    snapshot == null ? "" : snapshot.version(),
                    snapshot == null ? "" : snapshot.timestep(),
                    result == null ? "" : result.stored().stream().map(StoredModel::id)
                            .collect(java.util.stream.Collectors.joining(";")),
                    result == null ? 0 : result.exportedEvents().size(),
                    String.join("; ", export.rejected())));
        });
        return rows;
    }

    // ------------------------------------------------------------------ step 12: snapshots as network variants

    /**
     * Load many snapshots of one scenario as the variants of a single network.
     *
     * <p>Three parallel arrays, because that is what crosses the native boundary cheaply. An empty variant
     * identifier lets the naming rule of core decide (the timestep label, or {@code version@label} when two
     * requests share a label); an empty version is the newest one of that timestep; an empty timestep is the base
     * timestep of the scenario.</p>
     *
     * <p>A snapshot that cannot be reached inside a variant does not fail the load: its variant is not created and
     * the refusal is left on the network, where {@link #variantRows} shows it as a {@code refused} row.</p>
     *
     * @param db                            the open connection
     * @param scenario                      the scenario, required
     * @param variantIds                    the variant identifiers, {@code ""} for the naming rule
     * @param versions                      the version labels, {@code ""} for the newest one
     * @param timesteps                     the timesteps, {@code ""} for the base timestep
     * @param parameters                    the CGMES import parameters
     * @param reportNode                    where the load reports, may be {@code null}
     * @param allowVariantMultiThreadAccess whether the network may be read from several threads afterwards
     * @return the network, with one variant per request that was not refused
     */
    public static Network loadVariants(RdfDbConnection db, String scenario, List<String> variantIds,
                                       List<String> versions, List<String> timesteps,
                                       Map<String, String> parameters, ReportNode reportNode,
                                       boolean allowVariantMultiThreadAccess) {
        requireScenario(scenario);
        Objects.requireNonNull(timesteps);
        if (timesteps.isEmpty()) {
            throw new PowsyblException("at least one snapshot is needed to load a network as variants");
        }
        if (variantIds.size() != timesteps.size() || versions.size() != timesteps.size()) {
            throw new PowsyblException("the variant identifiers, the versions and the timesteps must be as many:"
                    + " got " + variantIds.size() + ", " + versions.size() + " and " + timesteps.size());
        }
        SnapshotCatalog catalog = db.snapshots(scenario);
        List<VariantRequest> requests = new ArrayList<>();
        for (int i = 0; i < timesteps.size(); i++) {
            SnapshotRef ref = SnapshotRef.of(timestepOrNull(versions.get(i)), timestepOrNull(timesteps.get(i)),
                    catalog);
            requests.add(new VariantRequest(timestepOrNull(variantIds.get(i)), ref));
        }
        RdfDbVariantLoadOptions options = new RdfDbVariantLoadOptions()
                .setAllowVariantMultiThreadAccess(allowVariantMultiThreadAccess);
        VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, scenario, requests, options,
                NetworkFactory.findDefault(), toProperties(parameters),
                reportNode == null ? ReportNode.NO_OP : reportNode);
        return result.network();
    }

    /**
     * One row of {@code network.variants_binding()}.
     *
     * @param variant    the identifier of the IIDM variant
     * @param scenario   the scenario the snapshot belongs to
     * @param snapshot   the IRI of the snapshot the variant stands for
     * @param version    the version label of that snapshot
     * @param timestep   the timestep of that snapshot
     * @param label      the {@code HH:MM} label of the timestep
     * @param clonedFrom the variant this one was cloned from
     * @param eq         the stored equipment model the variant is at
     * @param ssh        the stored steady state hypothesis model the variant is at
     * @param caseDate   the case date of the variant
     * @param status     {@code primary}, {@code bound}, {@code unbound} or {@code refused}
     * @param reasons    why a refused snapshot could not be reached
     */
    public record VariantRow(String variant, String scenario, String snapshot, String version, String timestep,
                             String label, String clonedFrom, String eq, String ssh, String caseDate, String status,
                             String reasons) {
    }

    /**
     * What every variant of a network stands for.
     *
     * <p>Four kinds of row, and the status column says which: the {@code primary} variant, whose identity is the
     * network-level one; the {@code bound} variants of a day loaded as variants; a {@code unbound} variant, which
     * is one a user cloned from a variant that stood for nothing (or created before the network knew a database);
     * and a {@code refused} row per snapshot the last operation could not reach, which is not a variant of the
     * network at all but is the only place its reasons survive.</p>
     *
     * @param network the network
     * @return one row per variant, primary first, plus one per refusal of the last operation
     */
    public static List<VariantRow> variantRows(Network network) {
        Objects.requireNonNull(network);
        List<VariantRow> rows = new ArrayList<>();
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        List<String> variantIds = List.copyOf(network.getVariantManager().getVariantIds());
        if (provenance == null) {
            for (String variantId : variantIds) {
                rows.add(unboundRow(variantId));
            }
            return rows;
        }
        Map<String, VariantBinding> bindings = provenance.variantBindings();
        provenance.variantBinding(RdfDbProvenance.PRIMARY_VARIANT)
                .ifPresent(binding -> rows.add(boundRow(binding, "primary")));
        bindings.forEach((variantId, binding) -> rows.add(boundRow(binding, "bound")));
        for (String variantId : variantIds) {
            if (!bindings.containsKey(variantId) && !RdfDbProvenance.PRIMARY_VARIANT.equals(variantId)) {
                rows.add(unboundRow(variantId));
            }
        }
        for (VariantOutcome outcome : provenance.lastRefused()) {
            SnapshotRef ref = outcome.requested();
            String reasons = String.join("; ", outcome.reasons());
            int existing = indexOf(rows, outcome.variantId());
            if (existing >= 0) {
                // The refusal named a variant that exists - it was asked to move and stayed where it was. A second
                // row under the same identifier would make the index of the dataframe non-unique, so the reasons go
                // onto the row that variant already has and its status keeps saying what it still stands for
                rows.set(existing, withReasons(rows.get(existing), reasons));
            } else {
                rows.add(new VariantRow(outcome.variantId(), ref == null ? "" : ref.scenario(),
                        text(outcome.snapshotIri()), ref == null ? "" : text(ref.version()),
                        ref == null ? "" : text(ref.timestep()), "", text(outcome.clonedFrom()), "", "", "",
                        "refused", reasons));
            }
        }
        return rows;
    }

    /**
     * @return the mapper turning the variant bindings of a network into a dataframe
     */
    public static DataframeMapper<List<VariantRow>, Void> variantsMapper() {
        return VARIANTS_MAPPER;
    }

    /**
     * @return the mapper turning a per-variant export into a dataframe
     */
    public static DataframeMapper<List<VariantExportRow>, Void> variantExportMapper() {
        return VARIANT_EXPORT_MAPPER;
    }

    private static int indexOf(List<VariantRow> rows, String variantId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).variant().equals(variantId)) {
                return i;
            }
        }
        return -1;
    }

    private static VariantRow withReasons(VariantRow row, String reasons) {
        return new VariantRow(row.variant(), row.scenario(), row.snapshot(), row.version(), row.timestep(),
                row.label(), row.clonedFrom(), row.eq(), row.ssh(), row.caseDate(), row.status(), reasons);
    }

    private static VariantRow unboundRow(String variantId) {
        return new VariantRow(variantId, "", "", "", "", "", "", "", "", "", "unbound", "");
    }

    private static VariantRow boundRow(VariantBinding binding, String status) {
        String timestep = text(binding.timestep());
        return new VariantRow(binding.variantId(), text(binding.scenario()), text(binding.snapshotIri()),
                text(binding.version()), timestep, labelOf(timestep, binding), text(binding.clonedFrom()),
                text(binding.modelIds().get(CgmesSubset.EQUIPMENT)),
                text(binding.modelIds().get(CgmesSubset.STEADY_STATE_HYPOTHESIS)),
                binding.caseDate() == null ? "" : binding.caseDate().toString(), status, "");
    }

    /**
     * The {@code HH:MM} a timestep shows as.
     *
     * <p>A label is a wall time of the scenario's own day, so it needs the offset that day is written in. The
     * catalogue holds it, but this call takes no connection; the case date of the very snapshot carries the same
     * offset, and is used instead. A binding without a case date gets no label rather than a wrong one.</p>
     */
    private static String labelOf(String timestep, VariantBinding binding) {
        if (timestep.isEmpty() || binding.caseDate() == null) {
            return "";
        }
        try {
            return Timesteps.label(timestep, Timesteps.offsetOf(binding.caseDate()));
        } catch (RuntimeException e) {
            // A timestep that is not a canonical instant has no label; it is not worth failing a table for
            return "";
        }
    }

    /** The export options a caller may not set, because the address decides them. */
    private static Map<String, String> databaseDecidedFree(Map<String, String> options) {
        Map<String, String> rest = new LinkedHashMap<>(options == null ? Map.of() : options);
        for (String decided : DATABASE_DECIDED_OPTIONS) {
            if (rest.containsKey(decided)) {
                throw new PowsyblException("Export option '" + decided + "' is decided by the database (scenario,"
                        + " snapshot version and timestep)");
            }
        }
        return rest;
    }

    /** A variant export derives its target from the binding, so the scenario the caller names has to be the same. */
    private static void checkSameScenario(Network network, String scenario) {
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance != null && !scenario.equals(provenance.scenario())) {
            throw new PowsyblException("network " + network.getId() + " belongs to scenario '"
                    + provenance.scenario() + "', not to '" + scenario + "'; a difference never crosses scenarios");
        }
    }

    /**
     * Where a network stands: which scenario, which snapshot, which stored model per profile.
     *
     * @param network  the network
     * @param db       the open connection, may be {@code null} to read the provenance extension only
     * @param scenario the scenario to resolve the network in, required when {@code db} is given
     * @return the identity, empty when the network has none
     */
    public static Map<String, String> identity(Network network, RdfDbConnection db, String scenario) {
        Map<String, String> identity = identityOf(network);
        if (db != null) {
            requireScenario(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);
            catalog.snapshotOf(network).ifPresent(info -> putSnapshot(identity, info));
        }
        return identity;
    }

    /**
     * Where one variant of a network stands.
     *
     * <p>The network-level identity always describes the primary variant; a bound variant's identity is swapped in
     * for the duration of the read by {@link RdfDbProvenance#inVariant}, so that exactly the same code answers for
     * a variant as for the network. That is also why the answer for a variant carries the same keys.</p>
     *
     * @param network  the network
     * @param db       the open connection, may be {@code null} to read the provenance extension only
     * @param scenario the scenario to resolve the network in, required when {@code db} is given
     * @param variant  the variant to describe, {@code null} for the network itself (its primary variant)
     * @return the identity, empty when the network has none
     */
    public static Map<String, String> identity(Network network, RdfDbConnection db, String scenario,
                                               String variant) {
        String variantId = timestepOrNull(variant);
        if (variantId == null) {
            return identity(network, db, scenario);
        }
        if (!network.getVariantManager().getVariantIds().contains(variantId)) {
            throw new PowsyblException("network " + network.getId() + " has no variant '" + variantId + "'; it has "
                    + network.getVariantManager().getVariantIds());
        }
        if (!RdfDbProvenance.PRIMARY_VARIANT.equals(variantId)) {
            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            if (provenance == null || provenance.variantBinding(variantId).isEmpty()) {
                throw new PowsyblException("variant '" + variantId + "' of network " + network.getId()
                        + " is not bound to a snapshot, so it has no database identity of its own");
            }
        }
        return RdfDbProvenance.inVariant(network, variantId, () -> identity(network, db, scenario));
    }

    /**
     * Materialise a snapshot as a full state, so that loading it needs no chain walk.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param version  the version label, {@code null} for the newest one
     * @param timestep the timestep text, {@code null} for the base timestep
     * @return the IRI of the snapshot that was materialised
     */
    public static String checkpoint(RdfDbConnection db, String scenario, String version, String timestep) {
        requireScenario(scenario);
        SnapshotCatalog catalog = db.snapshots(scenario);
        return Checkpoint.create(db,
                SnapshotRef.of(timestepOrNull(version), timestepOrNull(timestep), catalog)).iri();
    }

    /**
     * @return the mapper turning the scenario list into a dataframe
     */
    public static DataframeMapper<List<ScenarioRow>, Void> scenariosMapper() {
        return SCENARIOS_MAPPER;
    }

    /**
     * @return the mapper turning a snapshot list into a dataframe
     */
    public static DataframeMapper<List<SnapshotInfo>, Void> snapshotsMapper() {
        return SNAPSHOTS_MAPPER;
    }

    /**
     * @return the mapper turning a timestep list into a dataframe
     */
    public static DataframeMapper<List<SnapshotCatalog.TimestepInfo>, Void> timestepsMapper() {
        return TIMESTEPS_MAPPER;
    }

    /**
     * @return the mapper turning a stored model list into a dataframe
     */
    public static DataframeMapper<List<StoredModel>, Void> modelsMapper() {
        return MODELS_MAPPER;
    }

    private static final List<String> DATABASE_DECIDED_OPTIONS = List.of(NetworkEventRecording.VERSION,
            NetworkEventRecording.SCENARIO_TIME, NetworkEventRecording.SUPERSEDES, NetworkEventRecording.DEPENDS_ON);

    private static final DataframeMapper<List<ScenarioRow>, Void> SCENARIOS_MAPPER =
            new DataframeMapperBuilder<List<ScenarioRow>, ScenarioRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("scenario", ScenarioRow::scenario)
                    .strings("base_timestep", ScenarioRow::baseTimestep)
                    .booleans("versioned", ScenarioRow::versioned)
                    .ints("snapshot_count", ScenarioRow::snapshotCount)
                    .build();

    private static final DataframeMapper<List<SnapshotInfo>, Void> SNAPSHOTS_MAPPER =
            new DataframeMapperBuilder<List<SnapshotInfo>, SnapshotInfo, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("snapshot", SnapshotInfo::iri)
                    .strings("scenario", SnapshotInfo::scenario)
                    .strings("version", SnapshotInfo::version)
                    .strings("timestep", SnapshotInfo::timestep)
                    .strings("timestep_label", i -> text(i.timestepLabel()))
                    .strings("kind", i -> i.kind().name().toLowerCase(Locale.ROOT))
                    .strings("parent", i -> text(i.parent()))
                    .strings("edge", RdfDbUtil::edgeName)
                    .ints("depth", SnapshotInfo::depth)
                    .booleans("has_full", SnapshotInfo::hasFull)
                    .booleans("fast", SnapshotInfo::fast)
                    .strings("members", i -> String.join(";", i.members()))
                    .strings("created", i -> i.created() == null ? "" : i.created().toString())
                    .strings("description", i -> text(i.description()))
                    .build();

    private static final DataframeMapper<List<SnapshotCatalog.TimestepInfo>, Void> TIMESTEPS_MAPPER =
            new DataframeMapperBuilder<List<SnapshotCatalog.TimestepInfo>, SnapshotCatalog.TimestepInfo, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("timestep", SnapshotCatalog.TimestepInfo::timestep)
                    .strings("scenario", SnapshotCatalog.TimestepInfo::scenario)
                    .strings("label", t -> text(t.label()))
                    .strings("root", t -> text(t.root()))
                    .strings("head", t -> text(t.head()))
                    .ints("version_count", SnapshotCatalog.TimestepInfo::versionCount)
                    .strings("pinned_base", t -> text(t.pinnedBase()))
                    .build();

    private static final DataframeMapper<List<StoredModel>, Void> MODELS_MAPPER =
            new DataframeMapperBuilder<List<StoredModel>, StoredModel, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("id", StoredModel::id)
                    .strings("scenario", StoredModel::scenario)
                    .strings("subset", m -> m.subset().getIdentifier())
                    .strings("kind", m -> m.kind().name().toLowerCase(Locale.ROOT))
                    .ints("version", StoredModel::version)
                    .strings("supersedes", m -> String.join(";", m.supersedes()))
                    .strings("depends_on", m -> String.join(";", m.dependentOn()))
                    .booleans("fast", StoredModel::fastPredicatesOnly)
                    .ints("triple_count", m -> (int) m.tripleCount())
                    .ints("chain_depth", StoredModel::chainDepth)
                    .strings("created", m -> m.created() == null ? "" : m.created().toString())
                    .build();

    private static final DataframeMapper<List<VariantRow>, Void> VARIANTS_MAPPER =
            new DataframeMapperBuilder<List<VariantRow>, VariantRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("variant", VariantRow::variant)
                    .strings("scenario", VariantRow::scenario)
                    .strings("snapshot", VariantRow::snapshot)
                    .strings("version", VariantRow::version)
                    .strings("timestep", VariantRow::timestep)
                    .strings("label", VariantRow::label)
                    .strings("cloned_from", VariantRow::clonedFrom)
                    .strings("eq", VariantRow::eq)
                    .strings("ssh", VariantRow::ssh)
                    .strings("case_date", VariantRow::caseDate)
                    .strings("status", VariantRow::status)
                    .strings("reasons", VariantRow::reasons)
                    .build();

    private static final DataframeMapper<List<VariantExportRow>, Void> VARIANT_EXPORT_MAPPER =
            new DataframeMapperBuilder<List<VariantExportRow>, VariantExportRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("variant", VariantExportRow::variant)
                    .strings("snapshot", VariantExportRow::snapshot)
                    .strings("version", VariantExportRow::version)
                    .strings("timestep", VariantExportRow::timestep)
                    .strings("models", VariantExportRow::models)
                    .ints("exported_events", VariantExportRow::exportedEvent)
                    .strings("rejected", VariantExportRow::rejected)
                    .build();

    private static String routeName(UpdateResult.Route route) {
        return switch (route) {
            case NOOP -> NOOP;
            case DIFF_APPLIED -> DIFF;
            case FULL_RELOAD -> FULL;
            case VARIANT_REFUSED -> REFUSED;
            case FULL_REQUIRED -> throw new IllegalStateException(
                    "a full reload was refused although it was allowed; this is a bug in the binding layer");
        };
    }

    private static String millis(java.time.Duration duration) {
        return duration == null ? "0" : Long.toString(duration.toMillis());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String edgeName(SnapshotInfo info) {
        return info.edge() == null || info.edge() == SnapshotInfo.EdgeKind.NONE
                ? "" : info.edge().name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> identityOf(Network network) {
        Map<String, String> identity = new LinkedHashMap<>();
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance != null) {
            identity.put(SCENARIO, provenance.scenario());
            provenance.snapshot().ifPresent(iri -> identity.put(SNAPSHOT, iri));
            provenance.modelIds().forEach((subset, id) -> identity.put(subset.getIdentifier(), id));
        }
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        if (models != null) {
            models.getModels().forEach(model ->
                    identity.putIfAbsent(model.getSubset().getIdentifier(), model.getId()));
        }
        return identity;
    }

    private static void putSnapshot(Map<String, String> identity, SnapshotInfo info) {
        identity.put(SNAPSHOT, info.iri());
        identity.put(SCENARIO, info.scenario());
        identity.put(VERSION, info.version());
        identity.put(TIMESTEP, info.timestep());
        if (info.timestepLabel() != null) {
            identity.put("label", info.timestepLabel());
        }
    }
}
