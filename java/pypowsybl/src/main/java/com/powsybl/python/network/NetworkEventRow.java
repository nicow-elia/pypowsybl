/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.iidm.network.events.CreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionRemovalNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.PropertiesUpdateNetworkEvent;
import com.powsybl.iidm.network.events.RemovalNetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.events.VariantNetworkEvent;

import java.util.Objects;

/**
 * One row of the dataframe view of the changes a {@link NetworkEventRecording} recorded.
 *
 * <p>A {@link NetworkEvent} is a sealed-by-convention family of records with different shapes: an update carries an
 * attribute and two values, a creation only an identifier, a variant event no identifier at all. The Python side
 * wants a single rectangular table, so every column is flattened to a string and the columns a given event kind does
 * not have are empty rather than absent. The values are rendered with {@link Objects#toString(Object, String)}, that
 * is with the {@code toString()} of whatever the IIDM setter was given, which is what a human debugging a recording
 * wants to see; they are not meant to be parsed back.</p>
 *
 * @param index the position of the event in the recording, which is the order in which the changes were made
 * @param event the recorded change
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record NetworkEventRow(int index, NetworkEvent event) {

    private static final String NONE = "";

    public NetworkEventRow {
        Objects.requireNonNull(event);
    }

    /** The kind of change, the name of {@link NetworkEvent.Type}. */
    public static String type(NetworkEventRow row) {
        return row.event().getType().name();
    }

    /** The identifier of the changed network element, empty for a variant event, which has none. */
    public static String id(NetworkEventRow row) {
        return switch (row.event()) {
            case UpdateNetworkEvent e -> e.id();
            case ExtensionUpdateNetworkEvent e -> e.id();
            case PropertiesUpdateNetworkEvent e -> e.id();
            case CreationNetworkEvent e -> e.id();
            case RemovalNetworkEvent e -> e.id();
            case ExtensionCreationNetworkEvent e -> e.id();
            case ExtensionRemovalNetworkEvent e -> e.id();
            default -> NONE;
        };
    }

    /** The name of the extension a change applies to, empty when the change applies to the element itself. */
    public static String extension(NetworkEventRow row) {
        return switch (row.event()) {
            case ExtensionUpdateNetworkEvent e -> Objects.toString(e.extensionName(), NONE);
            case ExtensionCreationNetworkEvent e -> Objects.toString(e.extensionName(), NONE);
            case ExtensionRemovalNetworkEvent e -> Objects.toString(e.extensionName(), NONE);
            default -> NONE;
        };
    }

    /**
     * The changed attribute, in IIDM spelling, for example {@code p0}, {@code open} or
     * {@code ratioTapChanger.tapPosition}. For a property update it is the key of the property; for the changes that
     * do not name an attribute, such as a creation, it is empty.
     */
    public static String attribute(NetworkEventRow row) {
        return switch (row.event()) {
            case UpdateNetworkEvent e -> e.attribute();
            case ExtensionUpdateNetworkEvent e -> Objects.toString(e.attribute(), NONE);
            case PropertiesUpdateNetworkEvent e -> Objects.toString(e.key(), NONE);
            default -> NONE;
        };
    }

    /**
     * The variant the change was made on, empty when the change does not belong to a variant. For a variant event it
     * is the variant the event is about, that is its target variant.
     */
    public static String variant(NetworkEventRow row) {
        return switch (row.event()) {
            case UpdateNetworkEvent e -> Objects.toString(e.variantId(), NONE);
            case ExtensionUpdateNetworkEvent e -> Objects.toString(e.variantId(), NONE);
            case VariantNetworkEvent e -> Objects.toString(e.targetVariantId(), NONE);
            default -> NONE;
        };
    }

    /** The value before the change, empty when the change has none. */
    public static String oldValue(NetworkEventRow row) {
        return switch (row.event()) {
            case UpdateNetworkEvent e -> Objects.toString(e.oldValue(), NONE);
            case ExtensionUpdateNetworkEvent e -> Objects.toString(e.oldValue(), NONE);
            case PropertiesUpdateNetworkEvent e -> Objects.toString(e.oldValue(), NONE);
            case VariantNetworkEvent e -> Objects.toString(e.sourceVariantId(), NONE);
            default -> NONE;
        };
    }

    /**
     * The value after the change, empty when the change has none. For a variant event it is the kind of variant
     * operation, which is what distinguishes a created from an overwritten or a removed variant.
     */
    public static String newValue(NetworkEventRow row) {
        return switch (row.event()) {
            case UpdateNetworkEvent e -> Objects.toString(e.newValue(), NONE);
            case ExtensionUpdateNetworkEvent e -> Objects.toString(e.newValue(), NONE);
            case PropertiesUpdateNetworkEvent e -> Objects.toString(e.newValue(), NONE);
            case VariantNetworkEvent e -> Objects.toString(e.eventType(), NONE);
            default -> NONE;
        };
    }
}
