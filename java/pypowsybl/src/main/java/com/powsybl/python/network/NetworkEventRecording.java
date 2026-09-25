/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.cgmes.rdfdb.RdfDbProvenance;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.events.NetworkEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A recording of the changes made to one {@link Network}, and the exports that turn it into CGMES update documents.
 *
 * <p>This is the object a pypowsybl {@code NetworkEventRecorder} holds a handle of. It owns three things the Python
 * side cannot own: the {@link NetworkEventRecorder} listener, the knowledge of whether that listener is currently
 * attached to the network, and the translation of the flat string options that cross the native boundary into the
 * typed export options of {@link PartialSshExport} and {@link CgmesDiffExport}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>A recording starts detached. {@link #start()} attaches the listener, {@link #stop()} detaches it; both are
 * idempotent, so entering the same Python context manager twice records every change once. Stopping keeps the events,
 * which is what lets a caller export after the recorded block has been left; {@link #clear()} is the only thing that
 * drops them. A recording that is never stopped stays referenced by the network's listener list, which is why the
 * Python object stops it when it is closed or collected.</p>
 *
 * <h2>Why every export pauses the recording</h2>
 * <p>Both exporters build a {@code CgmesExportContext}, which may write aliases and properties onto a network that
 * was not imported from CGMES. Those writes are themselves network changes, so an export running while the listener
 * is attached would record property updates that the <em>next</em> export would then reject as unsupported. Every
 * export therefore detaches the listener, works on an immutable snapshot of the events taken before that, and
 * re-attaches it afterwards if it was attached. The tender usage pattern exports inside the recorded block, so this
 * is the normal case, not an edge case.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class NetworkEventRecording {

    /** Option key: what to do with a change that cannot be exported, {@code raise} or {@code ignore}. */
    public static final String UNSUPPORTED = "unsupported";
    /** Option key: how much of a changed object a difference model describes. */
    public static final String GRANULARITY = "granularity";
    /** Option key: the identifier of the exported model. */
    public static final String MODEL_ID = "model_id";
    /** Option key: the description of the exported model. */
    public static final String DESCRIPTION = "description";
    /** Option key: the version number of the exported model. */
    public static final String VERSION = "version";
    /** Option key: the modeling authority set of the exported model. */
    public static final String MODELING_AUTHORITY_SET = "modeling_authority_set";
    /** Option key: the models the exported model declares it replaces, comma separated. */
    public static final String SUPERSEDES = "supersedes";
    /** Option key: the models the exported model declares it depends on, comma separated. */
    public static final String DEPENDS_ON = "depends_on";
    /** Option key: the point in time the exported state describes. */
    public static final String SCENARIO_TIME = "scenario_time";
    /** Option key: the creation time of the exported model. */
    public static final String CREATED = "created";
    /** Option key: the network variant whose values and identity the export describes. */
    public static final String VARIANT = "variant";

    private static final String RAISE = "raise";
    private static final String IGNORE = "ignore";
    private static final String FULL_OBJECT = "full_object";
    private static final String CHANGED_ONLY = "changed_only";

    /** The header option keys, that is everything but the two keys steering the export itself. */
    private static final List<String> HEADER_KEYS =
            List.of(MODEL_ID, DESCRIPTION, VERSION, MODELING_AUTHORITY_SET, SUPERSEDES, DEPENDS_ON);

    private static final List<String> SSH_KEYS =
            concat(List.of(UNSUPPORTED, SCENARIO_TIME, CREATED), HEADER_KEYS);

    private static final List<String> DIFF_KEYS =
            concat(List.of(UNSUPPORTED, GRANULARITY, SCENARIO_TIME, CREATED), HEADER_KEYS);

    /** The profiles a difference model export may write, in the order in which they are reported. */
    private static final List<CgmesSubset> DIFF_SUBSETS =
            List.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS);

    private final Network network;

    private final NetworkEventRecorder recorder = new NetworkEventRecorder();

    private boolean recording;

    public NetworkEventRecording(Network network) {
        this.network = Objects.requireNonNull(network);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    public Network getNetwork() {
        return network;
    }

    /** Attach the listener to the network, if it is not attached already. */
    public void start() {
        if (!recording) {
            network.addListener(recorder);
            recording = true;
        }
    }

    /** Detach the listener from the network, if it is attached. The events recorded so far are kept. */
    public void stop() {
        if (recording) {
            network.removeListener(recorder);
            recording = false;
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /** Drop every recorded change, without changing whether the recording is running. */
    public void clear() {
        recorder.reset();
    }

    public int getEventCount() {
        return recorder.getEvents().size();
    }

    /** The recorded changes, in the order in which they were made. */
    public List<NetworkEvent> getEvents() {
        return List.copyOf(recorder.getEvents());
    }

    /**
     * Export the recorded changes as a partial Steady State Hypothesis document.
     *
     * @param options the flat option map, see the option key constants of this class
     * @return the XML document
     * @throws PowsyblException if an option is unknown or malformed, or if a change cannot be written and the
     *                          behavior is {@code raise}
     */
    public String toPartialSsh(Map<String, String> options) {
        Map<String, String> rest = new LinkedHashMap<>(options);
        String variant = effectiveVariant(variantOf(rest));
        PartialSshExport.ExportOptions exportOptions = partialSshOptions(rest);
        if (variant != null) {
            exportOptions.setVariant(variant);
            exportOptions.setRejectSharedChanges(hasSeveralVariants());
        }
        return exportWithSnapshot(events -> RdfDbProvenance.inVariant(network, variant, () -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PartialSshExport.write(network, events, outputStream, exportOptions);
            return outputStream.toString(StandardCharsets.UTF_8);
        }));
    }

    /**
     * Export the recorded changes as a single CGMES difference model document.
     *
     * @param profileOrNull the profile to write ({@code EQ} or {@code SSH}, case insensitive), or {@code null} to let
     *                      the changes decide, which then have to touch at most one profile
     * @param options       the flat option map, see the option key constants of this class
     * @return the XML document, which is a well formed empty difference model when nothing was recorded
     */
    public String toCgmesDiff(String profileOrNull, Map<String, String> options) {
        CgmesSubset subset = profileOrNull == null ? null : parseProfile(profileOrNull);
        Map<String, String> rest = new LinkedHashMap<>(options);
        String variant = effectiveVariant(variantOf(rest));
        CgmesDiffExport.ExportOptions exportOptions = withVariant(diffOptions(rest), variant);
        return exportWithSnapshot(events -> RdfDbProvenance.inVariant(network, variant, () -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CgmesDiffExport.write(network, events, outputStream, subset, exportOptions);
            return outputStream.toString(StandardCharsets.UTF_8);
        }));
    }

    /**
     * Export the recorded changes as one CGMES difference model document per touched profile.
     *
     * <p>This is a single {@link CgmesDiffExport#toDifferences} call over every profile, not one export per profile:
     * an export restricted to one profile reports the changes of the other one as unsupported, and the link that
     * makes the difference of the steady state hypothesis depend on the difference of the equipment model only
     * exists when both are built together.</p>
     *
     * @return the documents keyed by profile identifier ({@code EQ}, {@code SSH}), in that order, empty when nothing
     *         was recorded
     */
    public Map<String, String> toCgmesDiffs(Map<String, String> options) {
        Map<String, String> rest = new LinkedHashMap<>(options);
        String variant = effectiveVariant(variantOf(rest));
        CgmesDiffExport.ExportOptions exportOptions = withVariant(diffOptions(rest), variant);
        return exportWithSnapshot(events -> RdfDbProvenance.inVariant(network, variant, () -> {
            CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events, exportOptions);
            Map<String, String> documents = new LinkedHashMap<>();
            for (Map.Entry<CgmesSubset, DifferenceModel> entry : result.differences().models().entrySet()) {
                documents.put(entry.getKey().getIdentifier(), DifferenceModelWriter.toString(entry.getValue()));
            }
            return documents;
        }));
    }

    /**
     * Take the {@code variant} option out of a flat option map.
     *
     * <p>It is not an export option of the CGMES exporters but of this binding: it selects <em>which state</em> is
     * exported, and for a variant bound to a stored snapshot also which model the difference supersedes. It is
     * therefore removed before the rest is handed to the option parsers, which reject what they do not know.</p>
     *
     * @param options the option map, modified in place
     * @return the variant, or {@code null} when none was named
     */
    static String variantOf(Map<String, String> options) {
        String variant = options.remove(VARIANT);
        return variant == null || variant.isBlank() ? null : variant;
    }

    /**
     * Which variant an export really describes.
     *
     * <p>A caller who names one gets that one. A caller who names none is exporting the <em>working</em> variant,
     * and on a network <strong>in variant mode</strong> that variant is what the document is about: its values,
     * its {@code md:Model.Supersedes}, its scenario time, and its refusal of a change IIDM shares between
     * variants &mdash; exactly what {@code to_rdf_updates} without a variant already does there, and exactly the
     * natural gesture of the feature ({@code set_working_variant(label)}, change, export). The primary is
     * included: in variant mode a change that belongs to every variant belongs to no single snapshot, whichever
     * variant is selected.</p>
     *
     * <p><strong>Variant mode, not the presence of a binding, is the question.</strong> Core tracks a binding for
     * every variant a user clones from a database-loaded network, opt-in or not, so a binding proves nothing about
     * what the caller asked for. Outside variant mode this therefore answers {@code null} and an unnamed export
     * behaves exactly as it did before variants existed &mdash; in particular it still writes a shared change,
     * such as a line impedance, which is the whole point of an ordinary clone.</p>
     *
     * @param named the variant the caller named, or {@code null}
     * @return the variant to export as, or {@code null} to export exactly as before
     */
    private String effectiveVariant(String named) {
        if (named != null) {
            return named;
        }
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance == null || !provenance.isVariantMode()) {
            return null;
        }
        try {
            return network.getVariantManager().getWorkingVariantId();
        } catch (PowsyblException e) {
            // A thread that never selected a variant has none; there is nothing to resolve
            return null;
        }
    }

    /**
     * Select a variant on difference model export options.
     *
     * <p>With more than one variant in the network a change IIDM does not store per variant belongs to all of them
     * and therefore to none of their snapshots, so it is refused rather than written into the one the caller
     * selected.</p>
     */
    private CgmesDiffExport.ExportOptions withVariant(CgmesDiffExport.ExportOptions exportOptions, String variant) {
        if (variant != null) {
            exportOptions.setVariant(variant);
            exportOptions.setRejectSharedChanges(hasSeveralVariants());
        }
        return exportOptions;
    }

    private boolean hasSeveralVariants() {
        return network.getVariantManager().getVariantIds().size() > 1;
    }

    /**
     * Run an export on an immutable snapshot of the recorded changes with the listener detached, see the class
     * javadoc. The recording is left in the state it was in.
     */
    <T> T exportWithSnapshot(Function<List<NetworkEvent>, T> exporter) {
        List<NetworkEvent> snapshot = getEvents();
        boolean wasRecording = recording;
        stop();
        try {
            return exporter.apply(snapshot);
        } finally {
            if (wasRecording) {
                start();
            }
        }
    }

    /**
     * Translate the flat option map of a partial SSH export into typed export options.
     *
     * <p>A partial SSH document holds one profile, so it takes no granularity and no profile prefix other than
     * {@code ssh.}.</p>
     */
    static PartialSshExport.ExportOptions partialSshOptions(Map<String, String> options) {
        PartialSshExport.ExportOptions exportOptions = new PartialSshExport.ExportOptions();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String prefix = prefixOf(key);
            String name = stripPrefix(key);
            if (prefix != null && !"ssh".equals(prefix)) {
                throw new PowsyblException("Export option '" + key + "' addresses the profile '"
                        + prefix.toUpperCase(Locale.ROOT)
                        + "'; a partial Steady State Hypothesis export only writes the SSH profile");
            }
            if (!SSH_KEYS.contains(name)) {
                throw unknownOption(key, SSH_KEYS);
            }
            applyPartialSshOption(exportOptions, name, value);
        }
        return exportOptions;
    }

    private static void applyPartialSshOption(PartialSshExport.ExportOptions exportOptions, String name,
                                              String value) {
        switch (name) {
            case UNSUPPORTED -> exportOptions.setUnsupportedChangeBehavior(parseUnsupported(value));
            case MODEL_ID -> exportOptions.setModelId(value);
            case DESCRIPTION -> exportOptions.setDescription(value);
            case VERSION -> exportOptions.setVersion(parseVersion(name, value));
            case MODELING_AUTHORITY_SET -> exportOptions.setModelingAuthoritySet(value);
            case SCENARIO_TIME -> exportOptions.setScenarioTime(parseTime(name, value));
            case CREATED -> exportOptions.setCreated(parseTime(name, value));
            case SUPERSEDES -> {
                exportOptions.setSupersedePreviousSshModel(false);
                exportOptions.addSupersedes(splitIds(value));
            }
            case DEPENDS_ON -> {
                exportOptions.clearDependencies();
                exportOptions.addDependentOn(splitIds(value));
            }
            default -> throw unknownOption(name, SSH_KEYS);
        }
    }

    /**
     * Translate the flat option map of a difference model export into typed export options.
     *
     * <p>A header option without a profile prefix applies to every written profile, a prefixed one only to its own
     * profile and wins over the unprefixed value. The unprefixed values are therefore applied first.</p>
     */
    static CgmesDiffExport.ExportOptions diffOptions(Map<String, String> options) {
        CgmesDiffExport.ExportOptions exportOptions = new CgmesDiffExport.ExportOptions();
        // two passes, so that a prefixed header value overrides the unprefixed one whatever the map order is
        applyDiffOptions(exportOptions, options, false);
        applyDiffOptions(exportOptions, options, true);
        return exportOptions;
    }

    private static void applyDiffOptions(CgmesDiffExport.ExportOptions exportOptions, Map<String, String> options,
                                         boolean prefixed) {
        for (Map.Entry<String, String> entry : options.entrySet()) {
            String key = entry.getKey();
            String prefix = prefixOf(key);
            if (prefixed != (prefix != null)) {
                continue;
            }
            String name = stripPrefix(key);
            if (!DIFF_KEYS.contains(name)) {
                throw unknownOption(key, DIFF_KEYS);
            }
            if (HEADER_KEYS.contains(name)) {
                for (CgmesSubset subset : prefix == null ? DIFF_SUBSETS : List.of(parseProfile(prefix))) {
                    applyHeaderOption(exportOptions.header(subset), name, entry.getValue());
                }
            } else if (prefix != null) {
                throw new PowsyblException("Export option '" + key + "' cannot be set per profile; "
                        + "it steers the whole export, write it as '" + name + "'");
            } else {
                applyDiffOption(exportOptions, name, entry.getValue());
            }
        }
    }

    private static void applyDiffOption(CgmesDiffExport.ExportOptions exportOptions, String name, String value) {
        switch (name) {
            case UNSUPPORTED -> exportOptions.setUnsupportedChangeBehavior(parseUnsupported(value));
            case GRANULARITY -> exportOptions.setGranularity(parseGranularity(value));
            case SCENARIO_TIME -> exportOptions.setScenarioTime(parseTime(name, value));
            case CREATED -> exportOptions.setCreated(parseTime(name, value));
            default -> throw unknownOption(name, DIFF_KEYS);
        }
    }

    private static void applyHeaderOption(CgmesDiffExport.HeaderOptions header, String name, String value) {
        switch (name) {
            case MODEL_ID -> header.setModelId(value);
            case DESCRIPTION -> header.setDescription(value);
            case VERSION -> header.setVersion(parseVersion(name, value));
            case MODELING_AUTHORITY_SET -> header.setModelingAuthoritySet(value);
            case SUPERSEDES -> {
                header.setSupersedePreviousModel(false);
                header.addSupersedes(splitIds(value));
            }
            case DEPENDS_ON -> {
                header.clearDependencies();
                header.addDependentOn(splitIds(value));
            }
            default -> throw unknownOption(name, HEADER_KEYS);
        }
    }

    /**
     * The profile a name denotes, matched case insensitively against the CGMES profile identifiers. Only the two
     * profiles an update document may describe are accepted.
     */
    static CgmesSubset parseProfile(String profile) {
        Objects.requireNonNull(profile);
        for (CgmesSubset subset : DIFF_SUBSETS) {
            if (subset.getIdentifier().equalsIgnoreCase(profile)) {
                return subset;
            }
        }
        throw new PowsyblException("Unknown profile '" + profile + "'. Supported profiles: "
                + DIFF_SUBSETS.stream().map(CgmesSubset::getIdentifier).toList());
    }

    /** The profile prefix of an option key, for example {@code ssh} in {@code ssh.model_id}, or {@code null}. */
    private static String prefixOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? null : key.substring(0, dot);
    }

    private static String stripPrefix(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    private static PowsyblException unknownOption(String key, List<String> supported) {
        return new PowsyblException("Unknown export option '" + key + "'. Supported options: " + supported);
    }

    private static UnsupportedChangeBehavior parseUnsupported(String value) {
        return switch (value) {
            case RAISE -> UnsupportedChangeBehavior.FAIL;
            case IGNORE -> UnsupportedChangeBehavior.IGNORE;
            default -> throw new PowsyblException("Unknown value '" + value + "' of export option '" + UNSUPPORTED
                    + "'. Supported values: [" + RAISE + ", " + IGNORE + "]");
        };
    }

    private static CgmesDiffExport.DiffGranularity parseGranularity(String value) {
        return switch (value) {
            case FULL_OBJECT -> CgmesDiffExport.DiffGranularity.FULL_OBJECT;
            case CHANGED_ONLY -> CgmesDiffExport.DiffGranularity.CHANGED_ONLY;
            default -> throw new PowsyblException("Unknown value '" + value + "' of export option '" + GRANULARITY
                    + "'. Supported values: [" + FULL_OBJECT + ", " + CHANGED_ONLY + "]");
        };
    }

    private static int parseVersion(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new PowsyblException("Export option '" + key + "' expects a whole number, got '" + value + "'");
        }
    }

    /**
     * A point in time, read as an offset date time and, when it carries no offset, as a local date time taken as UTC.
     * The Python side sends {@code datetime.isoformat()}, which is one of the two.
     */
    private static ZonedDateTime parseTime(String key, String value) {
        String trimmed = value.trim();
        try {
            return ZonedDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            // not a zoned date time, try the two shapes an ISO-8601 timestamp without a zone region can have
        }
        try {
            return OffsetDateTime.parse(trimmed).toZonedDateTime();
        } catch (DateTimeParseException e) {
            // no offset either, so it is a local date time, which the caller documented as UTC
        }
        try {
            return LocalDateTime.parse(trimmed).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new PowsyblException("Export option '" + key + "' expects an ISO-8601 date and time, got '"
                    + value + "'");
        }
    }

    /** The identifiers of a comma separated list, trimmed, without the empty ones. */
    private static List<String> splitIds(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
