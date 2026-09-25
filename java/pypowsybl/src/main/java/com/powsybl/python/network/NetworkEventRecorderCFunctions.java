/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.Directives;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import com.powsybl.python.commons.PyPowsyblApiHeader.ArrayPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.ExceptionHandlerPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.SeriesPointer;
import com.powsybl.python.commons.Util.PointerProvider;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;

import java.util.Map;
import java.util.function.IntSupplier;

import static com.powsybl.python.commons.Util.doCatch;

/**
 * The native entry points of the network event recorder, that is of {@link NetworkEventRecording}.
 *
 * <p>Every method here is a one liner delegating to {@link NetworkEventRecording}, because entry points cannot be
 * unit tested on the JVM: the logic lives in that class and is tested there. The handle these functions take is the
 * one {@link #createNetworkEventRecorder} returns; it is released by the generic {@code destroyObjectHandle} entry
 * point when the Python object holding it dies.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
@SuppressWarnings({"java:S1602", "java:S1604", "Convert2Lambda"})
@CContext(Directives.class)
public final class NetworkEventRecorderCFunctions {

    private NetworkEventRecorderCFunctions() {
    }

    private static NetworkEventRecording recording(ObjectHandle recorderHandle) {
        return ObjectHandles.getGlobal().get(recorderHandle);
    }

    @CEntryPoint(name = "createNetworkEventRecorder")
    public static ObjectHandle createNetworkEventRecorder(IsolateThread thread, ObjectHandle networkHandle,
                                                          ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                return ObjectHandles.getGlobal().create(new NetworkEventRecording(network));
            }
        });
    }

    @CEntryPoint(name = "startNetworkEventRecorder")
    public static void startNetworkEventRecorder(IsolateThread thread, ObjectHandle recorderHandle,
                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                recording(recorderHandle).start();
            }
        });
    }

    @CEntryPoint(name = "stopNetworkEventRecorder")
    public static void stopNetworkEventRecorder(IsolateThread thread, ObjectHandle recorderHandle,
                                                ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                recording(recorderHandle).stop();
            }
        });
    }

    @CEntryPoint(name = "clearNetworkEventRecorder")
    public static void clearNetworkEventRecorder(IsolateThread thread, ObjectHandle recorderHandle,
                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                recording(recorderHandle).clear();
            }
        });
    }

    @CEntryPoint(name = "getNetworkEventCount")
    public static int getNetworkEventCount(IsolateThread thread, ObjectHandle recorderHandle,
                                           ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new IntSupplier() {
            @Override
            public int getAsInt() {
                return recording(recorderHandle).getEventCount();
            }
        });
    }

    @CEntryPoint(name = "createNetworkEventsSeriesArray")
    public static ArrayPointer<SeriesPointer> createNetworkEventsSeriesArray(IsolateThread thread,
                                                                            ObjectHandle recorderHandle,
                                                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(Dataframes.networkEventsMapper(),
                        recording(recorderHandle).getEvents());
            }
        });
    }

    @CEntryPoint(name = "exportNetworkEventsToPartialSsh")
    public static CCharPointer exportNetworkEventsToPartialSsh(IsolateThread thread, ObjectHandle recorderHandle,
                                                               CCharPointerPointer optionKeysPtr, int optionKeysCount,
                                                               CCharPointerPointer optionValuesPtr,
                                                               int optionValuesCount,
                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public CCharPointer get() {
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                return CTypeUtil.toCharPtr(recording(recorderHandle).toPartialSsh(options));
            }
        });
    }

    @CEntryPoint(name = "exportNetworkEventsToCgmesDiff")
    public static CCharPointer exportNetworkEventsToCgmesDiff(IsolateThread thread, ObjectHandle recorderHandle,
                                                              CCharPointer profilePtr,
                                                              CCharPointerPointer optionKeysPtr, int optionKeysCount,
                                                              CCharPointerPointer optionValuesPtr,
                                                              int optionValuesCount,
                                                              ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public CCharPointer get() {
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                String profile = CTypeUtil.toStringOrNull(profilePtr);
                if (profile != null && profile.isEmpty()) {
                    profile = null;
                }
                return CTypeUtil.toCharPtr(recording(recorderHandle).toCgmesDiff(profile, options));
            }
        });
    }

    @CEntryPoint(name = "exportNetworkEventsToCgmesDiffs")
    public static PyPowsyblApiHeader.StringMap exportNetworkEventsToCgmesDiffs(IsolateThread thread,
                                                                              ObjectHandle recorderHandle,
                                                                              CCharPointerPointer optionKeysPtr,
                                                                              int optionKeysCount,
                                                                              CCharPointerPointer optionValuesPtr,
                                                                              int optionValuesCount,
                                                                              ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                return CTypeUtil.fromStringMap(recording(recorderHandle).toCgmesDiffs(options));
            }
        });
    }
}
