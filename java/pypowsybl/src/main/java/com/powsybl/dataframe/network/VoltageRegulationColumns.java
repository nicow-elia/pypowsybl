/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import com.powsybl.python.network.NetworkUtil;

import java.util.function.Consumer;

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

    /** The voltage target as core reads it: the regulation's target value with a terminal, the local one otherwise. */
    public static double getTargetV(VoltageRegulationHolder<?> holder) {
        return holder.getRegulatingTargetV();
    }

    /** Writes the voltage target where {@link #getTargetV} reads it. */
    public static void setTargetV(VoltageRegulationHolder<?> holder, double targetV) {
        if (holder.hasRegulatingTerminal() && (holder.isWithMode(VOLTAGE) || holder.isWithMode(VOLTAGE_PER_REACTIVE_POWER))) {
            holder.getVoltageRegulation().setTargetValue(targetV);
        } else {
            holder.setLocalTargetV(targetV);
        }
    }

    /** The reactive power target shown: the local one for generators and batteries, as core reads it otherwise. */
    public static double getTargetQ(VoltageRegulationHolder<?> holder) {
        return showsLocalTargetQ(holder) ? holder.getLocalTargetQ() : holder.getRegulatingTargetQ();
    }

    /** Writes the reactive power target where {@link #getTargetQ} reads it. */
    public static void setTargetQ(VoltageRegulationHolder<?> holder, double targetQ) {
        if (!showsLocalTargetQ(holder) && holder.hasRegulatingTerminal() && holder.isWithMode(REACTIVE_POWER)) {
            holder.getVoltageRegulation().setTargetValue(targetQ);
        } else {
            holder.setLocalTargetQ(targetQ);
        }
    }

    /** Regulating in mode {@link RegulationMode#VOLTAGE}. */
    public static boolean isVoltageRegulatorOn(VoltageRegulationHolder<?> holder) {
        return holder.isRegulatingWithMode(VOLTAGE);
    }

    /**
     * Switching on regulates in mode {@link RegulationMode#VOLTAGE} (a regulation is created when there is none, a
     * regulation in another mode is switched as by {@link #setMode}); switching off clears the regulating flag, except
     * for a VSC station regulating at its own terminal, which then regulates its reactive power there.
     * The value shown unchanged is a no-op; a refused switch leaves the equipment as it was.
     */
    public static void setVoltageRegulatorOn(VoltageRegulationHolder<?> holder, boolean on) {
        if (on == isVoltageRegulatorOn(holder)) {
            return;
        }
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (on) {
            switchRegulation(holder, VOLTAGE, true);
        } else if (regulatesReactivePowerWhenOff(holder)) {
            // a CGMES VsConverter has no control flag: off means following target_q, a reactive power regulation at
            // the station's own terminal, which the change exports carry (qPccControl reactivePcc)
            if (!regulation.isWithTerminal()) {
                regulation.setTerminal(holder.getTerminal(), holder.getRegulatingTargetV());
            }
            switchRegulation(holder, REACTIVE_POWER, true);
        } else {
            regulation.setRegulating(false);
        }
    }

    /** A VSC station regulating at its own terminal (or able to get one: a single variant) with a reactive target. */
    private static boolean regulatesReactivePowerWhenOff(VoltageRegulationHolder<?> holder) {
        if (!(holder instanceof VscConverterStation station) || Double.isNaN(getTargetQ(station))) {
            return false;
        }
        return station.getVoltageRegulation().isWithTerminal()
                ? station.getRegulatingTerminal() == station.getTerminal()
                : station.getNetwork().getVariantManager().getVariantIds().size() == 1;
    }

    /** The mode of the regulation, null without regulation (or without mode in the working variant). */
    public static RegulationMode getMode(VoltageRegulationHolder<?> holder) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        return regulation != null ? regulation.getMode() : null;
    }

    /**
     * Changes the mode and keeps the targets shown and the regulating flag; creates a non regulating regulation when
     * there is none. The mode shown unchanged is a no-op; a refused switch leaves the equipment as it was.
     */
    public static void setMode(VoltageRegulationHolder<?> holder, RegulationMode mode) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation == null || regulation.getMode() != mode) {
            switchRegulation(holder, mode, regulation != null && regulation.isRegulating());
        }
    }

    /**
     * Sets the regulating flag; a compensator without regulation that starts regulating gets one in mode
     * {@link RegulationMode#REACTIVE_POWER} (as core's {@code StaticVarCompensator.setRegulating}; the SVC
     * {@code regulating} column is the only one that gets here without a regulation). The flag shown is a no-op.
     */
    public static void setRegulating(VoltageRegulationHolder<?> holder, boolean regulating) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation != null) {
            if (regulation.isRegulating() != regulating) {
                regulation.setRegulating(regulating);
            }
        } else if (regulating) {
            withNewRegulation(holder, REACTIVE_POWER, created -> created.setRegulating(true));
        }
    }

    /**
     * Puts the regulation in the given mode and regulating state, all or nothing, keeping the targets shown: the
     * target of the new mode goes into the regulation (when it has a terminal), the one of the old mode stays shown
     * through the local target. The refusals core can make after the mode is set (an undefined target to regulate
     * with) are checked before anything is written, so a refused switch leaves the equipment as it was. The
     * regulating flag is not cleared on the way: a non regulating regulation needs local targets the equipment may
     * not have.
     */
    private static void switchRegulation(VoltageRegulationHolder<?> holder, RegulationMode mode, boolean regulating) {
        double targetV = getTargetV(holder);
        double targetQ = getTargetQ(holder);
        double target = mode == REACTIVE_POWER ? targetQ : targetV;
        if (regulating && Double.isNaN(target)) {
            throw new PowsyblException(nameOf(holder) + ": cannot regulate in mode " + mode + ", its target is undefined (NaN)");
        }
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation == null) {
            // without regulation both targets are the local ones, before and after
            withNewRegulation(holder, mode, created -> {
                if (regulating) {
                    created.setRegulating(true);
                }
            });
            return;
        }
        regulation.setMode(mode); // first step that can be refused: nothing changed before it
        if (regulation.isWithTerminal()) {
            regulation.setTargetValue(target);
        }
        if (Double.compare(getTargetV(holder), targetV) != 0) {
            holder.setLocalTargetV(targetV);
        }
        if (Double.compare(getTargetQ(holder), targetQ) != 0) {
            holder.setLocalTargetQ(targetQ);
        }
        if (regulation.isRegulating() != regulating) {
            regulation.setRegulating(regulating);
        }
    }

    /**
     * The one way this class creates a regulation: in the given mode, not regulating, then {@code change} is applied
     * through the regulation's setters. Core reports no event for a regulation created by its builder (a regulation
     * built regulating, or with a deadband, would reach no change recorder and no change export), but it does for the
     * setters. A refused change removes the regulation again, which leaves the equipment as it was and nothing
     * recorded.
     */
    private static void withNewRegulation(VoltageRegulationHolder<?> holder, RegulationMode mode, Consumer<VoltageRegulation> change) {
        VoltageRegulation created = holder.newVoltageRegulation().withMode(mode).withRegulating(false).build();
        try {
            change.accept(created);
        } catch (RuntimeException e) {
            holder.removeVoltageRegulation();
            throw e;
        }
    }

    private static String nameOf(VoltageRegulationHolder<?> holder) {
        return holder instanceof Identifiable<?> identifiable ? "'" + identifiable.getId() + "'" : String.valueOf(holder);
    }

    /** The equipment of the regulating terminal, the holder itself when the regulation has no terminal. */
    public static String getRegulatedElementId(VoltageRegulationHolder<?> holder) {
        return NetworkUtil.getRegulatedElementId(holder::getRegulatingTerminal);
    }

    /**
     * Sets the regulating terminal from an element id, which must be an injection's ({@link NetworkUtil}); the id
     * shown unchanged is a no-op, also when the current terminal is not an injection's. The battery
     * {@code voltageRegulation} view resolves its ids itself (an empty id meaning the battery, as the former
     * extension) and calls {@link #setRegulatingTerminal}, whose no-op test compares terminals.
     */
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
            double target = holder.getRegulatingTargetV();
            withNewRegulation(holder, VOLTAGE, created -> created.setTerminal(terminal, target));
        } else {
            double target = regulation.getMode() == REACTIVE_POWER ? holder.getRegulatingTargetQ() : holder.getRegulatingTargetV();
            regulation.setTerminal(terminal, target);
        }
    }

    /** The deadband of the regulation, NaN without regulation. */
    public static double getTargetDeadband(VoltageRegulationHolder<?> holder) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        return regulation != null ? regulation.getTargetDeadband() : Double.NaN;
    }

    /** Sets the deadband; creates a non regulating regulation for a defined deadband when there is none. */
    public static void setTargetDeadband(VoltageRegulationHolder<?> holder, double targetDeadband) {
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation != null) {
            regulation.setTargetDeadband(targetDeadband);
        } else if (!Double.isNaN(targetDeadband)) {
            withNewRegulation(holder, VOLTAGE, created -> created.setTargetDeadband(targetDeadband));
        }
    }

    private static boolean showsLocalTargetQ(VoltageRegulationHolder<?> holder) {
        return holder instanceof Generator || holder instanceof Battery;
    }
}
