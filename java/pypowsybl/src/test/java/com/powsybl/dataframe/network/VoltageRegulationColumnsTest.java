/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network;

import com.powsybl.dataframe.DataframeElementType;
import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.network.extensions.VoltageRegulationDataframeAdder;
import com.powsybl.dataframe.update.TestDoubleSeries;
import com.powsybl.dataframe.update.TestIntSeries;
import com.powsybl.dataframe.update.TestStringSeries;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static com.powsybl.dataframe.network.VoltageRegulationColumns.*;
import static com.powsybl.iidm.network.regulation.RegulationMode.REACTIVE_POWER;
import static com.powsybl.iidm.network.regulation.RegulationMode.VOLTAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules of {@link VoltageRegulationColumns}, one test each, on the four-substations network: targets read and
 * written where core reads them, unchanged writes are no-ops, a new regulated element keeps its target, a mode switch
 * keeps both targets shown and is all or nothing.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class VoltageRegulationColumnsTest {

    private Network network;

    @BeforeEach
    void setUp() {
        network = FourSubstationsNodeBreakerFactory.create();
    }

    private static String xiidm(Network network) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        NetworkSerDe.write(network, os);
        return os.toString(StandardCharsets.UTF_8);
    }

    /** VSC2 as a CGMES import leaves it: reactive power regulation at its terminal, target 120, no voltage target. */
    private VscConverterStation reactiveVsc2() {
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        vsc2.newVoltageRegulation().withMode(REACTIVE_POWER).withTerminal(vsc2.getTerminal())
                .withTargetValue(120).withRegulating(true).build();
        vsc2.setLocalTargetV(Double.NaN);
        return vsc2;
    }

    @Test
    void targetsAreWrittenWhereCoreReadsThem() {
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC");
        setTargetV(svc, 401);
        assertEquals(401, svc.getLocalTargetV());
        setRegulatingTerminal(svc, network.getGenerator("GH1").getTerminal());
        setTargetV(svc, 402);
        assertEquals(402, svc.getVoltageRegulation().getTargetValue());
        assertEquals(401, svc.getLocalTargetV());
        assertEquals(402, getTargetV(svc));
    }

    @Test
    void unchangedVoltageRegulatorOnIsANoOp() {
        reactiveVsc2();
        String before = xiidm(network);
        setVoltageRegulatorOn(network.getVscConverterStation("VSC2"), false);
        Generator gh1 = network.getGenerator("GH1");
        setVoltageRegulatorOn(gh1, isVoltageRegulatorOn(gh1));
        assertEquals(before, xiidm(network));
    }

    @Test
    void unchangedRegulatedElementIsANoOp() {
        String before = xiidm(network);
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        setRegulatedElementId(vsc2, network, getRegulatedElementId(vsc2));
        assertNull(vsc2.getVoltageRegulation());
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "other");
        Generator gh1 = network.getGenerator("GH1");
        setRegulatedElementId(gh1, network, getRegulatedElementId(gh1));
        network.getVariantManager().removeVariant("other");
        assertEquals(before, xiidm(network));
    }

    @Test
    void unchangedUndefinedDeadbandIsANoOp() {
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        setTargetDeadband(vsc2, getTargetDeadband(vsc2));
        assertNull(vsc2.getVoltageRegulation());
    }

    @Test
    void regulatedElementKeepsTheTargetInEitherOrder() {
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        setTargetV(vsc2, 1);
        setRegulatedElementId(vsc2, network, "VSC1");
        assertEquals(1, getTargetV(vsc2));
        assertEquals("VSC1", getRegulatedElementId(vsc2));

        setUp();
        vsc2 = network.getVscConverterStation("VSC2");
        setRegulatedElementId(vsc2, network, "VSC1");
        setTargetV(vsc2, 1);
        assertEquals(1, getTargetV(vsc2));
    }

    @Test
    void modeSwitchKeepsBothTargets() {
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC");
        setRegulatingTerminal(svc, svc.getTerminal());
        setTargetV(svc, 401);
        setTargetQ(svc, -30);
        setMode(svc, REACTIVE_POWER);
        assertEquals(REACTIVE_POWER, getMode(svc));
        assertTrue(svc.isRegulating());
        assertEquals(401, getTargetV(svc));
        assertEquals(-30, getTargetQ(svc));
        setMode(svc, VOLTAGE);
        assertEquals(401, getTargetV(svc));
        assertEquals(-30, getTargetQ(svc));
    }

    @Test
    void switchingOnKeepsTheLocalReactiveTargetOfAGenerator() {
        Generator gh1 = network.getGenerator("GH1");
        double localTargetQ = gh1.getLocalTargetQ();
        double targetV = getTargetV(gh1);
        gh1.newVoltageRegulation().withMode(REACTIVE_POWER).withTerminal(network.getGenerator("GH2").getTerminal())
                .withTargetValue(55).withRegulating(true).build();
        setVoltageRegulatorOn(gh1, true);
        assertTrue(isVoltageRegulatorOn(gh1));
        assertEquals(localTargetQ, getTargetQ(gh1));
        assertEquals(targetV, getTargetV(gh1));
    }

    @Test
    void refusedModeSwitchLeavesTheNetworkUnchanged() {
        // regulating in voltage mode, no reactive power target: reactive power mode is refused
        String before = xiidm(network);
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC");
        assertThrows(RuntimeException.class, () -> setMode(svc, REACTIVE_POWER));
        assertEquals(before, xiidm(network));
    }

    @Test
    void refusedSwitchOnLeavesTheNetworkUnchanged() {
        VscConverterStation vsc2 = reactiveVsc2();
        String before = xiidm(network);
        assertThrows(RuntimeException.class, () -> setVoltageRegulatorOn(vsc2, true));
        assertEquals(before, xiidm(network));
    }

    @Test
    void refusedSwitchOnWithoutRegulationCreatesNone() {
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        vsc2.setLocalTargetV(Double.NaN);
        String before = xiidm(network);
        assertThrows(RuntimeException.class, () -> setVoltageRegulatorOn(vsc2, true));
        assertEquals(before, xiidm(network));
    }

    @Test
    void remoteReactiveStationWithoutLocalReactiveTargetCanBeSwitchedOn() {
        // what core's adder makes of a station created with the regulator off, a reactive target and a regulated element
        VscConverterStation vsc = network.getVscConverterStation("VSC2");
        vsc.newVoltageRegulation().withMode(REACTIVE_POWER).withTerminal(network.getGenerator("GH2").getTerminal())
                .withTargetValue(3).withRegulating(true).build();
        vsc.setLocalTargetV(403);
        vsc.setLocalTargetQ(Double.NaN);
        setVoltageRegulatorOn(vsc, true);
        assertTrue(isVoltageRegulatorOn(vsc));
        assertEquals(403, getTargetV(vsc));
        assertEquals(3, getTargetQ(vsc));
    }

    @Test
    void emptyModeOfACompensatorWithoutRegulationIsWrittenBackUnchanged() {
        network.getVoltageLevel("S1VL2").newStaticVarCompensator().setId("SVC9").setNode(90)
                .setBmin(-0.01).setBmax(0.01).add();
        String before = xiidm(network);
        NetworkDataframeMapper mapper = NetworkDataframes.getDataframeMapper(DataframeElementType.STATIC_VAR_COMPENSATOR);
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("SVC9"));
        dataframe.addSeries("regulation_mode", false, new TestStringSeries(""));
        mapper.updateSeries(network, dataframe, NetworkDataframeContext.DEFAULT);
        assertEquals(before, xiidm(network));
        assertNull(network.getStaticVarCompensator("SVC9").getVoltageRegulation());
    }
    // ---------------------------------------------------------------- review 21 round 3 (R3-M1): creations are recorded

    /** The updates the network reports, as "id.attribute=new value" (what the change recorder sees). */
    private List<String> listen() {
        List<String> updates = new ArrayList<>();
        network.addListener(new NetworkListener() {
            @Override
            public void onUpdate(Identifiable<?> identifiable, String attribute, String variantId, Object oldValue, Object newValue) {
                updates.add(identifiable.getId() + "." + attribute + "=" + newValue);
            }
        });
        return updates;
    }

    private static final String REGULATING_ON = ".VoltageRegulation.isRegulating=true";

    @Test
    void switchingOnAGeneratorWithoutRegulationIsRecorded() {
        Generator gth1 = network.getGenerator("GTH1");
        gth1.removeVoltageRegulation();
        List<String> updates = listen();
        setVoltageRegulatorOn(gth1, true);
        assertTrue(isVoltageRegulatorOn(gth1));
        assertTrue(updates.contains("GTH1" + REGULATING_ON), updates::toString);
    }

    @Test
    void switchingOnAVscWithoutRegulationIsRecorded() {
        VscConverterStation vsc2 = network.getVscConverterStation("VSC2");
        vsc2.setLocalTargetV(400);
        List<String> updates = listen();
        setVoltageRegulatorOn(vsc2, true);
        assertTrue(updates.contains("VSC2" + REGULATING_ON), updates::toString);
    }

    @Test
    void anSvcWithoutRegulationStartingToRegulateIsRecorded() {
        network.getVoltageLevel("S1VL2").newStaticVarCompensator().setId("SVC9").setNode(90)
                .setBmin(-0.01).setBmax(0.01).setLocalTargetQ(5).add();
        List<String> updates = listen();
        setRegulating(network.getStaticVarCompensator("SVC9"), true);
        assertTrue(updates.contains("SVC9" + REGULATING_ON), updates::toString);
    }

    @Test
    void aShuntWithoutRegulationSwitchedOnIsRecorded() {
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        shunt.removeVoltageRegulation();
        List<String> updates = listen();
        setTargetV(shunt, 401);
        setTargetDeadband(shunt, 1);
        setVoltageRegulatorOn(shunt, true);
        assertTrue(updates.contains("SHUNT.VoltageRegulation.TargetDeadband=1.0"), updates::toString);
        assertTrue(updates.contains("SHUNT" + REGULATING_ON), updates::toString);
    }

    @Test
    void aRegulatedElementOnEquipmentWithoutRegulationIsRecorded() {
        List<String> updates = listen();
        setRegulatedElementId(network.getVscConverterStation("VSC2"), network, "VSC1");
        assertTrue(updates.stream().anyMatch(u -> u.startsWith("VSC2.VoltageRegulation.Terminal=")), updates::toString);
    }

    @Test
    void aBatteryVoltageRegulationCreatedRegulatingIsRecorded() {
        network.getVoltageLevel("S1VL2").newBattery().setId("B9").setNode(92).setMinP(-10).setMaxP(10)
                .setTargetP(1).setLocalTargetQ(1).add();
        List<String> updates = listen();
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("B9"));
        dataframe.addSeries("voltage_regulator_on", false, new TestIntSeries(1));
        dataframe.addSeries("target_v", false, new TestDoubleSeries(401));
        new VoltageRegulationDataframeAdder().addElements(network, dataframe);
        assertTrue(isVoltageRegulatorOn(network.getBattery("B9")));
        assertTrue(updates.contains("B9" + REGULATING_ON), updates::toString);
    }

    @Test
    void refusedSwitchOnOfAShuntWithoutDeadbandLeavesNothing() {
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        shunt.removeVoltageRegulation();
        setTargetV(shunt, 401);
        String before = xiidm(network);
        List<String> updates = listen();
        assertThrows(RuntimeException.class, () -> setVoltageRegulatorOn(shunt, true));
        assertEquals(before, xiidm(network));
        assertTrue(updates.isEmpty(), updates::toString);
    }
}
