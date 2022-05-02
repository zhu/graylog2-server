/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.testing.completebackend;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.graylog.testing.containermatrix.MongodbServer;
import org.graylog.testing.elasticsearch.SearchServerInstance;
import org.graylog.testing.graylognode.ExecutableNotFoundException;
import org.graylog.testing.graylognode.NodeInstance;
import org.graylog.testing.mongodb.MongoDBInstance;
import org.graylog2.storage.SearchVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PhysicalMemory;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSSession;
import oshi.software.os.OperatingSystem;
import oshi.util.FormatUtil;
import oshi.util.Util;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.graylog2.shared.utilities.StringUtils.f;

public class ContainerizedGraylogBackend implements GraylogBackend, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GraylogBackend.class);
    private Network network;
    private SearchServerInstance searchServer;
    private MongoDBInstance mongodb;
    private NodeInstance node;

    private ContainerizedGraylogBackend() {
    }

    public static ContainerizedGraylogBackend createStarted(SearchVersion esVersion, MongodbServer mongodbVersion,
                                                            int[] extraPorts, List<URL> mongoDBFixtures,
                                                            PluginJarsProvider pluginJarsProvider, MavenProjectDirProvider mavenProjectDirProvider) {

        final ContainerizedGraylogBackend backend = new ContainerizedGraylogBackend();
        backend.create(esVersion, mongodbVersion, extraPorts, mongoDBFixtures, pluginJarsProvider, mavenProjectDirProvider);
        return backend;
    }

    private void create(SearchVersion esVersion, MongodbServer mongodbVersion,
                        int[] extraPorts, List<URL> mongoDBFixtures,
                        PluginJarsProvider pluginJarsProvider, MavenProjectDirProvider mavenProjectDirProvider) {

        final SearchServerInstanceFactory searchServerInstanceFactory = new SearchServerInstanceFactoryByVersion(esVersion);
        Network network = Network.newNetwork();
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("build-es-container-for-api-it").build());
        Future<SearchServerInstance> esFuture = executor.submit(() -> searchServerInstanceFactory.create(network));
        MongoDBInstance mongoDB = MongoDBInstance.createStartedWithUniqueName(network, Lifecycle.CLASS, mongodbVersion);
        mongoDB.dropDatabase();
        mongoDB.importFixtures(mongoDBFixtures);

        try {
            // Wait for ES before starting the Graylog node to avoid any race conditions
            SearchServerInstance esInstance = esFuture.get();
            NodeInstance node = NodeInstance.createStarted(
                    network,
                    MongoDBInstance.internalUri(),
                    esInstance.internalUri(),
                    esInstance.version(),
                    extraPorts,
                    pluginJarsProvider, mavenProjectDirProvider);
            this.network = network;
            this.searchServer = esInstance;
            this.mongodb = mongoDB;
            this.node = node;

            // ensure that all containers and networks will be removed after all tests finish
            // We can't close the resources in an afterAll callback, as the instances are cached and reused
            // so we need a solution that will be triggered only once after all test classes
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        } catch (InterruptedException | ExecutionException | ExecutableNotFoundException e) {
            LOG.error("Container creation aborted", e);
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    public void purgeData() {
        mongodb.dropDatabase();
        searchServer.cleanUp();
    }

    public void fullReset(List<URL> mongoDBFixtures) {
        LOG.debug("Resetting backend.");
        purgeData();
        mongodb.importFixtures(mongoDBFixtures);
        node.restart();
    }

    @Override
    public void importElasticsearchFixture(String resourcePath, Class<?> testClass) {
        searchServer.importFixtureResource(resourcePath, testClass);
    }

    @Override
    public void importMongoDBFixture(String resourcePath, Class<?> testClass) {
        mongodb.importFixture(resourcePath, testClass);
    }

    @Override
    public String uri() {
        return node.uri();
    }

    @Override
    public int apiPort() {
        return node.apiPort();
    }

    @Override
    public String getLogs() {
        return node.getLogs();
    }

    @Override
    public String getSearchLogs() {
        return searchServer.getLogs();
    }

    @Override
    public String getDbLogs() {
        return mongodb.getLogs();
    }

    @Override
    public String getSysInfo() {
        final SystemInfo si = new SystemInfo();

        final HardwareAbstractionLayer hal = si.getHardware();
        final OperatingSystem os = si.getOperatingSystem();

        final List<String> oshi = printOperatingSystem(os);
        oshi.addAll(printMemory(hal.getMemory()));
        oshi.addAll(printCpu(hal.getProcessor()));

        return String.join("\n", oshi);
    }

    private List<String> printOperatingSystem(final OperatingSystem os) {
        List<String> oshi = new ArrayList<>();
        oshi.add(String.valueOf(os));
        oshi.add("Booted: " + Instant.ofEpochSecond(os.getSystemBootTime()));
        oshi.add("Uptime: " + FormatUtil.formatElapsedSecs(os.getSystemUptime()));
        oshi.add("Running with" + (os.isElevated() ? "" : "out") + " elevated permissions.");
        oshi.add("Sessions:");
        for (OSSession s : os.getSessions()) {
            oshi.add(" " + s.toString());
        }
        return oshi;
    }

    private List<String> printMemory(GlobalMemory memory) {
        List<String> oshi = new ArrayList<>();
        oshi.add("Physical Memory: \n " + memory.toString());
        VirtualMemory vm = memory.getVirtualMemory();
        oshi.add("Virtual Memory: \n " + vm.toString());
        List<PhysicalMemory> pmList = memory.getPhysicalMemory();
        if (!pmList.isEmpty()) {
            oshi.add("Physical Memory: ");
            for (PhysicalMemory pm : pmList) {
                oshi.add(" " + pm.toString());
            }
        }
        return oshi;
    }

    private List<String> printCpu(CentralProcessor processor) {
        List<String> oshi = new ArrayList<>();
        oshi.add("Context Switches/Interrupts: " + processor.getContextSwitches() + " / " + processor.getInterrupts());

        long[] prevTicks = processor.getSystemCpuLoadTicks();
        long[][] prevProcTicks = processor.getProcessorCpuLoadTicks();
        oshi.add("CPU, IOWait, and IRQ ticks @ 0 sec:" + Arrays.toString(prevTicks));
        // Wait a second...
        Util.sleep(1000);
        long[] ticks = processor.getSystemCpuLoadTicks();
        oshi.add("CPU, IOWait, and IRQ ticks @ 1 sec:" + Arrays.toString(ticks));
        long user = ticks[TickType.USER.getIndex()] - prevTicks[TickType.USER.getIndex()];
        long nice = ticks[TickType.NICE.getIndex()] - prevTicks[TickType.NICE.getIndex()];
        long sys = ticks[TickType.SYSTEM.getIndex()] - prevTicks[TickType.SYSTEM.getIndex()];
        long idle = ticks[TickType.IDLE.getIndex()] - prevTicks[TickType.IDLE.getIndex()];
        long iowait = ticks[TickType.IOWAIT.getIndex()] - prevTicks[TickType.IOWAIT.getIndex()];
        long irq = ticks[TickType.IRQ.getIndex()] - prevTicks[TickType.IRQ.getIndex()];
        long softirq = ticks[TickType.SOFTIRQ.getIndex()] - prevTicks[TickType.SOFTIRQ.getIndex()];
        long steal = ticks[TickType.STEAL.getIndex()] - prevTicks[TickType.STEAL.getIndex()];
        long totalCpu = user + nice + sys + idle + iowait + irq + softirq + steal;

        oshi.add(f(
                "User: %.1f%% Nice: %.1f%% System: %.1f%% Idle: %.1f%% IOwait: %.1f%% IRQ: %.1f%% SoftIRQ: %.1f%% Steal: %.1f%%",
                100d * user / totalCpu, 100d * nice / totalCpu, 100d * sys / totalCpu, 100d * idle / totalCpu,
                100d * iowait / totalCpu, 100d * irq / totalCpu, 100d * softirq / totalCpu, 100d * steal / totalCpu));
        oshi.add(f("CPU load: %.1f%%", processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100));
        double[] loadAverage = processor.getSystemLoadAverage(3);
        oshi.add("CPU load averages:" + (loadAverage[0] < 0 ? " N/A" : f(" %.2f", loadAverage[0]))
                + (loadAverage[1] < 0 ? " N/A" : f(" %.2f", loadAverage[1]))
                + (loadAverage[2] < 0 ? " N/A" : f(" %.2f", loadAverage[2])));
        // per core CPU
        StringBuilder procCpu = new StringBuilder("CPU load per processor:");
        double[] load = processor.getProcessorCpuLoadBetweenTicks(prevProcTicks);
        for (double avg : load) {
            procCpu.append(f(" %.1f%%", avg * 100));
        }
        oshi.add(procCpu.toString());
        long freq = processor.getProcessorIdentifier().getVendorFreq();
        if (freq > 0) {
            oshi.add("Vendor Frequency: " + FormatUtil.formatHertz(freq));
        }
        freq = processor.getMaxFreq();
        if (freq > 0) {
            oshi.add("Max Frequency: " + FormatUtil.formatHertz(freq));
        }
        long[] freqs = processor.getCurrentFreq();
        if (freqs[0] > 0) {
            StringBuilder sb = new StringBuilder("Current Frequencies: ");
            for (int i = 0; i < freqs.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(FormatUtil.formatHertz(freqs[i]));
            }
            oshi.add(sb.toString());
        }
        return oshi;
    }

    @Override
    public int mappedPortFor(int originalPort) {
        return node.mappedPortFor(originalPort);
    }

    @Override
    public Network network() {
        return this.network;
    }

    public MongoDBInstance mongoDB() {
        return mongodb;
    }

    @Override
    public void close() {
        node.close();
        mongodb.close();
        searchServer.close();
        network.close();
    }

    @Override
    public SearchServerInstance searchServerInstance() {
        return searchServer;
    }
}
