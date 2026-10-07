/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.network.adders.AbstractSimpleAdder;
import com.powsybl.dataframe.update.DoubleSeries;
import com.powsybl.dataframe.update.IntSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.regulation.RegulationMode;

import java.util.Collections;
import java.util.List;

/**
 * Creates the former {@code voltageRegulation} extension of batteries as a {@link com.powsybl.iidm.network.regulation.VoltageRegulation}
 * of the battery, see {@link VoltageRegulationDataframeProvider}.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class VoltageRegulationDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.booleans("voltage_regulator_on"),
            SeriesMetadata.doubles("target_v"),
            SeriesMetadata.strings("regulated_element_id")

    );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class VoltageRegulationSerie {
        private final StringSeries id;
        private final IntSeries voltageRegulatorOn;
        private final DoubleSeries targetV;
        private final StringSeries regulatedElement;

        VoltageRegulationSerie(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.voltageRegulatorOn = dataframe.getInts("voltage_regulator_on");
            this.targetV = dataframe.getDoubles("target_v");
            this.regulatedElement = dataframe.getStrings("regulated_element_id");
        }

        void create(Network network, int row) {
            String batteryId = this.id.get(row);
            Battery battery = network.getBattery(batteryId);
            if (battery == null) {
                throw new PowsyblException("Battery '" + batteryId + "' not found");
            }
            if (voltageRegulatorOn == null) {
                throw new PowsyblException("Voltage regulator status is not defined");
            }
            boolean on = voltageRegulatorOn.get(row) != 0;
            Terminal terminal = regulatedElement != null ? getTerminal(network, regulatedElement.get(row), batteryId) : null;
            double target = targetV != null ? targetV.get(row) : Double.NaN;
            boolean created = battery.getVoltageRegulation() == null;
            // built not regulating: core reports no event for a regulation its builder creates regulating, the
            // switch-on goes through the setter (review 21 R3-M1)
            if (terminal != null && terminal != battery.getTerminal()) {
                battery.newVoltageRegulation()
                        .withMode(RegulationMode.VOLTAGE)
                        .withRegulating(false)
                        .withTerminal(terminal)
                        .withTargetValue(target)
                        .build();
            } else {
                if (!Double.isNaN(target)) {
                    battery.setLocalTargetV(target);
                }
                battery.newVoltageRegulation()
                        .withMode(RegulationMode.VOLTAGE)
                        .withRegulating(false)
                        .build();
            }
            if (on) {
                try {
                    battery.getVoltageRegulation().setRegulating(true);
                } catch (RuntimeException e) {
                    if (created) {
                        battery.removeVoltageRegulation();
                    }
                    throw e;
                }
            }
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        VoltageRegulationSerie series = new VoltageRegulationSerie(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }

    protected static Terminal getTerminal(Network network, String regulatedElement, String id) {
        String targetElement = regulatedElement == null || regulatedElement.isEmpty() ? id : regulatedElement;
        Identifiable<?> identifiable = network.getIdentifiable(targetElement);
        if (identifiable instanceof Injection) {
            return ((Injection<?>) identifiable).getTerminal();
        } else {
            throw new UnsupportedOperationException("Cannot set regulated element to " + regulatedElement +
                    ": the regulated element may only be a busbar section or an injection.");
        }

    }
}
