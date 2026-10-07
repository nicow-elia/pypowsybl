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
import com.powsybl.dataframe.network.adders.NetworkElementAdder;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The former {@code voltagePerReactivePowerControl} extension of static var compensators.
 * <p>
 * Since powsybl-core 7.5 (voltage regulation refactoring, powsybl-core#3699) the extension no longer exists: its
 * slope is the {@link VoltageRegulation#getSlope() slope} of the compensator's voltage regulation in mode
 * {@link RegulationMode#VOLTAGE_PER_REACTIVE_POWER}. This provider keeps the extension name and columns as a view on
 * that state, with the same rule as the IIDM reader of the former extension: a compensator "has the extension" when
 * its voltage regulation is in that mode and has a slope; creating it sets the slope and the mode, removing it sets
 * the mode back to {@link RegulationMode#VOLTAGE} and clears the slope.
 *
 * @author Hugo Kulesza {@literal <hugo.kulesza at rte-france.com>}
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class VoltagePerReactivePowerControlDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    static final String NAME = "voltagePerReactivePowerControl";

    @Override
    public String getExtensionName() {
        return NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(NAME,
                "Models the voltage control static var compensators",
                "index : id (str), slope (float)");
    }

    static boolean hasControl(StaticVarCompensator svc) {
        VoltageRegulation regulation = svc.getVoltageRegulation();
        return regulation != null
                && regulation.getMode() == RegulationMode.VOLTAGE_PER_REACTIVE_POWER
                && !Double.isNaN(regulation.getSlope());
    }

    private Stream<StaticVarCompensator> itemsStream(Network network) {
        return network.getStaticVarCompensatorStream().filter(VoltagePerReactivePowerControlDataframeProvider::hasControl);
    }

    private StaticVarCompensator getOrThrow(Network network, String id) {
        StaticVarCompensator svc = network.getStaticVarCompensator(id);
        if (svc == null) {
            throw new PowsyblException("Static var compensator '" + id + "' not found");
        }
        if (!hasControl(svc)) {
            throw new PowsyblException("Static var compensator '" + id + "' has no VoltagePerReactivePowerControl extension");
        }
        return svc;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", StaticVarCompensator::getId)
                .doubles("slope", svc -> svc.getVoltageRegulation().getSlope(),
                    (svc, val, context) -> svc.getVoltageRegulation().setSlope(val))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getStaticVarCompensator)
                .filter(Objects::nonNull)
                .filter(VoltagePerReactivePowerControlDataframeProvider::hasControl)
                .forEach(svc -> svc.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setSlope(Double.NaN));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new VoltagePerReactivePowerControlDataframeAdder();
    }

}
