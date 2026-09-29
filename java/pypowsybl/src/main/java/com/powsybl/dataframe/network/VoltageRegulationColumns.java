/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network;

import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import com.powsybl.python.network.NetworkUtil;

import static com.powsybl.iidm.network.regulation.RegulationMode.REACTIVE_POWER;
import static com.powsybl.iidm.network.regulation.RegulationMode.VOLTAGE;
import static com.powsybl.iidm.network.regulation.RegulationMode.VOLTAGE_PER_REACTIVE_POWER;

/**
 * The regulation columns of the dataframes ({@code target_v}, {@code target_q}, {@code voltage_regulator_on},
 * {@code regulation_mode}, {@code regulating}, {@code regulated_element_id}, {@code target_deadband}) on the
 * {@link VoltageRegulation} of a {@link VoltageRegulationHolder}.
 * <p>
 * Up to powsybl-core 7.4 these columns were independent attributes of the equipment. Since core 7.5 (#3699) an
 * equipment has a local voltage and a local reactive power target and at most one voltage regulation, with a mode, a
 * regulating flag, a terminal and ONE target value, which replaces the local target of its mode as soon as the
 * regulation has a terminal. The deprecated setters of core that bridge the old attributes do not keep them
 * independent (a station in reactive power mode written back {@code voltage_regulator_on=False} is put in voltage mode
 * and its reactive target is read as a voltage; setting a regulated element creates a regulation without target
 * value, which loses the target written before). The columns therefore go through the regulation API with three rules:
 * <ul>
 *   <li>a target is read and written where core reads it: the regulation's target value when the regulation has a
 *       terminal and a mode of that target, the local target otherwise;</li>
 *   <li>a mode, regulating flag or regulated element written unchanged does nothing, so a dataframe written back
 *       unchanged leaves the network (and a recording of its changes) untouched;</li>
 *   <li>a change of mode or of regulated element keeps the targets the columns show.</li>
 * </ul>
 * Generators and batteries show their local reactive power target in {@code target_q}: the target of a remote
 * reactive power regulation (formerly the extension {@code generatorRemoteReactivePowerControl}) has no column.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class VoltageRegulationColumns {

    private VoltageRegulationColumns() {
    }

    public static double getTargetV(VoltageRegulationHolder<?> holder) {
        return holder.getRegulatingTargetV();
    }

    public static void setTargetV(VoltageRegulationHolder<?> holder, double targetV) {
        if (holder.hasRegulatingTerminal() && (holder.isWithMode(VOLTAGE) || holder.isWithMode(VOLTAGE_PER_REACTIVE_POWER))) {
            holder.getVoltageRegulation().setTargetValue(targetV);
        } else {
            holder.setLocalTargetV(targetV);
        }
    }

    public static double getTargetQ(VoltageRegulationHolder<?> holder) {
        return showsLocalTargetQ(holder) ? holder.getLocalTargetQ() : holder.getRegulatingTargetQ();
    }

    public static void setTargetQ(VoltageRegulationHolder<?> holder, double targetQ) {
        if (!showsLocalTargetQ(holder) && holder.hasRegulatingTerminal() && holder.isWithMode(REACTIVE_POWER)) {
            holder.getVoltageRegulation().setTargetValue(targetQ);
        } else {
            holder.setLocalTargetQ(targetQ);
        }
    }

    public static boolean isVoltageRegulatorOn(VoltageRegulationHolder<?> holder) {
        return holder.isRegulatingWithMode(VOLTAGE);
    }

    public static void setVoltageRegulatorOn(VoltageRegulationHolder<?> holder, boolean on) {
        if (on != isVoltageRegulatorOn(holder)) {
            if (on) {
                setMode(holder, VOLTAGE);
            }
            setRegulating(holder, on);
        }
    }

    public static RegulationMode getMode(VoltageRegulationHolder<?> holder) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        return regulation != null ? regulation.getMode() : null;
    }

    public static void setMode(VoltageRegulationHolder<?> holder, RegulationMode mode) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation == null) {
            holder.newVoltageRegulation().withMode(mode).withRegulating(false).build();
        } else if (regulation.getMode() != mode) {
            double targetV = getTargetV(holder);
            double targetQ = getTargetQ(holder);
            boolean regulating = regulation.isRegulating();
            if (regulating) {
                // the target value is only checked against the mode while regulating: the switch goes through a
                // non regulating state, in which the target of the old mode can be replaced by the one of the new mode
                regulation.setRegulating(false);
            }
            regulation.setMode(mode);
            if (Double.compare(getTargetV(holder), targetV) != 0) {
                setTargetV(holder, targetV);
            }
            if (Double.compare(getTargetQ(holder), targetQ) != 0) {
                setTargetQ(holder, targetQ);
            }
            if (regulating) {
                regulation.setRegulating(true);
            }
        }
    }

    public static void setRegulating(VoltageRegulationHolder<?> holder, boolean regulating) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation != null) {
            if (regulation.isRegulating() != regulating) {
                regulation.setRegulating(regulating);
            }
        } else if (regulating) {
            // as core's StaticVarCompensator.setRegulating, the only column that can get here without a regulation
            holder.newVoltageRegulation().withMode(REACTIVE_POWER).withRegulating(true).build();
        }
    }

    public static String getRegulatedElementId(VoltageRegulationHolder<?> holder) {
        return NetworkUtil.getRegulatedElementId(holder::getRegulatingTerminal);
    }

    public static void setRegulatedElementId(VoltageRegulationHolder<?> holder, Network network, String elementId) {
        if (!elementId.equals(getRegulatedElementId(holder))) {
            NetworkUtil.setRegulatingTerminal(terminal -> setRegulatingTerminal(holder, terminal), network, elementId);
        }
    }

    /**
     * Sets the regulating terminal with the target the regulation has for its mode at the current terminal, which is
     * the local target of that mode when there is no terminal yet. Core refuses it when the network has several
     * variants.
     */
    public static void setRegulatingTerminal(VoltageRegulationHolder<?> holder, Terminal terminal) {
        if (terminal == holder.getRegulatingTerminal()) {
            return;
        }
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation == null) {
            holder.newVoltageRegulation().withMode(VOLTAGE).withRegulating(false)
                    .withTerminal(terminal).withTargetValue(holder.getRegulatingTargetV()).build();
        } else {
            double target = regulation.getMode() == REACTIVE_POWER ? holder.getRegulatingTargetQ() : holder.getRegulatingTargetV();
            regulation.setTerminal(terminal, target);
        }
    }

    public static double getTargetDeadband(VoltageRegulationHolder<?> holder) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        return regulation != null ? regulation.getTargetDeadband() : Double.NaN;
    }

    public static void setTargetDeadband(VoltageRegulationHolder<?> holder, double targetDeadband) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation != null) {
            regulation.setTargetDeadband(targetDeadband);
        } else if (!Double.isNaN(targetDeadband)) {
            holder.newVoltageRegulation().withMode(VOLTAGE).withRegulating(false).withTargetDeadband(targetDeadband).build();
        }
    }

    private static boolean showsLocalTargetQ(VoltageRegulationHolder<?> holder) {
        return holder instanceof Generator || holder instanceof Battery;
    }
}
