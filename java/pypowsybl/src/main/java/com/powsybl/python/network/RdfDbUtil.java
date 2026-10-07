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
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.cgmes.rdfdb.Checkpoint;
import com.powsybl.cgmes.rdfdb.GraphInfo;
import com.powsybl.cgmes.rdfdb.Profiles;
import com.powsybl.cgmes.rdfdb.RdfDatabase;
import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.cgmes.rdfdb.RdfDbException;
import com.powsybl.cgmes.rdfdb.RdfDbExport;
import com.powsybl.cgmes.rdfdb.RdfDbLoadOptions;
import com.powsybl.cgmes.rdfdb.RdfDbNames;
import com.powsybl.cgmes.rdfdb.RdfDbNetworkLoader;
import com.powsybl.cgmes.rdfdb.RdfDbProvenance;
import com.powsybl.cgmes.rdfdb.RdfDbUpdateOptions;
import com.powsybl.cgmes.rdfdb.RdfDbVariantLoadOptions;
import com.powsybl.cgmes.rdfdb.SnapshotCatalog;
import com.powsybl.cgmes.rdfdb.SnapshotInfo;
import com.powsybl.cgmes.rdfdb.SnapshotRef;
import com.powsybl.cgmes.rdfdb.StoredModel;
import com.powsybl.cgmes.rdfdb.UpdateResult;
import com.powsybl.cgmes.rdfdb.UpdateStatistics;
import com.powsybl.cgmes.rdfdb.VariantBinding;
import com.powsybl.cgmes.rdfdb.VariantLoadResult;
import com.powsybl.cgmes.rdfdb.VariantOutcome;
import com.powsybl.cgmes.rdfdb.VariantRequest;
import com.powsybl.cgmes.rdfdb.VersionRegistry;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.dataframe.DataframeMapper;
import com.powsybl.dataframe.DataframeMapperBuilder;
import com.powsybl.iidm.network.ImportConfig;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.eclipse.rdf4j.model.Statement;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the RDF database bindings do, expressed in plain Java so that it can be unit tested on the JVM.
 *
 * <p>The native entry points in {@link RdfDbCFunctions} cannot be tested outside a GraalVM image, so they are
 * one-liners over this class. What lives here is the translation between the flat, string-typed world of the C API
 * and the typed Java API of {@code powsybl-cgmes-rdfdb}: option maps become an {@link RdfDatabase}, profile names
 * become a checked set of names, and a list of graphs becomes a dataframe.</p>
 *
 * <p><b>Addressing.</b> Every call names a <em>scenario</em>: the free-form name of the base grid model (typically a
 * day, {@code "2021-02-09"}) whose instance files live together in the database. It is required and never defaulted -
 * a database is expected to hold many days side by side, and silently picking one of them would be a trap. Inside a
 * scenario a snapshot is addressed by a <em>modelling authority</em> (the {@code md:Model.modelingAuthoritySet} of
 * its files), a <em>timestamp</em> (an ISO-8601 instant on the C API) and a <em>version</em>, a name the version
 * registry of the scenario ranks; each may be left open, which means "the only modelling authority of the
 * scenario", "the base timestamp of its tree" and "the newest version" respectively. A named version on a read means
 * the highest ranking version at or below it, unless the read is <em>exact</em>. Core requires the authority on every read; resolving an open one when the scenario
 * holds exactly one is the convenience of these bindings ({@link #authority}), and so is resolving it for a recorder
 * export or a checkpoint into such a scenario ({@link #onlyAuthority}); core resolves it for files, whose headers it
 * reads. The <em>profiles</em> are not part of
 * the address: they select which CGMES profiles a call reads or writes. A scenario that holds no snapshot at all is
 * <em>unversioned</em>, and then nothing may be addressed in it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbUtil {

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
    /** Key of the modelling authority of the snapshot the network is at. */
    public static final String MODELLING_AUTHORITY = "modelling_authority";
    /** Key of the timestamp (ISO-8601 instant) of the snapshot the network is at. */
    public static final String TIMESTAMP = "timestamp";
    /** Key of the version of the snapshot the network is at. */
    public static final String VERSION = "version";
    /** Column of the rank the version registry gives a version. */
    public static final String RANK = "rank";
    /** Key of the variant an update created or moved, empty when the update was not a variant operation. */
    public static final String VARIANT = "variant";

    private static final String NOOP = "noop";
    private static final String DIFF = "diff";
    private static final String FULL = "full";
    private static final String REFUSED = "refused";
    private static final String LEGACY_UPDATE = "update";
    private static final String MAX_DIFF_CHAIN = "max_diff_chain";

    private static final DataframeMapper<List<GraphInfo>, Void> GRAPHS_MAPPER =
            new DataframeMapperBuilder<List<GraphInfo>, GraphInfo, Void>()
                    .itemsProvider(graphs -> graphs)
                    .stringsIndex("name", GraphInfo::contextName)
                    .strings("subset", g -> text(g.profile()))
                    .strings("graph", GraphInfo::remoteGraph)
                    .build();

    private static final List<String> DATABASE_DECIDED_OPTIONS = List.of(NetworkEventRecording.VERSION,
            NetworkEventRecording.SCENARIO_TIME, NetworkEventRecording.SUPERSEDES, NetworkEventRecording.DEPENDS_ON);

    private static final DataframeMapper<List<ScenarioRow>, Void> SCENARIOS_MAPPER =
            new DataframeMapperBuilder<List<ScenarioRow>, ScenarioRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("scenario", ScenarioRow::scenario)
                    .strings("modelling_authorities", ScenarioRow::modellingAuthorities)
                    .booleans("versioned", ScenarioRow::versioned)
                    .ints("snapshot_count", ScenarioRow::snapshotCount)
                    .strings("archive_cutoff", ScenarioRow::archiveCutoff)
                    .strings("archive_location", ScenarioRow::archiveLocation)
                    .build();

    private static final DataframeMapper<List<SnapshotInfo>, Void> SNAPSHOTS_MAPPER =
            new DataframeMapperBuilder<List<SnapshotInfo>, SnapshotInfo, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("snapshot", SnapshotInfo::iri)
                    .strings("scenario", SnapshotInfo::scenario)
                    .strings(MODELLING_AUTHORITY, SnapshotInfo::modellingAuthority)
                    .strings(TIMESTAMP, i -> i.timestamp().toString())
                    .strings(VERSION, SnapshotInfo::version)
                    .ints(RANK, SnapshotInfo::rank)
                    .strings("profiles", i -> profileNames(i.profiles()))
                    .strings("kind", i -> i.kind().name().toLowerCase(Locale.ROOT))
                    .strings("parent", i -> text(i.parent()))
                    .strings("edge", RdfDbUtil::edgeName)
                    .ints("depth", SnapshotInfo::depth)
                    .booleans("has_full", SnapshotInfo::hasFull)
                    .booleans("fast", SnapshotInfo::fast)
                    .booleans("rollover", SnapshotInfo::rollover)
                    .strings("members", i -> String.join(";", i.members()))
                    .strings("created", i -> i.created() == null ? "" : i.created().toString())
                    .strings("description", i -> text(i.description()))
                    .build();

    private static final DataframeMapper<List<SnapshotCatalog.TimestampInfo>, Void> TIMESTAMPS_MAPPER =
            new DataframeMapperBuilder<List<SnapshotCatalog.TimestampInfo>, SnapshotCatalog.TimestampInfo, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex(TIMESTAMP, t -> t.timestamp().toString())
                    .strings(MODELLING_AUTHORITY, SnapshotCatalog.TimestampInfo::modellingAuthority)
                    .strings("root", t -> text(t.root()))
                    .strings("head", t -> text(t.head()))
                    .ints("version_count", SnapshotCatalog.TimestampInfo::versionCount)
                    .strings("pin", t -> text(t.pin()))
                    .build();

    private static final DataframeMapper<List<AssemblyRow>, Void> ASSEMBLY_MAPPER =
            new DataframeMapperBuilder<List<AssemblyRow>, AssemblyRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex(MODELLING_AUTHORITY, AssemblyRow::modellingAuthority)
                    .strings("snapshot", r -> r.snapshot() == null ? "" : r.snapshot().iri())
                    .strings(TIMESTAMP, r -> r.snapshot() == null ? "" : r.snapshot().timestamp().toString())
                    .strings(VERSION, r -> r.snapshot() == null ? "" : r.snapshot().version())
                    .ints(RANK, r -> r.snapshot() == null ? -1 : r.snapshot().rank())
                    .strings("profiles", r -> r.snapshot() == null ? "" : profileNames(r.snapshot().profiles()))
                    .strings("kind", r -> r.snapshot() == null ? ""
                            : r.snapshot().kind().name().toLowerCase(Locale.ROOT))
                    .ints("depth", r -> r.snapshot() == null ? -1 : r.snapshot().depth())
                    .booleans("has_full", r -> r.snapshot() != null && r.snapshot().hasFull())
                    .booleans("fast", r -> r.snapshot() != null && r.snapshot().fast())
                    .strings("members", r -> r.snapshot() == null ? "" : String.join(";", r.snapshot().members()))
                    .build();

    private static final DataframeMapper<List<StoredModel>, Void> MODELS_MAPPER =
            new DataframeMapperBuilder<List<StoredModel>, StoredModel, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("id", StoredModel::id)
                    .strings("scenario", StoredModel::scenario)
                    .strings("subset", StoredModel::subset)
                    .strings("kind", m -> m.kind().name().toLowerCase(Locale.ROOT))
                    .ints("version", StoredModel::version)
                    .strings("supersedes", m -> String.join(";", m.supersedes()))
                    .strings("depends_on", m -> String.join(";", m.dependentOn()))
                    .booleans("fast", StoredModel::fastPredicatesOnly)
                    .strings("capabilities", m -> text(m.capabilities()))
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
                    .strings(MODELLING_AUTHORITY, VariantRow::modellingAuthority)
                    .strings(TIMESTAMP, VariantRow::timestamp)
                    .strings(VERSION, VariantRow::version)
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
                    .strings(MODELLING_AUTHORITY, VariantExportRow::modellingAuthority)
                    .strings(TIMESTAMP, VariantExportRow::timestamp)
                    .strings(VERSION, VariantExportRow::version)
                    .ints(RANK, VariantExportRow::rank)
                    .strings("models", VariantExportRow::models)
                    .ints("exported_events", VariantExportRow::exportedEvent)
                    .strings("rejected", VariantExportRow::rejected)
                    .build();

    private static final DataframeMapper<List<RegistryRow>, Void> REGISTRY_MAPPER =
            new DataframeMapperBuilder<List<RegistryRow>, RegistryRow, Void>()
                    .itemsProvider(rows -> rows)
                    .stringsIndex("name", RegistryRow::name)
                    .ints(RANK, RegistryRow::rank)
                    .booleans("transient", RegistryRow::isTransient)
                    .build();

    private static final DataframeMapper<List<ChangeRow>, Void> CHANGES_MAPPER =
            new DataframeMapperBuilder<List<ChangeRow>, ChangeRow, Void>()
                    .itemsProvider(rows -> rows)
                    .intsIndex("index", ChangeRow::index)
                    .strings("profile", ChangeRow::profile)
                    .strings("subject", ChangeRow::subject)
                    .strings("property", ChangeRow::property)
                    .strings("value", ChangeRow::value)
                    .strings("side", ChangeRow::side)
                    .build();

    private static final DataframeMapper<List<StatementRow>, Void> STATEMENTS_MAPPER =
            new DataframeMapperBuilder<List<StatementRow>, StatementRow, Void>()
                    .itemsProvider(rows -> rows)
                    .intsIndex("index", StatementRow::index)
                    .strings("subject", StatementRow::subject)
                    .strings("predicate", StatementRow::predicate)
                    .strings("object", StatementRow::object)
                    .booleans("is_iri", StatementRow::isIri)
                    .build();

    private RdfDbUtil() {
    }

    /**
     * The address of a pin as it crosses the C API: version, timestamp and modelling authority, each {@code null}
     * when not given. The class exists so that "no pin" ({@code null}) and "the newest version of the base
     * timestamp" (a pin with three {@code null}s) stay apart.
     *
     * @param version            the version name, {@code null} for the newest one
     * @param timestamp          the timestamp, {@code null} for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} for the target's
     */
    public record PinArgs(String version, String timestamp, String modellingAuthority) {
    }

    /** The pin as a {@link SnapshotRef}, its authority defaulting to the target's, or the only one of the scenario. */
    private static SnapshotRef pin(SnapshotCatalog catalog, PinArgs pin, String targetAuthority) {
        if (pin == null) {
            return null;
        }
        String authority = blankToNull(pin.modellingAuthority());
        return SnapshotRef.of(catalog.scenario(), authority != null ? authority
                : authority(catalog, targetAuthority), toInstant(pin.timestamp()), blankToNull(pin.version()));
    }

    /**
     * Flag a snapshot as a rollover - later timestamps ingested from files hang off it by default - and checkpoint
     * it.
     *
     * @return the IRI of the snapshot
     */
    public static String rollover(RdfDbConnection db, String scenario, String version, boolean exact,
                                  String timestamp, String modellingAuthority) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        return catalog.rollover(ref(scenario, authority(catalog, modellingAuthority), toInstant(timestamp), version,
                exact)).iri();
    }

    /**
     * Drop one timestamp of a tree with every version of it; refused for the base timestamp and for a timestamp
     * another one is pinned to.
     *
     * @return the IRIs of the dropped snapshots, oldest first
     */
    public static List<String> dropTimestamp(RdfDbConnection db, String scenario, String timestamp,
                                             String modellingAuthority) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        Instant moment = toInstant(timestamp);
        if (moment == null) {
            throw new PowsyblException("drop_timestamp needs a timestamp: the base timestamp of a tree is never"
                    + " dropped");
        }
        return catalog.dropTimestamp(authority(catalog, modellingAuthority), moment).stream()
                .map(SnapshotInfo::iri).toList();
    }

    /**
     * One statement of {@code db.changes_between(...)}.
     *
     * @param index    the position of the statement in the answer
     * @param profile  the profile of the difference it belongs to
     * @param subject  the mRID of the object
     * @param property the property, {@code Class.attribute}
     * @param value    the value: a literal, or the mRID or IRI an association points at
     * @param side     {@code forward} (holds at the second snapshot) or {@code reverse} (held at the first)
     */
    public record ChangeRow(int index, String profile, String subject, String property, String value, String side) {
    }

    /**
     * The changes that lead from one snapshot of a tree to another, composed into one difference per profile.
     *
     * @return one row per statement, forward statements first per profile
     */
    public static List<ChangeRow> changesBetween(RdfDbConnection db, String scenario, String modellingAuthority,
                                                 String fromVersion, String fromTimestamp, String toVersion,
                                                 String toTimestamp) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        String authority = authority(catalog, modellingAuthority);
        DifferenceModelSet set = RdfDbNetworkLoader.changesBetween(db,
                SnapshotRef.of(scenario, authority, toInstant(fromTimestamp), fromVersion),
                SnapshotRef.of(scenario, authority, toInstant(toTimestamp), toVersion));
        List<ChangeRow> rows = new ArrayList<>();
        set.models().forEach((subset, model) -> {
            String profile = Profiles.of(subset);
            model.forward().forEach(st -> rows.add(new ChangeRow(rows.size(), profile, st.subjectId(),
                    st.property(), st.value(), "forward")));
            model.reverse().forEach(st -> rows.add(new ChangeRow(rows.size(), profile, st.subjectId(),
                    st.property(), st.value(), "reverse")));
        });
        return rows;
    }

    /**
     * @return the mapper turning the changes between two snapshots into a dataframe
     */
    public static DataframeMapper<List<ChangeRow>, Void> changesMapper() {
        return CHANGES_MAPPER;
    }

    /**
     * One row of {@code db.registry(scenario).dataframe()}: a registered version name.
     *
     * @param name        the version name
     * @param rank        its rank; versions are compared by rank, never by name
     * @param isTransient whether deleting the name drops the snapshots that carry it
     */
    public record RegistryRow(String name, int rank, boolean isTransient) {
    }

    /**
     * The version registry of a scenario, lowest rank first; empty for a scenario that has none yet.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return one row per registered name
     */
    public static List<RegistryRow> registry(RdfDbConnection db, String scenario) {
        VersionRegistry registry = db.snapshots(requireScenario(scenario)).registry();
        return registry.ranks().entrySet().stream()
                .map(e -> new RegistryRow(e.getKey(), e.getValue(), registry.isTransient(e.getKey())))
                .toList();
    }

    /**
     * Edit the version registry of a scenario, or only read what it says about itself.
     *
     * <p>One entry point for every edit keeps the C surface small: {@code op} names the {@link VersionRegistry}
     * method, the other arguments are what it takes. Every edit is guarded by the registry's revision in core, so a
     * registry edited elsewhere in between refuses the edit rather than overwriting it.</p>
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param op       {@code refresh} (read only), {@code create} ({@code names}, {@code flag} = permissive),
     *                 {@code add} ({@code name}), {@code insert} ({@code name}, {@code other} = the name it follows,
     *                 {@code null} for before the first), {@code rerank} ({@code names} with {@code ranks}),
     *                 {@code rename} ({@code name} to {@code other}), {@code delete} ({@code name}) or
     *                 {@code mark_transient} ({@code name}, {@code flag})
     * @param name     the version name the operation is about
     * @param other    the second name of {@code insert} and {@code rename}
     * @param names    the names of {@code create} and {@code rerank}
     * @param ranks    the new ranks of {@code rerank}, parallel to {@code names}
     * @param flag     permissive for {@code create}, transient for {@code mark_transient}
     * @return {@code rank} (of the name an {@code add} or {@code insert} registered, else empty), {@code permissive}
     *         and {@code rev} after the operation
     */
    public static Map<String, String> editRegistry(RdfDbConnection db, String scenario, String op, String name,
                                                   String other, List<String> names, List<Integer> ranks,
                                                   boolean flag) {
        VersionRegistry registry = db.snapshots(requireScenario(scenario)).registry();
        Integer rank = null;
        switch (Objects.requireNonNull(op)) {
            case "refresh" -> registry.refresh();
            case "create" -> registry.create(names, flag);
            case "add" -> rank = registry.add(requireName(name));
            case "insert" -> rank = registry.insert(requireName(name), blankToNull(other));
            case "rerank" -> {
                if (names.size() != ranks.size()) {
                    throw new PowsyblException("rerank takes one rank per name: got " + names.size() + " names and "
                            + ranks.size() + " ranks");
                }
                Map<String, Integer> newRanks = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) {
                    newRanks.put(names.get(i), ranks.get(i));
                }
                registry.rerank(newRanks);
            }
            case "rename" -> registry.rename(requireName(name), requireName(other));
            case "delete" -> registry.delete(requireName(name));
            case "mark_transient" -> registry.markTransient(requireName(name), flag);
            default -> throw new PowsyblException("unknown version registry operation '" + op + "'");
        }
        Map<String, String> info = new LinkedHashMap<>();
        info.put(RANK, rank == null ? "" : Integer.toString(rank));
        info.put("permissive", Boolean.toString(registry.isPermissive()));
        info.put("rev", Long.toString(registry.rev()));
        return info;
    }

    /**
     * One statement of a graph, as {@code db.fetch_profile(...)} answers it.
     *
     * @param index     the position of the statement in the answer
     * @param subject   the subject, an IRI or a blank node label
     * @param predicate the predicate IRI
     * @param object    the object: an IRI, or the lexical form of a literal
     * @param isIri     whether the object is an IRI
     */
    public record StatementRow(int index, String subject, String predicate, String object, boolean isIri) {
    }

    /**
     * The profiles of a snapshot that are stored as one whole graph, with that graph: every custom profile, and a
     * standard one while it is still at its instance file.
     *
     * @param db                 the open connection
     * @param scenario           the scenario
     * @param version            the version name, {@code null} for the newest one
     * @param exact              whether the read means exactly {@code version}
     * @param timestamp          the timestamp, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario
     * @return profile name to graph IRI, the standard profiles first
     */
    public static Map<String, String> profiles(RdfDbConnection db, String scenario, String version, boolean exact,
                                               String timestamp, String modellingAuthority) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        return new LinkedHashMap<>(catalog.graphsOf(ref(scenario, authority(catalog, modellingAuthority),
                toInstant(timestamp), version, exact)));
    }

    /**
     * Every statement of one stored graph, typically a custom profile the conversion never reads.
     *
     * @param db       the open connection
     * @param scenario the scenario the graph belongs to
     * @param graphIri the graph, as {@link #profiles} names it
     * @return one row per statement
     */
    public static List<StatementRow> fetchGraph(RdfDbConnection db, String scenario, String graphIri) {
        if (graphIri == null || graphIri.isBlank()) {
            throw new PowsyblException("a graph IRI is required: take it from db.profiles(...)");
        }
        List<Statement> statements = db.fetchGraph(requireScenario(scenario), graphIri);
        List<StatementRow> rows = new ArrayList<>(statements.size());
        for (Statement statement : statements) {
            rows.add(new StatementRow(rows.size(), statement.getSubject().stringValue(),
                    statement.getPredicate().stringValue(), statement.getObject().stringValue(),
                    statement.getObject().isIRI()));
        }
        return rows;
    }

    /**
     * @return the mapper turning the statements of a graph into a dataframe
     */
    public static DataframeMapper<List<StatementRow>, Void> statementsMapper() {
        return STATEMENTS_MAPPER;
    }

    /**
     * @return the mapper turning a version registry into a dataframe
     */
    public static DataframeMapper<List<RegistryRow>, Void> registryMapper() {
        return REGISTRY_MAPPER;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new PowsyblException("a version name must not be blank");
        }
        return name;
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
                toProperties(parameters), orNoOp(reportNode));
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
                toProperties(parameters), orNoOp(reportNode));
    }

    /**
     * Apply the graphs of a scenario to a network that is already in memory.
     *
     * @param network    the network to update in place
     * @param db         the open connection
     * @param scenario   the scenario holding the update data
     * @param profiles   the CGMES profiles to read ({@code "SSH"}, {@code "SV"}, ...), empty for the steady-state
     *                   pair
     * @param parameters the CGMES import parameters
     * @param reportNode where the update reports, may be {@code null}
     * @return the route name of this legacy profile replacement, always {@code "update"}; the versioned routes are
     *         taken by the other overload
     */
    public static String update(Network network, RdfDbConnection db, String scenario, List<String> profiles,
                                Map<String, String> parameters, ReportNode reportNode) {
        RdfDbLoadOptions options = RdfDbLoadOptions.forUpdate();
        Set<String> projection = toProfiles(profiles);
        if (projection != null) {
            options.setProfiles(projection);
        }
        RdfDbNetworkLoader.update(network, db, requireScenario(scenario), options, toProperties(parameters),
                orNoOp(reportNode));
        return "update";
    }

    /**
     * @return the mapper turning the graph catalogue of a scenario into a dataframe
     */
    public static DataframeMapper<List<GraphInfo>, Void> graphsMapper() {
        return GRAPHS_MAPPER;
    }

    /**
     * Check the profile names a caller writes.
     *
     * <p>A profile is a name: one of the nine the CGMES conversion reads ({@link Profiles#STANDARD}) or a custom one
     * an application stores next to them ({@code OP}), named {@code [A-Z][A-Z0-9_]*}.</p>
     *
     * @param names the names, for instance {@code SSH}, {@code EQ_BD} or {@code OP}; surrounding blanks and case
     *              are ignored
     * @return the profiles, or {@code null} when none was named (the default of the call)
     * @throws RdfDbException if a name is not of the shape of a profile name
     */
    static Set<String> toProfiles(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        Set<String> profiles = new LinkedHashSet<>();
        for (String name : names) {
            profiles.add(Profiles.check(name.trim().toUpperCase(Locale.ROOT)));
        }
        return profiles;
    }

    /**
     * The address of a snapshot, exact on request.
     *
     * @param scenario  the scenario
     * @param authority the modelling authority
     * @param moment    the timestamp, {@code null} for the base timestamp
     * @param version   the version name, {@code null} for the head
     * @param exact     whether a read means exactly that version rather than the highest ranking one at or below it
     * @return the address
     * @throws RdfDbException if an exact address names no version
     */
    static SnapshotRef ref(String scenario, String authority, Instant moment, String version, boolean exact) {
        SnapshotRef ref = SnapshotRef.of(scenario, authority, moment, version);
        return exact ? ref.exactly() : ref;
    }

    /**
     * Read the timestamp text of the C API.
     *
     * @param text an ISO-8601 date-time with an offset ({@code 2021-02-09T20:30:00Z}), or {@code null} or empty for
     *             "not given"
     * @return the instant, or {@code null}
     */
    static Instant toInstant(String text) {
        String value = blankToNull(text);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new PowsyblException("'" + text + "' is not a timestamp: expected an ISO-8601 date-time with an"
                    + " offset, for instance 2021-02-09T20:30:00Z", e);
        }
    }

    /**
     * The modelling authority a read is addressed to.
     *
     * <p>Core requires one on every read. Most scenarios hold the tree of a single authority, so an open one is
     * resolved here when there is exactly one to choose from - one request - and refused with the list otherwise.</p>
     *
     * @param catalog   the snapshots of the scenario
     * @param authority the authority the caller named, {@code null} or blank for none
     * @return the authority to address
     * @throws PowsyblException if none was named and the scenario holds none or several
     */
    static String authority(SnapshotCatalog catalog, String authority) {
        String resolved = onlyAuthority(catalog, authority);
        if (resolved != null) {
            return resolved;
        }
        List<String> all = catalog.modellingAuthorities();
        throw new PowsyblException("scenario '" + catalog.scenario() + "' holds "
                + (all.isEmpty() ? "no snapshot" : "the trees of the modelling authorities " + all)
                + ", so the modelling authority cannot be left open: name one of them");
    }

    /**
     * The modelling authority a write is addressed to: the named one, else the only tree of the scenario, else none.
     *
     * <p>A write of recorded changes or a checkpoint into a scenario of one tree goes into that tree, as a read does -
     * whatever the network's provenance says. Where there is no single tree - the first root of a scenario, or a
     * scenario of several - the open authority is passed on, and core takes it from the network. Files are not
     * resolved here: core applies the same rule to them and also reads their headers (see {@link #loadCgmes}).</p>
     *
     * @param catalog   the snapshots of the scenario
     * @param authority the authority the caller named, {@code null} or blank for none
     * @return the authority to address, {@code null} when none was named and the scenario holds none or several
     */
    static String onlyAuthority(SnapshotCatalog catalog, String authority) {
        String named = blankToNull(authority);
        if (named != null) {
            return named;
        }
        List<String> all = catalog.modellingAuthorities();
        return all.size() == 1 ? all.get(0) : null;
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

    /**
     * One row of {@code db.scenarios()} as Python sees it.
     *
     * @param scenario      the scenario name, as it was given at upload
     * @param modellingAuthorities the modelling authorities the scenario holds a tree of, {@code ;} joined, empty for
     *                             an unversioned scenario
     * @param versioned     whether the scenario holds snapshots
     * @param snapshotCount how many snapshots it holds
     * @param archiveCutoff the archive cutoff (ISO-8601 instant), empty when none is set
     * @param archiveLocation where the states before the cutoff were archived, empty when no cutoff is set
     */
    public record ScenarioRow(String scenario, String modellingAuthorities, boolean versioned, int snapshotCount,
                              String archiveCutoff, String archiveLocation) {
    }

    /**
     * What an update did, in a shape a C entry point can hand out as one handle.
     *
     * <p>The versioned route answers with an {@link UpdateResult}; the legacy profile-replacement route of an
     * unversioned scenario answers with nothing at all, and is represented by its route name. Exactly
     * one of the two is set.</p>
     *
     * @param result      the result of the versioned route, {@code null} on the legacy route
     * @param legacyRoute the route name of the legacy route, {@code null} on the versioned route
     */
    public record UpdateOutcome(UpdateResult result, String legacyRoute) {
    }

    /**
     * Whether a scenario holds snapshots, i.e. whether it can be addressed by modelling authority, timestamp and
     * version.
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
            String authorities = snapshots.isEmpty() ? "" : String.join(";", catalog.modellingAuthorities());
            rows.add(new ScenarioRow(scenario, authorities, !snapshots.isEmpty(), snapshots.size(),
                    catalog.archiveCutoff().map(Instant::toString).orElse(""), catalog.archiveLocation().orElse("")));
        }
        rows.sort(Comparator.comparing(ScenarioRow::scenario));
        return rows;
    }

    /**
     * Set the archive cutoff of a scenario, or clear it: a read of a snapshot whose timestamp is before the cutoff is
     * refused with a text naming where the states were archived. Listings still show those snapshots.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param cutoff   the cutoff, an ISO-8601 instant; {@code null} or empty, together with the location, clears it
     * @param location where the states before the cutoff were archived
     */
    public static void setArchiveCutoff(RdfDbConnection db, String scenario, String cutoff, String location) {
        db.snapshots(requireScenario(scenario)).setArchiveCutoff(toInstant(cutoff), blankToNull(location));
    }

    /**
     * The archive cutoff of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return {@code cutoff} (an ISO-8601 instant) and {@code location}, both empty when none is set
     */
    public static Map<String, String> archiveCutoff(RdfDbConnection db, String scenario) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("cutoff", catalog.archiveCutoff().map(Instant::toString).orElse(""));
        answer.put("location", catalog.archiveLocation().orElse(""));
        return answer;
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
     * The version chain of one timestamp of a scenario.
     *
     * @param db                 the open connection
     * @param scenario           the scenario
     * @param timestamp          the timestamp, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one
     * @return the snapshots of that timestamp, oldest first; empty when the scenario holds none
     */
    public static List<SnapshotInfo> versions(RdfDbConnection db, String scenario, String timestamp,
                                              String modellingAuthority) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        // an empty scenario has no tree, and so no base timestamp to default to
        return catalog.isVersioned() ? catalog.versions(authority(catalog, modellingAuthority), toInstant(timestamp))
                : List.of();
    }

    /**
     * The timestamps of one modelling authority's tree in a scenario.
     *
     * @param db                 the open connection
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one
     * @return one row per timestamp, oldest first; empty when the scenario holds no snapshot
     */
    public static List<SnapshotCatalog.TimestampInfo> timestamps(RdfDbConnection db, String scenario,
                                                                  String modellingAuthority) {
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        return catalog.isVersioned() ? catalog.timestamps(authority(catalog, modellingAuthority)) : List.of();
    }

    /**
     * The modelling authorities a scenario holds a snapshot tree of.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @return the authorities, sorted; empty for an unversioned or unknown scenario
     */
    public static List<String> modellingAuthorities(RdfDbConnection db, String scenario) {
        return db.snapshots(requireScenario(scenario)).modellingAuthorities();
    }

    /**
     * One row of {@code db.assembly(...)}: a modelling authority of the scenario and its snapshot at the moment.
     *
     * @param modellingAuthority the authority
     * @param snapshot           its snapshot at that moment, {@code null} when it has none there
     */
    public record AssemblyRow(String modellingAuthority, SnapshotInfo snapshot) {
    }

    /**
     * Every modelling authority of a scenario at one moment: what a CGM is assembled from.
     *
     * <p>Core answers only the authorities that have a snapshot at that moment; the table answers every authority
     * of the scenario, so that a missing one is a visible row with no snapshot rather than an absent one.</p>
     *
     * @param db        the open connection
     * @param scenario  the scenario
     * @param timestamp the moment, required
     * @param version   the version every authority is taken at - the highest ranking one at or below it of each -,
     *                  {@code null} for the head of each
     * @return one row per authority of the scenario, sorted by authority
     */
    public static List<AssemblyRow> assembly(RdfDbConnection db, String scenario, String timestamp,
                                             String version) {
        Instant moment = toInstant(timestamp);
        if (moment == null) {
            throw new PowsyblException("an assembly is the state of every modelling authority at one moment, so it"
                    + " needs a timestamp");
        }
        SnapshotCatalog catalog = db.snapshots(requireScenario(scenario));
        Map<String, SnapshotInfo> present = catalog.assembly(moment, version);
        return catalog.modellingAuthorities().stream()
                .map(authority -> new AssemblyRow(authority, present.get(authority)))
                .toList();
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
     * @param db                 the open connection
     * @param scenario           the scenario to read
     * @param version            the version name, {@code null} for the newest one; otherwise the highest ranking
     *                           version at or below it
     * @param exact              whether the read means exactly {@code version}
     * @param timestamp          the timestamp, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario
     * @param profiles           the profiles to load, empty for every profile of the snapshot
     * @param parameters         the CGMES import parameters
     * @param postProcessors     the import post processors, only supported when nothing is addressed
     * @param reportNode         where the load reports, may be {@code null}
     * @return the network
     */
    public static Network load(RdfDbConnection db, String scenario, String version, boolean exact, String timestamp,
                               String modellingAuthority, List<String> profiles, Map<String, String> parameters,
                               List<String> postProcessors, ReportNode reportNode) {
        requireScenario(scenario);
        Instant moment = toInstant(timestamp);
        Set<String> projection = toProfiles(profiles);
        if (version == null && !exact && moment == null && blankToNull(modellingAuthority) == null
                && projection == null) {
            // "The scenario" without an address: the unversioned graphs, or the newest snapshot of a versioned
            // scenario of one modelling authority. The loader decides which, and it is the only form that takes
            // load options - so it is also the only form that can honour post processors
            return load(db, scenario, parameters, postProcessors, reportNode);
        }
        if (postProcessors != null && !postProcessors.isEmpty()) {
            throw new PowsyblException("post_processors are not supported when loading one addressed snapshot;"
                    + " the snapshot entry points of cgmes-rdfdb take no load options. Load the scenario without an"
                    + " address and without profiles, or run the post processors yourself");
        }
        SnapshotRef ref = ref(scenario, authority(db.snapshots(scenario), modellingAuthority), moment, version, exact);
        return RdfDbNetworkLoader.load(db, ref, projection, NetworkFactory.findDefault(), toProperties(parameters),
                orNoOp(reportNode));
    }

    /**
     * Bring a network to a snapshot, or replace its profiles from the unversioned graphs of a scenario.
     *
     * <p>On a versioned scenario the network is brought to the addressed snapshot; the profiles are then the
     * projection the update looks at ({@link RdfDbUpdateOptions#setProfiles}). On an unversioned scenario, with
     * nothing addressed, the profiles are replaced from the scenario's graphs instead (the legacy route).</p>
     *
     * @param network            the network to bring up to date
     * @param db                 the open connection
     * @param scenario           the scenario holding the target
     * @param version            the version name, {@code null} for the newest one; otherwise the highest ranking
     *                           version at or below it
     * @param exact              whether the update means exactly {@code version}
     * @param timestamp          the timestamp, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario
     * @param profiles           the profiles the update looks at, empty for the default (equipment and steady state
     *                           hypothesis)
     * @param options            the update options: {@code max_diff_chain}, and {@code variant} to create or update
     *                           one variant of the network instead of its working state
     * @param parameters         the CGMES import parameters
     * @param reportNode         where the update reports, may be {@code null}
     * @return what was done
     */
    public static UpdateOutcome update(Network network, RdfDbConnection db, String scenario, String version,
                                       boolean exact, String timestamp, String modellingAuthority,
                                       List<String> profiles, Map<String, String> options,
                                       Map<String, String> parameters, ReportNode reportNode) {
        requireScenario(scenario);
        String targetVariant = options == null ? null : blankToNull(options.get(VARIANT));
        Instant moment = toInstant(timestamp);
        Set<String> projection = toProfiles(profiles);
        SnapshotCatalog catalog = db.snapshots(scenario);
        if (!catalog.isVersioned() && version == null && !exact && moment == null
                && blankToNull(modellingAuthority) == null) {
            if (targetVariant != null) {
                throw new PowsyblException("scenario '" + scenario + "' holds no snapshot, so there is nothing for"
                        + " variant '" + targetVariant + "' to stand for; store a root snapshot first");
            }
            // An unversioned scenario has no snapshot to address; the profile replacement is what "update" means there
            update(network, db, scenario, profiles, parameters, reportNode);
            return new UpdateOutcome(null, LEGACY_UPDATE);
        }
        RdfDbUpdateOptions updateOptions = new RdfDbUpdateOptions();
        if (projection != null) {
            updateOptions.setProfiles(projection);
        }
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
        SnapshotRef target = ref(scenario, authority(catalog, modellingAuthority), moment, version, exact);
        UpdateResult result = RdfDbNetworkLoader.update(network, db, target, updateOptions, toProperties(parameters),
                orNoOp(reportNode));
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
     * Read CGMES instance files into a scenario, unversioned or as a snapshot.
     *
     * <p>With nothing addressed this is the unversioned upload, which core refuses on a scenario that already holds
     * snapshots. With a version, a timestamp or a modelling authority, the files become the <em>root</em> snapshot of
     * their modelling authority's tree when it has none yet, or - when it has one - one further snapshot: the parent
     * state is materialised, the new files are compared against it and the difference is written
     * ({@code SnapshotCatalog.putAsDiff}). That is how a day of timestamps exported by a TSO reaches the database
     * without anyone recording anything.</p>
     *
     * <p>The tree is the named one, or - when none is named - the only one of the scenario, which core resolves
     * while it reads the files' headers: whatever they state, unless their equipment and steady state hypothesis
     * agree on <em>another</em> authority - another TSO's files, refused with "pass Y in the address to store them
     * under it, or X to open a second tree". Adding the tree of a second authority to a scenario therefore names it;
     * only the first root of a scenario, or a further snapshot of a scenario of several trees, takes the authority
     * from the files.</p>
     *
     * @param db                 the open connection
     * @param ds                 the data source holding the instance files
     * @param scenario           the scenario to write into
     * @param version            the version name of the snapshot; {@code null} for the lowest registered name
     *                           ranking above the head's (in a permissive registry the next number it lacks, "1"
     *                           for the root of a scenario without a registry)
     * @param timestamp          the moment the files describe; {@code null} or empty for the base timestamp, which
     *                           for a root is taken from the steady state file
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario
     *                           (refused for files agreeing on another), or - when it holds none or several - to
     *                           take it from the files
     * @param profiles           the profiles to store (a root) or to compare (a further snapshot), empty for the
     *                           default of each
     * @param parameters         the CGMES import parameters
     * @param reportNode         where the reader reports, may be {@code null}
     * @return the graph names of an unversioned upload, the stored model ids of a snapshot
     */
    public static List<String> loadCgmes(RdfDbConnection db, ReadOnlyDataSource ds, String scenario, String version,
                                         String timestamp, String modellingAuthority, List<String> profiles,
                                         Map<String, String> parameters, ReportNode reportNode) {
        return loadCgmes(db, ds, scenario, version, timestamp, modellingAuthority, profiles, null, parameters,
                reportNode);
    }

    /**
     * {@link #loadCgmes(RdfDbConnection, ReadOnlyDataSource, String, String, String, String, List, Map, ReportNode)}
     * with the snapshot a new timestamp hangs off.
     *
     * @param pin the pin of a new timestamp, as {@link #pin} builds it; {@code null} for the latest rollover at or
     *            before the timestamp
     */
    public static List<String> loadCgmes(RdfDbConnection db, ReadOnlyDataSource ds, String scenario, String version,
                                         String timestamp, String modellingAuthority, List<String> profiles,
                                         PinArgs pin, Map<String, String> parameters, ReportNode reportNode) {
        requireScenario(scenario);
        Instant moment = toInstant(timestamp);
        String authority = blankToNull(modellingAuthority);
        Set<String> projection = toProfiles(profiles);
        if (version == null && moment == null && authority == null && pin == null) {
            if (projection != null) {
                throw new PowsyblException("profiles select what a snapshot stores, and an upload with neither a"
                        + " version, a timestamp nor a modelling authority stores no snapshot: pass version='1' to"
                        + " store the root of scenario '" + scenario + "'");
            }
            return loadCgmes(db, scenario, ds, parameters, reportNode);
        }
        SnapshotCatalog catalog = db.snapshots(scenario);
        Properties props = toProperties(parameters);
        ReportNode rn = orNoOp(reportNode);
        SnapshotRef ref = SnapshotRef.of(scenario, authority, moment, version);
        boolean hasRoot = authority == null ? catalog.isVersioned() : catalog.root(authority).isPresent();
        if (!hasRoot && pin != null) {
            throw new PowsyblException("the files become the root of their tree, and a root hangs off nothing: drop"
                    + " the pin");
        }
        SnapshotInfo written = hasRoot
                ? catalog.putAsDiff(ds, null, ref, projection, pin(catalog, pin, authority), props, rn)
                : catalog.putFull(ds, null, ref, projection, props, rn);
        return List.copyOf(written.members());
    }

    /**
     * Store the changes a recorder holds as a new snapshot of a scenario.
     *
     * @param recording          the recorder holding the changes
     * @param db                 the open connection
     * @param scenario           the base scenario the difference is made against
     * @param version            the version name of the new snapshot, {@code null} for the lowest registered name
     *                           ranking above the head's
     * @param timestamp          the timestamp of the new snapshot, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario, or
     *                           - when it holds several - the one of the snapshot the network is at
     * @param profiles           the profiles the difference may write, empty for every profile a change touches; a
     *                           change of another profile is an unsupported change
     * @param options            the export options, see {@link NetworkEventRecording#diffOptions}, plus
     *                           {@code variant} to write the changes of one variant as the successor of <em>that
     *                           variant's</em> snapshot
     * @return the stored model ids
     */
    public static List<String> exportRecording(NetworkEventRecording recording, RdfDbConnection db, String scenario,
                                               String version, String timestamp, String modellingAuthority,
                                               List<String> profiles, Map<String, String> options) {
        return exportRecording(recording, db, scenario, version, timestamp, modellingAuthority, profiles, null,
                options);
    }

    /**
     * {@link #exportRecording(NetworkEventRecording, RdfDbConnection, String, String, String, String, List, Map)}
     * with the snapshot a new timestamp hangs off.
     *
     * @param pin the pin of a new timestamp, {@code null} for the default (the snapshot the network is at when it
     *            states what the changes supersede, else the deepest snapshot that does)
     */
    public static List<String> exportRecording(NetworkEventRecording recording, RdfDbConnection db, String scenario,
                                               String version, String timestamp, String modellingAuthority,
                                               List<String> profiles, PinArgs pin, Map<String, String> options) {
        requireScenario(scenario);
        Instant moment = toInstant(timestamp);
        String authority = blankToNull(modellingAuthority);
        Map<String, String> rest = databaseDecidedFree(options);
        String variant = blankToNull(rest.remove(VARIANT));
        CgmesDiffExport.ExportOptions exportOptions = NetworkEventRecording.diffOptions(rest);
        Set<String> projection = toProfiles(profiles);
        if (projection != null) {
            exportOptions.setSubsets(projection.stream().map(RdfDbUtil::recordedSubset)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(CgmesSubset.class))));
        }
        if (variant != null) {
            if (pin != null) {
                throw new PowsyblException("a variant export writes the successor of the snapshot variant '"
                        + variant + "' stands for, so it takes no pin");
            }
            if (moment != null || authority != null) {
                throw new PowsyblException("a variant export writes the successor of the snapshot variant '"
                        + variant + "' stands for, so its timestamp and modelling authority are that variant's own;"
                        + " drop them");
            }
            Network network = recording.getNetwork();
            if (!network.getVariantManager().getVariantIds().contains(variant)) {
                throw new PowsyblException("network " + network.getId() + " has no variant '" + variant + "'; it has "
                        + network.getVariantManager().getVariantIds());
            }
            checkSameScenario(network, scenario);
            return recording.exportWithSnapshot(events -> storedIds(
                    RdfDbExport.exportVariant(recording.getNetwork(), events, db, variant, version,
                            exportOptions, ReportNode.NO_OP)));
        }
        SnapshotCatalog catalog = db.snapshots(scenario);
        SnapshotRef target = SnapshotRef.of(scenario, onlyAuthority(catalog, authority), moment, version);
        SnapshotRef pinRef = pin(catalog, pin, target.modellingAuthority());
        return recording.exportWithSnapshot(events -> storedIds(
                RdfDbExport.export(recording.getNetwork(), events, db, target, pinRef, exportOptions,
                        ReportNode.NO_OP)));
    }

    /** A recorder translates network changes, which only ever touch the profiles the conversion reads. */
    private static CgmesSubset recordedSubset(String profile) {
        return Profiles.subset(profile).orElseThrow(() -> new PowsyblException("recorded changes are written into"
                + " the profiles the CGMES conversion reads, " + Profiles.STANDARD + ", and '" + profile
                + "' is a custom one: store a custom profile with load_cgmes"));
    }

    /**
     * One row of the table {@code to_rdf_updates(..., per_variant=True)} answers with.
     *
     * @param variant       the variant the changes were recorded on
     * @param snapshot           the IRI of the snapshot they became, empty when nothing was written
     * @param modellingAuthority the modelling authority of that snapshot
     * @param timestamp          the timestamp of that snapshot, an ISO-8601 instant
     * @param version            the version of that snapshot, empty when nothing was written
     * @param rank               the rank of that version, -1 when nothing was written
     * @param models             the stored model ids, {@code ;} joined
     * @param exportedEvent      how many recorded changes reached the database
     * @param rejected           the changes that did not, {@code ; } joined
     */
    public record VariantExportRow(String variant, String snapshot, String modellingAuthority, String timestamp,
                                   String version, int rank, String models, int exportedEvent,
                                   String rejected) {
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
     * @param version   the version name every new snapshot gets, {@code null} for the lowest registered name
     *                  ranking above the head of each timestamp's chain
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
                RdfDbExport.exportPerVariant(recording.getNetwork(), events, db, version, exportOptions,
                        ReportNode.NO_OP));
        List<VariantExportRow> rows = new ArrayList<>();
        exports.forEach((variantId, export) -> {
            RdfDbExport.SnapshotResult result = export.result();
            SnapshotInfo snapshot = result == null ? null : result.snapshot();
            rows.add(new VariantExportRow(variantId,
                    snapshot == null ? "" : snapshot.iri(),
                    snapshot == null ? "" : snapshot.modellingAuthority(),
                    snapshot == null ? "" : snapshot.timestamp().toString(),
                    snapshot == null ? "" : snapshot.version(),
                    snapshot == null ? -1 : snapshot.rank(),
                    result == null ? "" : String.join(";", storedIds(result)),
                    result == null ? 0 : result.exportedEvents().size(),
                    String.join("; ", export.rejected())));
        });
        return rows;
    }

    /**
     * Load many snapshots of one scenario as the variants of a single network.
     *
     * <p>Four parallel arrays, because that is what crosses the native boundary cheaply. An empty variant
     * identifier lets the naming rule of core decide (the ISO instant of the snapshot; {@code version@instant}
     * when two requests share an instant; {@code authority/version@instant} when the requests span several modelling
     * authorities); a {@code null} version is the newest one of that timestamp, a named one the highest ranking
     * version at or below it (exactly it with {@code exact}); an empty
     * timestamp is the base timestamp; an empty modelling authority is the only one of the scenario.</p>
     *
     * <p>A snapshot that cannot be reached inside a variant does not fail the load: its variant is not created and
     * the refusal is left on the network, where {@link #variantRows} shows it as a {@code refused} row.</p>
     *
     * @param db                            the open connection
     * @param scenario                      the scenario, required
     * @param variantIds                    the variant identifiers, {@code ""} for the naming rule
     * @param versions                      the version names, {@code null} for the newest one
     * @param exact                         whether every named version is meant exactly
     * @param timestamps                    the timestamps, {@code ""} for the base timestamp
     * @param modellingAuthorities          the modelling authorities, {@code ""} for the only one
     * @param profiles                      the profiles every variant is updated by, empty for the default
     * @param parameters                    the CGMES import parameters
     * @param reportNode                    where the load reports, may be {@code null}
     * @param allowVariantMultiThreadAccess whether the network may be read from several threads afterwards
     * @return the network, with one variant per request that was not refused
     */
    public static Network loadVariants(RdfDbConnection db, String scenario, List<String> variantIds,
                                       List<String> versions, boolean exact, List<String> timestamps,
                                       List<String> modellingAuthorities, List<String> profiles,
                                       Map<String, String> parameters, ReportNode reportNode,
                                       boolean allowVariantMultiThreadAccess) {
        requireScenario(scenario);
        Objects.requireNonNull(timestamps);
        if (timestamps.isEmpty()) {
            throw new PowsyblException("at least one snapshot is needed to load a network as variants");
        }
        int count = timestamps.size();
        if (variantIds.size() != count || versions.size() != count || modellingAuthorities.size() != count) {
            throw new PowsyblException("the variant identifiers, the versions, the timestamps and the modelling"
                    + " authorities must be as many: got " + variantIds.size() + ", " + versions.size() + ", "
                    + count + " and " + modellingAuthorities.size());
        }
        SnapshotCatalog catalog = db.snapshots(scenario);
        List<VariantRequest> requests = new ArrayList<>();
        String onlyAuthority = null;
        for (int i = 0; i < count; i++) {
            String authority = blankToNull(modellingAuthorities.get(i));
            if (authority == null) {
                // one listing for every open request
                onlyAuthority = onlyAuthority == null ? authority(catalog, null) : onlyAuthority;
                authority = onlyAuthority;
            }
            SnapshotRef ref = ref(scenario, authority, toInstant(timestamps.get(i)), versions.get(i),
                    exact && versions.get(i) != null);
            requests.add(new VariantRequest(blankToNull(variantIds.get(i)), ref));
        }
        RdfDbVariantLoadOptions options = new RdfDbVariantLoadOptions()
                .setAllowVariantMultiThreadAccess(allowVariantMultiThreadAccess);
        Set<String> projection = toProfiles(profiles);
        if (projection != null) {
            options.setUpdateOptions(options.getUpdateOptions().setProfiles(projection));
        }
        VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, scenario, requests, options,
                NetworkFactory.findDefault(), toProperties(parameters),
                orNoOp(reportNode));
        return result.network();
    }

    /**
     * One row of {@code network.variants_binding()}.
     *
     * @param variant    the identifier of the IIDM variant
     * @param scenario   the scenario the snapshot belongs to
     * @param snapshot   the IRI of the snapshot the variant stands for
     * @param modellingAuthority the modelling authority of that snapshot
     * @param timestamp  the timestamp of that snapshot, an ISO-8601 instant
     * @param version    the version of that snapshot, empty when the variant stands for none
     * @param clonedFrom the variant this one was cloned from
     * @param eq         the stored equipment model the variant is at
     * @param ssh        the stored steady state hypothesis model the variant is at
     * @param caseDate   the case date of the variant
     * @param status     {@code primary}, {@code bound}, {@code unbound} or {@code refused}
     * @param reasons    why a refused snapshot could not be reached
     */
    public record VariantRow(String variant, String scenario, String snapshot, String modellingAuthority,
                             String timestamp, String version, String clonedFrom, String eq, String ssh,
                             String caseDate, String status, String reasons) {
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
                        text(outcome.snapshotIri()), ref == null ? "" : text(ref.modellingAuthority()),
                        ref == null ? "" : text(ref.timestamp()), ref == null ? "" : text(ref.version()),
                        text(outcome.clonedFrom()), "", "", "", "refused", reasons));
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
        return new VariantRow(row.variant(), row.scenario(), row.snapshot(), row.modellingAuthority(),
                row.timestamp(), row.version(), row.clonedFrom(), row.eq(), row.ssh(), row.caseDate(), row.status(),
                reasons);
    }

    private static VariantRow unboundRow(String variantId) {
        return new VariantRow(variantId, "", "", "", "", "", "", "", "", "", "unbound", "");
    }

    private static VariantRow boundRow(VariantBinding binding, String status) {
        return new VariantRow(binding.variantId(), text(binding.scenario()), text(binding.snapshotIri()),
                text(binding.modellingAuthority()), text(binding.timestamp()), text(binding.version()),
                text(binding.clonedFrom()), text(binding.modelIds().get(Profiles.EQ)),
                text(binding.modelIds().get(Profiles.SSH)),
                binding.caseDate() == null ? "" : binding.caseDate().toString(), status, "");
    }

    /** The export options a caller may not set, because the address decides them. */
    private static Map<String, String> databaseDecidedFree(Map<String, String> options) {
        Map<String, String> rest = new LinkedHashMap<>(options == null ? Map.of() : options);
        for (String decided : DATABASE_DECIDED_OPTIONS) {
            if (rest.containsKey(decided)) {
                throw new PowsyblException("Export option '" + decided + "' is decided by the database (scenario,"
                        + " modelling authority, timestamp and version of the snapshot)");
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
        String variantId = blankToNull(variant);
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
     * @param db                 the open connection
     * @param scenario           the scenario
     * @param version            the version name, {@code null} for the newest one; otherwise the highest ranking
     *                           version at or below it
     * @param timestamp          the timestamp, {@code null} or empty for the base timestamp
     * @param modellingAuthority the modelling authority, {@code null} or empty for the only one of the scenario
     * @return the IRI of the snapshot that was materialised
     */
    public static String checkpoint(RdfDbConnection db, String scenario, String version, String timestamp,
                                    String modellingAuthority) {
        requireScenario(scenario);
        SnapshotCatalog catalog = db.snapshots(scenario);
        return Checkpoint.create(db, SnapshotRef.of(scenario, authority(catalog, modellingAuthority),
                toInstant(timestamp), version)).iri();
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
     * @return the mapper turning a timestamp list into a dataframe
     */
    public static DataframeMapper<List<SnapshotCatalog.TimestampInfo>, Void> timestampsMapper() {
        return TIMESTAMPS_MAPPER;
    }

    /**
     * @return the mapper turning an assembly (one snapshot per modelling authority) into a dataframe
     */
    public static DataframeMapper<List<AssemblyRow>, Void> assemblyMapper() {
        return ASSEMBLY_MAPPER;
    }

    /**
     * @return the mapper turning a stored model list into a dataframe
     */
    public static DataframeMapper<List<StoredModel>, Void> modelsMapper() {
        return MODELS_MAPPER;
    }

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

    private static String millis(Duration duration) {
        return duration == null ? "0" : Long.toString(duration.toMillis());
    }

    private static ReportNode orNoOp(ReportNode reportNode) {
        return reportNode == null ? ReportNode.NO_OP : reportNode;
    }

    private static List<String> storedIds(RdfDbExport.SnapshotResult result) {
        return result.stored().stream().map(StoredModel::id).toList();
    }

    /**
     * Turn the empty string the C API uses for "not given" into {@code null}; used for every optional text
     * argument (timestamp, modelling authority, variant).
     *
     * @param text the value a caller gave
     * @return the value, or {@code null} when it was {@code null}, empty or blank
     */
    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The names of a set of profiles in the order of the conversion's nine, then custom ones by name, {@code ;}
     * joined. */
    private static String profileNames(Set<String> profiles) {
        return profiles.stream().sorted(Profiles.ORDER).collect(Collectors.joining(";"));
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
            provenance.snapshot().ifPresent(iri -> putAddress(identity, iri));
            provenance.modelIds().forEach(identity::put);
        }
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        if (models != null) {
            models.getModels().forEach(model ->
                    identity.putIfAbsent(model.getSubset().getIdentifier(), model.getId()));
        }
        return identity;
    }

    /** The snapshot and its address, read off the IRI without a request. */
    private static void putAddress(Map<String, String> identity, String snapshotIri) {
        identity.put(SNAPSHOT, snapshotIri);
        SnapshotRef ref = RdfDbNames.refOf(snapshotIri);
        if (ref != null) {
            identity.put(MODELLING_AUTHORITY, text(ref.modellingAuthority()));
            identity.put(TIMESTAMP, text(ref.timestamp()));
            identity.put(VERSION, text(ref.version()));
        }
    }

    private static void putSnapshot(Map<String, String> identity, SnapshotInfo info) {
        identity.put(SNAPSHOT, info.iri());
        identity.put(SCENARIO, info.scenario());
        identity.put(MODELLING_AUTHORITY, info.modellingAuthority());
        identity.put(TIMESTAMP, info.timestamp().toString());
        identity.put(VERSION, info.version());
    }
}
