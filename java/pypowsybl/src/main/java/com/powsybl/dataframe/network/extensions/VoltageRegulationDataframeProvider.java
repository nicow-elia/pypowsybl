/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.network.ExtensionInformation;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframeMapperBuilder;
import com.powsybl.dataframe.network.VoltageRegulationColumns;
import com.powsybl.dataframe.network.adders.NetworkElementAdder;
import com.powsybl.iidm.network.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.powsybl.dataframe.network.extensions.VoltageRegulationDataframeAdder.getTerminal;

/**
 * The former {@code voltageRegulation} extension of batteries.
 * <p>
 * Since powsybl-core 7.5 (voltage regulation refactoring, powsybl-core#3699) the extension no longer exists: a
 * battery holds a {@link com.powsybl.iidm.network.regulation.VoltageRegulation} itself. This provider keeps the
 * extension name and columns as a view on it, with the rules of the IIDM reader and writer of the former extension:
 * a battery "has the extension" when it has a voltage regulation; {@code voltage_regulator_on} is "regulating in mode
 * {@link com.powsybl.iidm.network.regulation.RegulationMode#VOLTAGE}"; {@code target_v} is the regulating voltage
 * target (the regulation's target value when it has a remote terminal, the battery's local target otherwise);
 * {@code regulated_element_id} is the regulating terminal (the battery's own one when the regulation has none).
 * Removing the extension removes the voltage regulation. The columns go through {@link VoltageRegulationColumns}.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class VoltageRegulationDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    static final String NAME = "voltageRegulation";

    @Override
    public String getExtensionName() {
        return NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(NAME, "it allows to specify the voltage regulation mode for batteries",
                "index : id (str), voltage_regulator_on (bool), target_v (float), regulated_element_id (str)");
    }

    private Stream<Battery> itemsStream(Network network) {
        return network.getBatteryStream().filter(battery -> battery.getVoltageRegulation() != null);
    }

    private Battery getOrThrow(Network network, String id) {
        Battery battery = network.getBattery(id);
        if (battery == null) {
            throw new PowsyblException("Battery '" + id + "' not found");
        }
        if (battery.getVoltageRegulation() == null) {
            throw new PowsyblException("Voltage regulation extension for battery '" + id + "' not found");
        }
        return battery;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", Battery::getId)
                .booleans("voltage_regulator_on", VoltageRegulationColumns::isVoltageRegulatorOn, VoltageRegulationColumns::setVoltageRegulatorOn)
                .doubles("target_v", (battery, context) -> VoltageRegulationColumns.getTargetV(battery),
                        (battery, targetV, context) -> VoltageRegulationColumns.setTargetV(battery, targetV))
                .strings("regulated_element_id", VoltageRegulationColumns::getRegulatedElementId,
                        (battery, id) -> VoltageRegulationColumns.setRegulatingTerminal(battery, getTerminal(battery.getNetwork(), id, battery.getId())))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getBattery)
                .filter(Objects::nonNull)
                .forEach(Battery::removeVoltageRegulation);
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new VoltageRegulationDataframeAdder();
    }
}
