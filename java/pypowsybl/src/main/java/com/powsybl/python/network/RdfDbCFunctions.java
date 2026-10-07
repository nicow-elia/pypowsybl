/**
 * Copyright (c) 2026, Elia Group
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.network;

import com.powsybl.cgmes.rdfdb.RdfDbConnection;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.Directives;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import com.powsybl.python.commons.PyPowsyblApiHeader.ArrayPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.ExceptionHandlerPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.SeriesPointer;
import com.powsybl.python.commons.Util;
import com.powsybl.python.commons.Util.PointerProvider;
import com.powsybl.python.report.ReportCUtils;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.word.WordFactory;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.powsybl.python.commons.Util.doCatch;

/**
 * The native entry points of the split CGMES loading: CGMES files into an RDF database, and an RDF database into an
 * IIDM network.
 *
 * <p>Every method here is a one liner delegating to {@link RdfDbUtil}, because entry points cannot be unit tested on
 * the JVM: the logic lives in that class and is tested there. The connection handle these functions take is the one
 * {@link #createRdfDbConnection} returns; {@link #closeRdfDbConnection} releases the server connection (which the
 * generic {@code destroyObjectHandle} would not do, it only drops the reference).</p>
 *
 * <p>The {@code scenario} argument is required by every call that touches data: a database holds many base models
 * (typically one per day) and nothing may guess which one is meant. Inside that scenario a snapshot is addressed by
 * a {@code modellingAuthority} (text, {@code ""} = the only one of the scenario), a {@code timestamp} (an ISO-8601
 * instant as text, {@code ""} = the base timestamp) and a {@code version} (a registered name as text, {@code ""} =
 * the head on a read, the next registered one on a write; a read takes the highest ranking version at or below it,
 * exactly it when {@code exact} is set); {@code profiles} is an array of profile names, empty for the default of the
 * call. {@link RdfDbUtil} builds the {@code SnapshotRef}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
@SuppressWarnings({"java:S1602", "java:S1604", "Convert2Lambda"})
@CContext(Directives.class)
public final class RdfDbCFunctions {

    private RdfDbCFunctions() {
    }

    private static RdfDbConnection connection(ObjectHandle dbHandle) {
        return ObjectHandles.getGlobal().get(dbHandle);
    }

    private static String orNull(CCharPointer ptr) {
        String value = CTypeUtil.toStringOrNull(ptr);
        return value == null || value.isEmpty() ? null : value;
    }

    private static RdfDbUtil.PinArgs pin(boolean hasPin, CCharPointer versionPtr, CCharPointer timestampPtr,
                                         CCharPointer authorityPtr) {
        return hasPin ? new RdfDbUtil.PinArgs(orNull(versionPtr), orNull(timestampPtr), orNull(authorityPtr)) : null;
    }

    @CEntryPoint(name = "createRdfDbConnection")
    public static ObjectHandle createRdfDbConnection(IsolateThread thread, CCharPointer urlPtr,
                                                     CCharPointerPointer optionKeysPtr, int optionKeysCount,
                                                     CCharPointerPointer optionValuesPtr, int optionValuesCount,
                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                String url = CTypeUtil.toString(urlPtr);
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                return ObjectHandles.getGlobal().create(RdfDbUtil.open(url, options));
            }
        });
    }

    @CEntryPoint(name = "closeRdfDbConnection")
    public static void closeRdfDbConnection(IsolateThread thread, ObjectHandle dbHandle,
                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                connection(dbHandle).close();
            }
        });
    }

    @CEntryPoint(name = "getRdfDbGraphs")
    public static ArrayPointer<SeriesPointer> getRdfDbGraphs(IsolateThread thread, ObjectHandle dbHandle,
                                                            CCharPointer scenarioPtr,
                                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                String scenario = CTypeUtil.toString(scenarioPtr);
                return Dataframes.createCDataframe(RdfDbUtil.graphsMapper(),
                        RdfDbUtil.graphs(connection(dbHandle), scenario));
            }
        });
    }

    @CEntryPoint(name = "clearRdfDb")
    public static void clearRdfDb(IsolateThread thread, ObjectHandle dbHandle, CCharPointer scenarioPtr,
                                  ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                RdfDbUtil.clear(connection(dbHandle), CTypeUtil.toString(scenarioPtr));
            }
        });
    }

    @CEntryPoint(name = "loadCgmesToRdfDb")
    public static ArrayPointer<CCharPointerPointer> loadCgmesToRdfDb(IsolateThread thread, ObjectHandle dbHandle,
                                                                     CCharPointer filePtr, CCharPointer scenarioPtr,
                                                                     CCharPointer versionPtr,
                                                                     CCharPointer timestampPtr,
                                                                     CCharPointer modellingAuthorityPtr,
                                                                     CCharPointerPointer profilesPtr,
                                                                     int profilesCount, boolean hasPin,
                                                                     CCharPointer pinVersionPtr,
                                                                     CCharPointer pinTimestampPtr,
                                                                     CCharPointer pinAuthorityPtr,
                                                                     CCharPointerPointer parameterNamesPtr,
                                                                     int parameterNamesCount,
                                                                     CCharPointerPointer parameterValuesPtr,
                                                                     int parameterValuesCount,
                                                                     ObjectHandle reportNodeHandle,
                                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() {
                String scenario = CTypeUtil.toString(scenarioPtr);
                ReadOnlyDataSource ds = DataSource.fromPath(Paths.get(CTypeUtil.toString(filePtr)));
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                return Util.createCharPtrArray(RdfDbUtil.loadCgmes(connection(dbHandle), ds, scenario,
                        orNull(versionPtr), orNull(timestampPtr), orNull(modellingAuthorityPtr),
                        CTypeUtil.toStringList(profilesPtr, profilesCount),
                        pin(hasPin, pinVersionPtr, pinTimestampPtr, pinAuthorityPtr), parameters, reportNode));
            }
        });
    }

    @CEntryPoint(name = "loadCgmesBuffersToRdfDb")
    public static ArrayPointer<CCharPointerPointer> loadCgmesBuffersToRdfDb(IsolateThread thread,
                                                                            ObjectHandle dbHandle,
                                                                            CCharPointerPointer data,
                                                                            CIntPointer dataSizes, int bufferCount,
                                                                            CCharPointer scenarioPtr,
                                                                            CCharPointer versionPtr,
                                                                            CCharPointer timestampPtr,
                                                                            CCharPointer modellingAuthorityPtr,
                                                                            CCharPointerPointer profilesPtr,
                                                                            int profilesCount, boolean hasPin,
                                                                            CCharPointer pinVersionPtr,
                                                                            CCharPointer pinTimestampPtr,
                                                                            CCharPointer pinAuthorityPtr,
                                                                            CCharPointerPointer parameterNamesPtr,
                                                                            int parameterNamesCount,
                                                                            CCharPointerPointer parameterValuesPtr,
                                                                            int parameterValuesCount,
                                                                            ObjectHandle reportNodeHandle,
                                                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() {
                String scenario = CTypeUtil.toString(scenarioPtr);
                ReadOnlyDataSource ds = NetworkCFunctions.createDataSourceFromBuffers(data, dataSizes, bufferCount);
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                return Util.createCharPtrArray(RdfDbUtil.loadCgmes(connection(dbHandle), ds, scenario,
                        orNull(versionPtr), orNull(timestampPtr), orNull(modellingAuthorityPtr),
                        CTypeUtil.toStringList(profilesPtr, profilesCount),
                        pin(hasPin, pinVersionPtr, pinTimestampPtr, pinAuthorityPtr), parameters, reportNode));
            }
        });
    }

    @CEntryPoint(name = "loadNetworkFromRdfDb")
    public static ObjectHandle loadNetworkFromRdfDb(IsolateThread thread, ObjectHandle dbHandle,
                                                    CCharPointer scenarioPtr, CCharPointer versionPtr,
                                                    boolean exact, CCharPointer timestampPtr,
                                                    CCharPointer modellingAuthorityPtr,
                                                    CCharPointerPointer profilesPtr, int profilesCount,
                                                    CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                                    CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                                    CCharPointerPointer postProcessorsPtr, int postProcessorsCount,
                                                    ObjectHandle reportNodeHandle,
                                                    boolean allowVariantMultiThreadAccess,
                                                    ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                String scenario = CTypeUtil.toString(scenarioPtr);
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                List<String> postProcessors = CTypeUtil.toStringList(postProcessorsPtr, postProcessorsCount);
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                Network network = RdfDbUtil.load(connection(dbHandle), scenario, orNull(versionPtr), exact,
                        orNull(timestampPtr), orNull(modellingAuthorityPtr),
                        CTypeUtil.toStringList(profilesPtr, profilesCount), parameters, postProcessors, reportNode);
                network.getVariantManager().allowVariantMultiThreadAccess(allowVariantMultiThreadAccess);
                return ObjectHandles.getGlobal().create(network);
            }
        });
    }

    @CEntryPoint(name = "updateNetworkFromRdfDb")
    public static ObjectHandle updateNetworkFromRdfDb(IsolateThread thread, ObjectHandle networkHandle,
                                                      ObjectHandle dbHandle, CCharPointer scenarioPtr,
                                                      CCharPointer versionPtr, boolean exact,
                                                      CCharPointer timestampPtr,
                                                      CCharPointer modellingAuthorityPtr,
                                                      CCharPointerPointer profilesPtr, int profilesCount,
                                                      CCharPointerPointer optionKeysPtr, int optionKeysCount,
                                                      CCharPointerPointer optionValuesPtr, int optionValuesCount,
                                                      CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                                      CCharPointerPointer parameterValuesPtr,
                                                      int parameterValuesCount, ObjectHandle reportNodeHandle,
                                                      ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String scenario = CTypeUtil.toString(scenarioPtr);
                List<String> profiles = CTypeUtil.toStringList(profilesPtr, profilesCount);
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                return ObjectHandles.getGlobal().create(RdfDbUtil.update(network, connection(dbHandle), scenario,
                        orNull(versionPtr), exact, orNull(timestampPtr), orNull(modellingAuthorityPtr), profiles,
                        options, parameters, reportNode));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbUpdateInfo")
    public static PyPowsyblApiHeader.StringMap getRdfDbUpdateInfo(IsolateThread thread, ObjectHandle outcomeHandle,
                                                                  ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                RdfDbUtil.UpdateOutcome outcome = ObjectHandles.getGlobal().get(outcomeHandle);
                return CTypeUtil.fromStringMap(RdfDbUtil.updateInfo(outcome));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbUpdateNetwork")
    public static ObjectHandle getRdfDbUpdateNetwork(IsolateThread thread, ObjectHandle outcomeHandle,
                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                RdfDbUtil.UpdateOutcome outcome = ObjectHandles.getGlobal().get(outcomeHandle);
                return ObjectHandles.getGlobal().create(RdfDbUtil.replacement(outcome));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbScenarioTable")
    public static ArrayPointer<SeriesPointer> getRdfDbScenarioTable(IsolateThread thread, ObjectHandle dbHandle,
                                                                    ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.scenariosMapper(),
                        RdfDbUtil.scenarioRows(connection(dbHandle)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbSnapshots")
    public static ArrayPointer<SeriesPointer> getRdfDbSnapshots(IsolateThread thread, ObjectHandle dbHandle,
                                                                CCharPointer scenarioPtr,
                                                                ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.snapshotsMapper(),
                        RdfDbUtil.snapshots(connection(dbHandle), CTypeUtil.toString(scenarioPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbVersions")
    public static ArrayPointer<SeriesPointer> getRdfDbVersions(IsolateThread thread, ObjectHandle dbHandle,
                                                               CCharPointer scenarioPtr, CCharPointer timestampPtr,
                                                               CCharPointer modellingAuthorityPtr,
                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.snapshotsMapper(),
                        RdfDbUtil.versions(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                                orNull(timestampPtr), orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbTimestamps")
    public static ArrayPointer<SeriesPointer> getRdfDbTimestamps(IsolateThread thread, ObjectHandle dbHandle,
                                                                 CCharPointer scenarioPtr,
                                                                 CCharPointer modellingAuthorityPtr,
                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.timestampsMapper(),
                        RdfDbUtil.timestamps(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                                orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbModellingAuthorities")
    public static ArrayPointer<CCharPointerPointer> getRdfDbModellingAuthorities(IsolateThread thread,
                                                                                 ObjectHandle dbHandle,
                                                                                 CCharPointer scenarioPtr,
                                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() {
                return Util.createCharPtrArray(RdfDbUtil.modellingAuthorities(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbAssembly")
    public static ArrayPointer<SeriesPointer> getRdfDbAssembly(IsolateThread thread, ObjectHandle dbHandle,
                                                               CCharPointer scenarioPtr, CCharPointer timestampPtr,
                                                               CCharPointer versionPtr,
                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.assemblyMapper(),
                        RdfDbUtil.assembly(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                                orNull(timestampPtr), orNull(versionPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbModels")
    public static ArrayPointer<SeriesPointer> getRdfDbModels(IsolateThread thread, ObjectHandle dbHandle,
                                                             CCharPointer scenarioPtr,
                                                             ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.modelsMapper(),
                        RdfDbUtil.models(connection(dbHandle), CTypeUtil.toString(scenarioPtr)));
            }
        });
    }

    @CEntryPoint(name = "isRdfDbVersioned")
    public static boolean isRdfDbVersioned(IsolateThread thread, ObjectHandle dbHandle, CCharPointer scenarioPtr,
                                           ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return RdfDbUtil.isVersioned(connection(dbHandle), CTypeUtil.toString(scenarioPtr));
            }
        });
    }

    @CEntryPoint(name = "createRdfDbCheckpoint")
    public static CCharPointer createRdfDbCheckpoint(IsolateThread thread, ObjectHandle dbHandle,
                                                     CCharPointer scenarioPtr, CCharPointer versionPtr,
                                                     CCharPointer timestampPtr,
                                                     CCharPointer modellingAuthorityPtr,
                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public CCharPointer get() {
                return CTypeUtil.toCharPtr(RdfDbUtil.checkpoint(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr), orNull(versionPtr), orNull(timestampPtr),
                        orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "exportNetworkEventsToRdfDb")
    public static ArrayPointer<CCharPointerPointer> exportNetworkEventsToRdfDb(IsolateThread thread,
                                                                               ObjectHandle recorderHandle,
                                                                               ObjectHandle dbHandle,
                                                                               CCharPointer scenarioPtr,
                                                                               CCharPointer versionPtr,
                                                                               CCharPointer timestampPtr,
                                                                               CCharPointer modellingAuthorityPtr,
                                                                               CCharPointerPointer profilesPtr,
                                                                               int profilesCount, boolean hasPin,
                                                                               CCharPointer pinVersionPtr,
                                                                               CCharPointer pinTimestampPtr,
                                                                               CCharPointer pinAuthorityPtr,
                                                                               CCharPointerPointer optionKeysPtr,
                                                                               int optionKeysCount,
                                                                               CCharPointerPointer optionValuesPtr,
                                                                               int optionValuesCount,
                                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() {
                NetworkEventRecording recording = ObjectHandles.getGlobal().get(recorderHandle);
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                return Util.createCharPtrArray(RdfDbUtil.exportRecording(recording, connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr), orNull(versionPtr), orNull(timestampPtr),
                        orNull(modellingAuthorityPtr), CTypeUtil.toStringList(profilesPtr, profilesCount),
                        pin(hasPin, pinVersionPtr, pinTimestampPtr, pinAuthorityPtr), options));
            }
        });
    }

    @CEntryPoint(name = "getNetworkRdfDbIdentity")
    public static PyPowsyblApiHeader.StringMap getNetworkRdfDbIdentity(IsolateThread thread,
                                                                       ObjectHandle networkHandle,
                                                                       ObjectHandle dbHandle,
                                                                       CCharPointer scenarioPtr,
                                                                       CCharPointer variantPtr,
                                                                       ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                RdfDbConnection db = dbHandle.equal(WordFactory.zero()) ? null : connection(dbHandle);
                return CTypeUtil.fromStringMap(RdfDbUtil.identity(network, db,
                        CTypeUtil.toStringOrNull(scenarioPtr), orNull(variantPtr)));
            }
        });
    }

    // ------------------------------------------------------------------ snapshots as network variants

    @CEntryPoint(name = "loadNetworkVariantsFromRdfDb")
    public static ObjectHandle loadNetworkVariantsFromRdfDb(IsolateThread thread, ObjectHandle dbHandle,
                                                            CCharPointer scenarioPtr,
                                                            CCharPointerPointer variantIdsPtr, int variantIdsCount,
                                                            CCharPointerPointer versionsPtr, int versionsCount,
                                                            boolean exact,
                                                            CCharPointerPointer timestampsPtr, int timestampsCount,
                                                            CCharPointerPointer modellingAuthoritiesPtr,
                                                            int modellingAuthoritiesCount,
                                                            CCharPointerPointer profilesPtr, int profilesCount,
                                                            CCharPointerPointer parameterNamesPtr,
                                                            int parameterNamesCount,
                                                            CCharPointerPointer parameterValuesPtr,
                                                            int parameterValuesCount,
                                                            ObjectHandle reportNodeHandle,
                                                            boolean allowVariantMultiThreadAccess,
                                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                String scenario = CTypeUtil.toString(scenarioPtr);
                List<String> variantIds = CTypeUtil.toStringList(variantIdsPtr, variantIdsCount);
                List<String> versions = CTypeUtil.toStringList(versionsPtr, versionsCount).stream()
                        .map(RdfDbUtil::blankToNull).toList();
                List<String> timestamps = CTypeUtil.toStringList(timestampsPtr, timestampsCount);
                List<String> authorities = CTypeUtil.toStringList(modellingAuthoritiesPtr,
                        modellingAuthoritiesCount);
                List<String> profiles = CTypeUtil.toStringList(profilesPtr, profilesCount);
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                return ObjectHandles.getGlobal().create(RdfDbUtil.loadVariants(connection(dbHandle), scenario,
                        variantIds, versions, exact, timestamps, authorities, profiles, parameters, reportNode,
                        allowVariantMultiThreadAccess));
            }
        });
    }

    @CEntryPoint(name = "getNetworkRdfDbVariants")
    public static ArrayPointer<SeriesPointer> getNetworkRdfDbVariants(IsolateThread thread,
                                                                      ObjectHandle networkHandle,
                                                                      ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                return Dataframes.createCDataframe(RdfDbUtil.variantsMapper(), RdfDbUtil.variantRows(network));
            }
        });
    }

    @CEntryPoint(name = "exportNetworkEventsToRdfDbPerVariant")
    public static ArrayPointer<SeriesPointer> exportNetworkEventsToRdfDbPerVariant(IsolateThread thread,
                                                                                   ObjectHandle recorderHandle,
                                                                                   ObjectHandle dbHandle,
                                                                                   CCharPointer scenarioPtr,
                                                                                   CCharPointer versionPtr,
                                                                                   CCharPointerPointer optionKeysPtr,
                                                                                   int optionKeysCount,
                                                                                   CCharPointerPointer optionValuesPtr,
                                                                                   int optionValuesCount,
                                                                                   ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                NetworkEventRecording recording = ObjectHandles.getGlobal().get(recorderHandle);
                Map<String, String> options = CTypeUtil.toStringMap(optionKeysPtr, optionKeysCount,
                        optionValuesPtr, optionValuesCount);
                return Dataframes.createCDataframe(RdfDbUtil.variantExportMapper(),
                        RdfDbUtil.exportRecordingPerVariant(recording, connection(dbHandle),
                                CTypeUtil.toString(scenarioPtr), orNull(versionPtr), options));
            }
        });
    }

    // ------------------------------------------------------------------ the version registry

    @CEntryPoint(name = "getRdfDbRegistry")
    public static ArrayPointer<SeriesPointer> getRdfDbRegistry(IsolateThread thread, ObjectHandle dbHandle,
                                                               CCharPointer scenarioPtr,
                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.registryMapper(),
                        RdfDbUtil.registry(connection(dbHandle), CTypeUtil.toString(scenarioPtr)));
            }
        });
    }

    @CEntryPoint(name = "editRdfDbRegistry")
    public static PyPowsyblApiHeader.StringMap editRdfDbRegistry(IsolateThread thread, ObjectHandle dbHandle,
                                                                 CCharPointer scenarioPtr, CCharPointer opPtr,
                                                                 CCharPointer namePtr, CCharPointer otherPtr,
                                                                 CCharPointerPointer namesPtr, int namesCount,
                                                                 CIntPointer ranksPtr, int ranksCount,
                                                                 boolean flag,
                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                return CTypeUtil.fromStringMap(RdfDbUtil.editRegistry(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr), CTypeUtil.toString(opPtr), orNull(namePtr), orNull(otherPtr),
                        CTypeUtil.toStringList(namesPtr, namesCount), CTypeUtil.toIntegerList(ranksPtr, ranksCount),
                        flag));
            }
        });
    }

    // ------------------------------------------------------------------ profiles stored whole

    @CEntryPoint(name = "getRdfDbProfiles")
    public static PyPowsyblApiHeader.StringMap getRdfDbProfiles(IsolateThread thread, ObjectHandle dbHandle,
                                                                CCharPointer scenarioPtr, CCharPointer versionPtr,
                                                                boolean exact, CCharPointer timestampPtr,
                                                                CCharPointer modellingAuthorityPtr,
                                                                ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                return CTypeUtil.fromStringMap(RdfDbUtil.profiles(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr), orNull(versionPtr), exact, orNull(timestampPtr),
                        orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "fetchRdfDbGraph")
    public static ArrayPointer<SeriesPointer> fetchRdfDbGraph(IsolateThread thread, ObjectHandle dbHandle,
                                                              CCharPointer scenarioPtr, CCharPointer graphPtr,
                                                              ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.statementsMapper(),
                        RdfDbUtil.fetchGraph(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                                CTypeUtil.toString(graphPtr)));
            }
        });
    }

    // ------------------------------------------------------------------ rollovers, dropping, changes

    @CEntryPoint(name = "rolloverRdfDbSnapshot")
    public static CCharPointer rolloverRdfDbSnapshot(IsolateThread thread, ObjectHandle dbHandle,
                                                     CCharPointer scenarioPtr, CCharPointer versionPtr, boolean exact,
                                                     CCharPointer timestampPtr, CCharPointer modellingAuthorityPtr,
                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public CCharPointer get() {
                return CTypeUtil.toCharPtr(RdfDbUtil.rollover(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                        orNull(versionPtr), exact, orNull(timestampPtr), orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "dropRdfDbTimestamp")
    public static ArrayPointer<CCharPointerPointer> dropRdfDbTimestamp(IsolateThread thread, ObjectHandle dbHandle,
                                                                       CCharPointer scenarioPtr,
                                                                       CCharPointer timestampPtr,
                                                                       CCharPointer modellingAuthorityPtr,
                                                                       ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() {
                return Util.createCharPtrArray(RdfDbUtil.dropTimestamp(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr), orNull(timestampPtr), orNull(modellingAuthorityPtr)));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbChangesBetween")
    public static ArrayPointer<SeriesPointer> getRdfDbChangesBetween(IsolateThread thread, ObjectHandle dbHandle,
                                                                     CCharPointer scenarioPtr,
                                                                     CCharPointer modellingAuthorityPtr,
                                                                     CCharPointer fromVersionPtr,
                                                                     CCharPointer fromTimestampPtr,
                                                                     CCharPointer toVersionPtr,
                                                                     CCharPointer toTimestampPtr,
                                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ArrayPointer<SeriesPointer> get() {
                return Dataframes.createCDataframe(RdfDbUtil.changesMapper(),
                        RdfDbUtil.changesBetween(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                                orNull(modellingAuthorityPtr), orNull(fromVersionPtr), orNull(fromTimestampPtr),
                                orNull(toVersionPtr), orNull(toTimestampPtr)));
            }
        });
    }

    // ------------------------------------------------------------------ the archive cutoff

    @CEntryPoint(name = "setRdfDbArchiveCutoff")
    public static void setRdfDbArchiveCutoff(IsolateThread thread, ObjectHandle dbHandle, CCharPointer scenarioPtr,
                                             CCharPointer cutoffPtr, CCharPointer locationPtr,
                                             ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                RdfDbUtil.setArchiveCutoff(connection(dbHandle), CTypeUtil.toString(scenarioPtr), orNull(cutoffPtr),
                        orNull(locationPtr));
            }
        });
    }

    @CEntryPoint(name = "getRdfDbArchiveCutoff")
    public static PyPowsyblApiHeader.StringMap getRdfDbArchiveCutoff(IsolateThread thread, ObjectHandle dbHandle,
                                                                     CCharPointer scenarioPtr,
                                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public PyPowsyblApiHeader.StringMap get() {
                return CTypeUtil.fromStringMap(RdfDbUtil.archiveCutoff(connection(dbHandle),
                        CTypeUtil.toString(scenarioPtr)));
            }
        });
    }

    // ------------------------------------------------------------------ several authorities as one network

    @CEntryPoint(name = "loadComposedNetworkFromRdfDb")
    public static ObjectHandle loadComposedNetworkFromRdfDb(IsolateThread thread, ObjectHandle dbHandle,
                                                            CCharPointer scenarioPtr, CCharPointer versionPtr,
                                                            boolean exact, CCharPointer timestampPtr,
                                                            CCharPointerPointer authoritiesPtr, int authoritiesCount,
                                                            CCharPointerPointer ownedPtr, int ownedCount,
                                                            CCharPointerPointer profilesPtr, int profilesCount,
                                                            CCharPointerPointer parameterNamesPtr,
                                                            int parameterNamesCount,
                                                            CCharPointerPointer parameterValuesPtr,
                                                            int parameterValuesCount,
                                                            ObjectHandle reportNodeHandle,
                                                            boolean allowVariantMultiThreadAccess,
                                                            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public ObjectHandle get() {
                Map<String, String> parameters = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                Network network = RdfDbUtil.loadComposed(connection(dbHandle), CTypeUtil.toString(scenarioPtr),
                        orNull(versionPtr), exact, orNull(timestampPtr),
                        CTypeUtil.toStringList(authoritiesPtr, authoritiesCount),
                        CTypeUtil.toStringList(ownedPtr, ownedCount),
                        CTypeUtil.toStringList(profilesPtr, profilesCount), parameters,
                        ReportCUtils.getReportNode(reportNodeHandle));
                network.getVariantManager().allowVariantMultiThreadAccess(allowVariantMultiThreadAccess);
                return ObjectHandles.getGlobal().create(network);
            }
        });
    }
}
