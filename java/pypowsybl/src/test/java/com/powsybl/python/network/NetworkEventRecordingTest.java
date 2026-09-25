/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.events.CreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionRemovalNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.PropertiesUpdateNetworkEvent;
import com.powsybl.iidm.network.events.RemovalNetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.events.VariantNetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the JVM side of the pypowsybl network event recorder. The native entry points of
 * {@link NetworkEventRecorderCFunctions} cannot be exercised on the JVM, which is why they are one liners and
 * everything they delegate to is tested here.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class NetworkEventRecordingTest {

    private static Network load() {
        return Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
    }

    private static Load anyLoad(Network network) {
        return network.getLoadStream().findFirst().orElseThrow();
    }

    private static Generator anyGenerator(Network network) {
        return network.getGeneratorStream().findFirst().orElseThrow();
    }

    /** The opening tag of the active power of an energy consumer, which is what the variant tests compare on. */
    private static final String P0 = "<cim:EnergyConsumer.p>";

    private static Map<String, String> options(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // Lifecycle

    @Test
    void recordsOnlyBetweenStartAndStop() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        assertFalse(recording.isRecording());

        anyLoad(network).setP0(1.0);
        assertEquals(0, recording.getEventCount());

        recording.start();
        assertTrue(recording.isRecording());
        anyLoad(network).setP0(2.0);
        assertEquals(1, recording.getEventCount());

        recording.stop();
        assertFalse(recording.isRecording());
        anyLoad(network).setP0(3.0);
        assertEquals(1, recording.getEventCount());
    }

    @Test
    void startAndStopAreIdempotent() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        recording.start();
        anyLoad(network).setP0(10.0);
        assertEquals(1, recording.getEventCount(), "a change is recorded once even after two start() calls");
        recording.stop();
        recording.stop();
        assertFalse(recording.isRecording());
    }

    @Test
    void eventsSurviveStopAndClearEmptiesThem() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(10.0);
        recording.stop();
        assertEquals(1, recording.getEvents().size());

        recording.clear();
        assertEquals(0, recording.getEventCount());
        assertThat(recording.getEvents()).isEmpty();
    }

    @Test
    void getNetworkReturnsTheRecordedNetwork() {
        Network network = load();
        assertEquals(network, new NetworkEventRecording(network).getNetwork());
    }

    // Partial SSH

    @Test
    void partialSshContainsChangedLoad() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = anyLoad(network);
        load.setP0(123.5);
        String ssh = recording.toPartialSsh(options());

        assertThat(ssh).contains("md:FullModel")
                .contains("EnergyConsumer.p")
                .contains(load.getId())
                .contains("123.5");
    }

    @Test
    void exportInsideRecordingDoesNotRecordOrDropEvents() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(42.0);
        int before = recording.getEventCount();

        recording.toPartialSsh(options());

        assertEquals(before, recording.getEventCount(), "the export must not record anything itself");
        assertTrue(recording.isRecording(), "the export must leave the recording running");

        // and a second export of the same recording still works, which it would not if the first one had recorded
        // property updates on the network
        assertThat(recording.toPartialSsh(options())).contains("EnergyConsumer.p");
    }

    /**
     * The {@code variant} option of the three file exports: it selects which state is written, and it restores the
     * working variant of the caller afterwards.
     *
     * <p>Two loads of the same network are moved on two variants. Each export names one of them and must write
     * that variant's value only - the recorder records the changes of every variant, and without the option the
     * export of a change made on another variant than the working one is not even supported.</p>
     */
    @Test
    void theVariantOptionSelectsWhichStateIsExported() {
        Network network = load();
        network.getVariantManager().cloneVariant("InitialState", "morning");
        network.getVariantManager().cloneVariant("InitialState", "evening");
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        network.getVariantManager().setWorkingVariant("morning");
        Load load = anyLoad(network);
        load.setP0(11.0);
        network.getVariantManager().setWorkingVariant("evening");
        anyLoad(network).setP0(99.0);
        network.getVariantManager().setWorkingVariant("InitialState");
        recording.stop();

        String morning = recording.toPartialSsh(options(NetworkEventRecording.VARIANT, "morning"));
        assertThat(morning).contains(load.getId()).contains(P0 + "11</").doesNotContain(P0 + "99</");
        assertEquals("InitialState", network.getVariantManager().getWorkingVariantId(),
                "the export restores the working variant of the caller");

        String evening = recording.toCgmesDiff("SSH", options(NetworkEventRecording.VARIANT, "evening"));
        assertThat(evening).contains(P0 + "99</").doesNotContain(P0 + "11</");
        assertEquals("InitialState", network.getVariantManager().getWorkingVariantId());

        Map<String, String> documents = recording.toCgmesDiffs(options(NetworkEventRecording.VARIANT, "morning"));
        assertThat(documents.get("SSH")).contains(P0 + "11</").doesNotContain(P0 + "99</");
        assertEquals("InitialState", network.getVariantManager().getWorkingVariantId());
    }

    /**
     * A change IIDM does not store per variant - here a property - belongs to every variant of the network, so it
     * cannot be written into the document of one of them. With a single variant there is nothing to confuse and
     * the change is exported as before.
     */
    @Test
    void aSharedChangeIsUnsupportedInAVariantExport() {
        Network network = load();
        network.getVariantManager().cloneVariant("InitialState", "morning");
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        network.getVariantManager().setWorkingVariant("morning");
        anyLoad(network).setP0(11.0);
        network.getLines().iterator().next().setR(0.42);
        network.getVariantManager().setWorkingVariant("InitialState");
        recording.stop();

        assertThatThrownBy(() -> recording.toCgmesDiff(null, options(NetworkEventRecording.VARIANT, "morning")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("not stored per variant");
        String ignored = recording.toCgmesDiff("SSH", options(NetworkEventRecording.VARIANT, "morning",
                NetworkEventRecording.UNSUPPORTED, "ignore"));
        assertThat(ignored).contains(P0 + "11</");
    }

    @Test
    void anUnknownVariantIsAClearError() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(11.0);
        recording.stop();
        assertThatThrownBy(() -> recording.toPartialSsh(options(NetworkEventRecording.VARIANT, "nope")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("nope");
        assertThat(recording.toPartialSsh(options(NetworkEventRecording.VARIANT, "")))
                .as("an empty variant means none was named")
                .contains(P0 + "11</");
    }

    @Test
    void exportOnAStoppedRecordingLeavesItStopped() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(42.0);
        recording.stop();

        recording.toPartialSsh(options());

        assertFalse(recording.isRecording());
    }

    @Test
    void unsupportedRaisesOrIsIgnored() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = anyLoad(network);
        load.setP0(11.0);
        Generator generator = anyGenerator(network);
        generator.setMaxP(generator.getMaxP() + 1.0);

        assertThatThrownBy(() -> recording.toPartialSsh(options()))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("maxP")
                .hasMessageContaining(generator.getId());

        String ssh = recording.toPartialSsh(options(NetworkEventRecording.UNSUPPORTED, "ignore"));
        assertThat(ssh).contains(load.getId());
        assertThat(ssh).doesNotContain(generator.getId());
    }

    @Test
    void metadataOptionsReachTheHeader() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(11.0);

        String ssh = recording.toPartialSsh(options(
                NetworkEventRecording.MODEL_ID, "urn:uuid:11111111-2222-3333-4444-555555555555",
                NetworkEventRecording.VERSION, "7",
                NetworkEventRecording.DESCRIPTION, "a description of the change",
                NetworkEventRecording.MODELING_AUTHORITY_SET, "http://example.com/OperationalPlanning",
                NetworkEventRecording.SUPERSEDES, "urn:uuid:aaaaaaaa-0000-0000-0000-000000000001",
                NetworkEventRecording.DEPENDS_ON, "urn:uuid:bbbbbbbb-0000-0000-0000-000000000002",
                NetworkEventRecording.SCENARIO_TIME, "2026-02-03T04:05:00Z",
                NetworkEventRecording.CREATED, "2026-02-03T06:07:00Z"));

        assertThat(ssh)
                .contains("urn:uuid:11111111-2222-3333-4444-555555555555")
                .contains(">7<")
                .contains("a description of the change")
                .contains("http://example.com/OperationalPlanning")
                .contains("urn:uuid:aaaaaaaa-0000-0000-0000-000000000001")
                .contains("urn:uuid:bbbbbbbb-0000-0000-0000-000000000002")
                .contains("2026-02-03T04:05")
                .contains("2026-02-03T06:07");
    }

    @Test
    void createdWithoutZoneIsReadAsUtc() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(11.0);

        String naive = recording.toPartialSsh(options(NetworkEventRecording.CREATED, "2026-02-03T06:07:00"));
        String utc = recording.toPartialSsh(options(NetworkEventRecording.CREATED, "2026-02-03T06:07:00Z"));
        assertEquals(utc, naive);
    }

    @Test
    void emptySupersedesAndDependsOnRemoveTheDefaults() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(11.0);

        String withDefaults = recording.toPartialSsh(options());
        String stripped = recording.toPartialSsh(options(
                NetworkEventRecording.SUPERSEDES, "",
                NetworkEventRecording.DEPENDS_ON, ""));

        assertThat(withDefaults).contains("Model.Supersedes");
        assertThat(stripped).doesNotContain("Model.Supersedes");
        assertThat(stripped).doesNotContain("Model.DependentOn");
    }

    @Test
    void unknownOptionIsRejected() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThatThrownBy(() -> recording.toPartialSsh(options("model-id", "x")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("Unknown export option 'model-id'")
                .hasMessageContaining(NetworkEventRecording.MODEL_ID);
        assertThatThrownBy(() -> recording.toCgmesDiffs(options("whatever", "x")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("Unknown export option 'whatever'");
    }

    @Test
    void badVersionAndBadTimeAreRejected() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThatThrownBy(() -> recording.toPartialSsh(options(NetworkEventRecording.VERSION, "seven")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining(NetworkEventRecording.VERSION)
                .hasMessageContaining("seven");
        assertThatThrownBy(() -> recording.toPartialSsh(options(NetworkEventRecording.CREATED, "yesterday")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining(NetworkEventRecording.CREATED)
                .hasMessageContaining("yesterday");
        assertThatThrownBy(() -> recording.toPartialSsh(options(NetworkEventRecording.UNSUPPORTED, "maybe")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("maybe");
    }

    @Test
    void granularityAndForeignPrefixAreRejectedForSsh() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThatThrownBy(() -> recording.toPartialSsh(options(NetworkEventRecording.GRANULARITY, "full_object")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining(NetworkEventRecording.GRANULARITY);
        assertThatThrownBy(() -> recording.toPartialSsh(options("eq.model_id", "urn:uuid:1")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("EQ");
        // the ssh. prefix is accepted, it addresses the only profile a partial SSH writes
        NetworkEventRecording recording2 = new NetworkEventRecording(load());
        recording2.start();
        anyLoad(recording2.getNetwork()).setP0(11.0);
        assertThat(recording2.toPartialSsh(options("ssh.model_id", "urn:uuid:99999999-0000-0000-0000-000000000000")))
                .contains("urn:uuid:99999999-0000-0000-0000-000000000000");
    }

    @Test
    void badGranularityValueIsRejected() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThatThrownBy(() -> recording.toCgmesDiffs(options(NetworkEventRecording.GRANULARITY, "coarse")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("coarse");
    }

    // Difference models

    @Test
    void cgmesDiffSingleProfile() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = anyLoad(network);
        load.setP0(123.5);

        String diff = recording.toCgmesDiff("SSH", options());
        assertThat(diff)
                .contains("DifferenceModel")
                .contains("forwardDifferences")
                .contains("reverseDifferences")
                .contains(load.getId())
                .contains("123.5");

        // the profile is matched case insensitively, and null lets the changes decide
        assertThat(recording.toCgmesDiff("ssh", options())).contains(load.getId());
        assertThat(recording.toCgmesDiff(null, options())).contains(load.getId());
    }

    @Test
    void cgmesDiffOfAnEmptyRecordingIsAWellFormedDocument() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThat(recording.toCgmesDiff("SSH", options())).contains("DifferenceModel");
        assertThat(recording.toCgmesDiffs(options())).isEmpty();
    }

    @Test
    void cgmesDiffsReturnsOneDocumentPerProfile() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(21.0);

        Map<String, String> documents = recording.toCgmesDiffs(options());
        assertThat(documents).containsOnlyKeys("SSH");
        assertThat(documents.get("SSH")).contains("DifferenceModel");
    }

    @Test
    void prefixedHeaderOptionOnlyHitsItsProfile() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(21.0);

        Map<String, String> documents = recording.toCgmesDiffs(options(
                NetworkEventRecording.MODEL_ID, "urn:uuid:cccccccc-0000-0000-0000-000000000000",
                "ssh.model_id", "urn:uuid:dddddddd-0000-0000-0000-000000000000"));

        assertThat(documents.get("SSH"))
                .contains("urn:uuid:dddddddd-0000-0000-0000-000000000000")
                .doesNotContain("urn:uuid:cccccccc-0000-0000-0000-000000000000");
    }

    @Test
    void anExportSteeringOptionCannotBePrefixed() {
        NetworkEventRecording recording = new NetworkEventRecording(load());
        assertThatThrownBy(() -> recording.toCgmesDiffs(options("ssh.unsupported", "ignore")))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("ssh.unsupported");
    }

    @Test
    void changedOnlyGranularityIsShorterThanFullObject() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        anyLoad(network).setP0(21.0);

        String full = recording.toCgmesDiff("SSH", options(NetworkEventRecording.GRANULARITY, "full_object"));
        String delta = recording.toCgmesDiff("SSH", options(NetworkEventRecording.GRANULARITY, "changed_only"));
        assertThat(delta.length()).isLessThan(full.length());
    }

    @Test
    void parseProfile() {
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, NetworkEventRecording.parseProfile("SSH"));
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, NetworkEventRecording.parseProfile("ssh"));
        assertEquals(CgmesSubset.EQUIPMENT, NetworkEventRecording.parseProfile("eq"));
        assertThatThrownBy(() -> NetworkEventRecording.parseProfile("TP"))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("TP");
    }

    // Round trip

    @Test
    void roundTripThroughNetworkUpdate(@TempDir Path tempDir) throws IOException {
        for (boolean asDiff : new boolean[] {false, true}) {
            Network sender = load();
            Network receiver = load();

            NetworkEventRecording recording = new NetworkEventRecording(sender);
            recording.start();
            Load load = anyLoad(sender);
            load.setP0(load.getP0() + 5.0);
            load.setQ0(load.getQ0() + 3.0);
            Generator generator = anyGenerator(sender);
            generator.setTargetP(generator.getTargetP() + 2.0);
            TwoWindingsTransformer transformer = sender.getTwoWindingsTransformerStream()
                    .filter(t -> t.getRatioTapChanger() != null)
                    .findFirst()
                    .orElseThrow();
            RatioTapChanger rtc = transformer.getRatioTapChanger();
            rtc.setTapPosition(rtc.getTapPosition() == rtc.getLowTapPosition()
                    ? rtc.getHighTapPosition() : rtc.getLowTapPosition());
            recording.stop();

            String document = asDiff ? recording.toCgmesDiff("SSH", options()) : recording.toPartialSsh(options());
            Path file = tempDir.resolve(asDiff ? "u_SSH_DIFF.xml" : "u_SSH.xml");
            Files.writeString(file, document, StandardCharsets.UTF_8);
            // a partial SSH is still an SSH file: without this the ordinary update resets what it does not mention
            Properties parameters = new Properties();
            if (!asDiff) {
                parameters.put("iidm.import.cgmes.use-previous-values-during-update", "true");
            }
            receiver.update(DataSource.fromPath(file), parameters);

            String kind = asDiff ? "difference model" : "partial SSH";
            assertEquals(anyLoad(sender).getP0(), anyLoad(receiver).getP0(), 1e-6, kind);
            assertEquals(anyLoad(sender).getQ0(), anyLoad(receiver).getQ0(), 1e-6, kind);
            assertEquals(anyGenerator(sender).getTargetP(), anyGenerator(receiver).getTargetP(), 1e-6, kind);
            assertEquals(rtc.getTapPosition(),
                    receiver.getTwoWindingsTransformer(transformer.getId()).getRatioTapChanger()
                            .getTapPosition(), kind);
            // the values the document does not mention survive
            assertEquals(sender.getLoadStream().mapToDouble(Load::getP0).sum(),
                    receiver.getLoadStream().mapToDouble(Load::getP0).sum(), 1e-6,
                    kind + ": untouched loads must keep their value");
        }
    }

    // Dataframe view

    @Test
    void eventsDataframe() {
        Network network = load();
        NetworkEventRecording recording = new NetworkEventRecording(network);
        recording.start();
        Load load = anyLoad(network);
        load.setP0(1.0);
        load.setP0(2.0);
        recording.stop();

        List<Series> series = new ArrayList<>();
        Dataframes.networkEventsMapper().createDataframe(recording.getEvents(),
                new DefaultDataframeHandler(series::add), new DataframeFilter());

        assertThat(series).extracting(Series::getName)
                .containsExactly("index", "type", "id", "extension", "attribute", "variant", "old_value", "new_value");
        assertEquals(2, series.get(0).getInts().length, "the dataframe is not compacted");
        assertEquals("UPDATE", series.get(1).getStrings()[0]);
        assertEquals(load.getId(), series.get(2).getStrings()[0]);
        assertEquals("", series.get(3).getStrings()[0]);
        assertEquals("p0", series.get(4).getStrings()[0]);
        assertEquals("2.0", series.get(7).getStrings()[1]);
    }

    @Test
    void eventRowRendersEveryEventKind() {
        NetworkEvent update = new UpdateNetworkEvent("LOAD", "p0", "InitialState", 1.0, 2.0);
        NetworkEventRow row = new NetworkEventRow(0, update);
        assertEquals("UPDATE", NetworkEventRow.type(row));
        assertEquals("LOAD", NetworkEventRow.id(row));
        assertEquals("", NetworkEventRow.extension(row));
        assertEquals("p0", NetworkEventRow.attribute(row));
        assertEquals("InitialState", NetworkEventRow.variant(row));
        assertEquals("1.0", NetworkEventRow.oldValue(row));
        assertEquals("2.0", NetworkEventRow.newValue(row));

        NetworkEventRow creation = new NetworkEventRow(1, new CreationNetworkEvent("G"));
        assertEquals("CREATION", NetworkEventRow.type(creation));
        assertEquals("G", NetworkEventRow.id(creation));
        assertEquals("", NetworkEventRow.attribute(creation));
        assertEquals("", NetworkEventRow.newValue(creation));

        NetworkEventRow extension = new NetworkEventRow(2,
                new ExtensionUpdateNetworkEvent("G", "activePowerControl",
                        "droop", "InitialState", 1.0, 3.0));
        assertEquals("activePowerControl", NetworkEventRow.extension(extension));
        assertEquals("droop", NetworkEventRow.attribute(extension));

        NetworkEventRow variant = new NetworkEventRow(3,
                new VariantNetworkEvent("InitialState", "other",
                        VariantNetworkEvent.VariantEventType.CREATED));
        assertEquals("", NetworkEventRow.id(variant));
        assertEquals("other", NetworkEventRow.variant(variant));
        assertEquals("InitialState", NetworkEventRow.oldValue(variant));
        assertEquals("CREATED", NetworkEventRow.newValue(variant));

        NetworkEventRow property = new NetworkEventRow(4,
                new PropertiesUpdateNetworkEvent("L", "key",
                        PropertiesUpdateNetworkEvent.PropertyUpdateType.ADDED,
                        null, "v"));
        assertEquals("key", NetworkEventRow.attribute(property));
        assertEquals("", NetworkEventRow.oldValue(property));
        assertEquals("v", NetworkEventRow.newValue(property));

        NetworkEventRow removal = new NetworkEventRow(5,
                new RemovalNetworkEvent("L", true));
        assertEquals("L", NetworkEventRow.id(removal));

        NetworkEventRow extensionCreation = new NetworkEventRow(6,
                new ExtensionCreationNetworkEvent("G", "activePowerControl"));
        assertEquals("activePowerControl", NetworkEventRow.extension(extensionCreation));
        assertEquals("G", NetworkEventRow.id(extensionCreation));

        NetworkEventRow extensionRemoval = new NetworkEventRow(7,
                new ExtensionRemovalNetworkEvent("G", "activePowerControl", true));
        assertEquals("activePowerControl", NetworkEventRow.extension(extensionRemoval));
        assertEquals("G", NetworkEventRow.id(extensionRemoval));
    }
}
