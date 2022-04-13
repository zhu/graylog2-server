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
import oshi.hardware.CentralProcessor.PhysicalProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.ComputerSystem;
import oshi.hardware.Display;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.LogicalVolumeGroup;
import oshi.hardware.NetworkIF;
import oshi.hardware.PhysicalMemory;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;
import oshi.hardware.SoundCard;
import oshi.hardware.UsbDevice;
import oshi.hardware.VirtualMemory;
import oshi.software.os.FileSystem;
import oshi.software.os.InternetProtocolStats;
import oshi.software.os.NetworkParams;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OSService;
import oshi.software.os.OSSession;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.ProcessFiltering;
import oshi.software.os.OperatingSystem.ProcessSorting;
import oshi.util.FormatUtil;
import oshi.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ContainerizedGraylogBackend implements GraylogBackend, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GraylogBackend.class);
    private Network network;
    private SearchServerInstance searchServer;
    private MongoDBInstance mongodb;
    private NodeInstance node;

    static List<String> oshi = new ArrayList<>();

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
        StringBuffer sysInfo = new StringBuffer("");
        sysInfo.append("Available processors (cores): ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        sysInfo.append("Free memory (bytes): ").append(Runtime.getRuntime().freeMemory()).append("\n");
        /* This will return Long.MAX_VALUE if there is no preset limit */
        long maxMemory = Runtime.getRuntime().maxMemory();
        sysInfo.append("Maximum memory (bytes): ").append( (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory)).append("\n");
        sysInfo.append("Total memory available to JVM (bytes): ").append(Runtime.getRuntime().totalMemory()).append("\n");

        /* Get a list of all filesystem roots on this system */
        File[] roots = File.listRoots();

        /* For each filesystem root, print some info */
        for (File root : roots) {
            sysInfo.append("-------------------------------------------\n");
            sysInfo.append("File system root: ").append(root.getAbsolutePath()).append("\n");
            sysInfo.append("Total space (bytes): ").append(root.getTotalSpace()).append("\n");
            sysInfo.append("Free space (bytes): ").append(root.getFreeSpace()).append("\n");
            sysInfo.append("Usable space (bytes): ").append(root.getUsableSpace()).append("\n");
        }

        try {
            sysInfo.append("-------------------------------------------\n");

            Runtime runtime = Runtime.getRuntime();
            BufferedReader br = new BufferedReader(new InputStreamReader(runtime.exec("cat /proc/loadavg").getInputStream()));

            String avgLine = br.readLine();
            sysInfo.append(avgLine).append("\n");
            List<String> avgLineList = Arrays.asList(avgLine.split("\\s+"));
            sysInfo.append(avgLineList).append("\n");
            sysInfo.append("Average load 1 minute : ").append(avgLineList.get(0)).append("\n");
            sysInfo.append("Average load 5 minutes : ").append(avgLineList.get(1)).append("\n");
            sysInfo.append("Average load 15 minutes : ").append(avgLineList.get(2)).append("\n");
        } catch (IOException iox) {
            sysInfo.append("Could not generate System Load: " + iox.getMessage());
        }

        sysInfo.append("--OSHI Output -----------------------------------------\n");
        sysInfo.append(oshi).append("\n");
        sysInfo.append("-------------------------------------------\n");

        return sysInfo.toString();
    }

    public String oshi() {
        SystemInfo si = new SystemInfo();

        HardwareAbstractionLayer hal = si.getHardware();
        OperatingSystem os = si.getOperatingSystem();

        printOperatingSystem(os);

        printComputerSystem(hal.getComputerSystem());
        printProcessor(hal.getProcessor());
        printMemory(hal.getMemory());
        printCpu(hal.getProcessor());
        printProcesses(os, hal.getMemory());
        printServices(os);
        printSensors(hal.getSensors());
        printPowerSources(hal.getPowerSources());
        printDisks(hal.getDiskStores());
        printLVgroups(hal.getLogicalVolumeGroups());
        printFileSystem(os.getFileSystem());
        printNetworkInterfaces(hal.getNetworkIFs());
        printNetworkParameters(os.getNetworkParams());
        printInternetProtocolStats(os.getInternetProtocolStats());
        printDisplays(hal.getDisplays());
        printUsbDevices(hal.getUsbDevices(true));
        printSoundCards(hal.getSoundCards());
        printGraphicsCards(hal.getGraphicsCards());

        StringBuilder output = new StringBuilder();
        for (int i = 0; i < oshi.size(); i++) {
            output.append(oshi.get(i));
            if (oshi.get(i) != null && !oshi.get(i).endsWith("\n")) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    private static void printOperatingSystem(final OperatingSystem os) {
        oshi.add(String.valueOf(os));
        oshi.add("Booted: " + Instant.ofEpochSecond(os.getSystemBootTime()));
        oshi.add("Uptime: " + FormatUtil.formatElapsedSecs(os.getSystemUptime()));
        oshi.add("Running with" + (os.isElevated() ? "" : "out") + " elevated permissions.");
        oshi.add("Sessions:");
        for (OSSession s : os.getSessions()) {
            oshi.add(" " + s.toString());
        }
    }

    private static void printComputerSystem(final ComputerSystem computerSystem) {
        oshi.add("System: " + computerSystem.toString());
        oshi.add(" Firmware: " + computerSystem.getFirmware().toString());
        oshi.add(" Baseboard: " + computerSystem.getBaseboard().toString());
    }

    private static void printProcessor(CentralProcessor processor) {
        oshi.add(processor.toString());
        oshi.add(" Cores:");
        for (PhysicalProcessor p : processor.getPhysicalProcessors()) {
            oshi.add("  " + (processor.getPhysicalPackageCount() > 1 ? p.getPhysicalPackageNumber() + "," : "")
                    + p.getPhysicalProcessorNumber() + ": efficiency=" + p.getEfficiency() + ", id=" + p.getIdString());
        }
    }

    private static void printMemory(GlobalMemory memory) {
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
    }

    private static void printCpu(CentralProcessor processor) {
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

        oshi.add(String.format(
                "User: %.1f%% Nice: %.1f%% System: %.1f%% Idle: %.1f%% IOwait: %.1f%% IRQ: %.1f%% SoftIRQ: %.1f%% Steal: %.1f%%",
                100d * user / totalCpu, 100d * nice / totalCpu, 100d * sys / totalCpu, 100d * idle / totalCpu,
                100d * iowait / totalCpu, 100d * irq / totalCpu, 100d * softirq / totalCpu, 100d * steal / totalCpu));
        oshi.add(String.format("CPU load: %.1f%%", processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100));
        double[] loadAverage = processor.getSystemLoadAverage(3);
        oshi.add("CPU load averages:" + (loadAverage[0] < 0 ? " N/A" : String.format(" %.2f", loadAverage[0]))
                + (loadAverage[1] < 0 ? " N/A" : String.format(" %.2f", loadAverage[1]))
                + (loadAverage[2] < 0 ? " N/A" : String.format(" %.2f", loadAverage[2])));
        // per core CPU
        StringBuilder procCpu = new StringBuilder("CPU load per processor:");
        double[] load = processor.getProcessorCpuLoadBetweenTicks(prevProcTicks);
        for (double avg : load) {
            procCpu.append(String.format(" %.1f%%", avg * 100));
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
    }

    private static void printProcesses(OperatingSystem os, GlobalMemory memory) {
        OSProcess myProc = os.getProcess(os.getProcessId());
        // current process will never be null. Other code should check for null here
        oshi.add(
                "My PID: " + myProc.getProcessID() + " with affinity " + Long.toBinaryString(myProc.getAffinityMask()));
        oshi.add("Processes: " + os.getProcessCount() + ", Threads: " + os.getThreadCount());
        // Sort by highest CPU
        List<OSProcess> procs = os.getProcesses(ProcessFiltering.ALL_PROCESSES, ProcessSorting.CPU_DESC, 5);
        oshi.add("   PID  %CPU %MEM       VSZ       RSS Name");
        for (int i = 0; i < procs.size() && i < 5; i++) {
            OSProcess p = procs.get(i);
            oshi.add(String.format(" %5d %5.1f %4.1f %9s %9s %s", p.getProcessID(),
                    100d * (p.getKernelTime() + p.getUserTime()) / p.getUpTime(),
                    100d * p.getResidentSetSize() / memory.getTotal(), FormatUtil.formatBytes(p.getVirtualSize()),
                    FormatUtil.formatBytes(p.getResidentSetSize()), p.getName()));
        }
        OSProcess p = os.getProcess(os.getProcessId());
        oshi.add("Current process arguments: ");
        for (String s : p.getArguments()) {
            oshi.add("  " + s);
        }
        oshi.add("Current process environment: ");
        for (Map.Entry<String, String> e : p.getEnvironmentVariables().entrySet()) {
            oshi.add("  " + e.getKey() + "=" + e.getValue());
        }
    }

    private static void printServices(OperatingSystem os) {
        oshi.add("Services: ");
        oshi.add("   PID   State   Name");
        // DO 5 each of running and stopped
        int i = 0;
        for (OSService s : os.getServices()) {
            if (s.getState().equals(OSService.State.RUNNING) && i++ < 5) {
                oshi.add(String.format(" %5d  %7s  %s", s.getProcessID(), s.getState(), s.getName()));
            }
        }
        i = 0;
        for (OSService s : os.getServices()) {
            if (s.getState().equals(OSService.State.STOPPED) && i++ < 5) {
                oshi.add(String.format(" %5d  %7s  %s", s.getProcessID(), s.getState(), s.getName()));
            }
        }
    }

    private static void printSensors(Sensors sensors) {
        oshi.add("Sensors: " + sensors.toString());
    }

    private static void printPowerSources(List<PowerSource> list) {
        StringBuilder sb = new StringBuilder("Power Sources: ");
        if (list.isEmpty()) {
            sb.append("Unknown");
        }
        for (PowerSource powerSource : list) {
            sb.append("\n ").append(powerSource.toString());
        }
        oshi.add(sb.toString());
    }

    private static void printDisks(List<HWDiskStore> list) {
        oshi.add("Disks:");
        for (HWDiskStore disk : list) {
            oshi.add(" " + disk.toString());

            List<HWPartition> partitions = disk.getPartitions();
            for (HWPartition part : partitions) {
                oshi.add(" |-- " + part.toString());
            }
        }

    }

    private static void printLVgroups(List<LogicalVolumeGroup> list) {
        if (!list.isEmpty()) {
            oshi.add("Logical Volume Groups:");
            for (LogicalVolumeGroup lvg : list) {
                oshi.add(" " + lvg.toString());
            }
        }
    }

    private static void printFileSystem(FileSystem fileSystem) {
        oshi.add("File System:");

        oshi.add(String.format(" File Descriptors: %d/%d", fileSystem.getOpenFileDescriptors(),
                fileSystem.getMaxFileDescriptors()));

        for (OSFileStore fs : fileSystem.getFileStores()) {
            long usable = fs.getUsableSpace();
            long total = fs.getTotalSpace();
            oshi.add(String.format(
                    " %s (%s) [%s] %s of %s free (%.1f%%), %s of %s files free (%.1f%%) is %s "
                            + (fs.getLogicalVolume() != null && fs.getLogicalVolume().length() > 0 ? "[%s]" : "%s")
                            + " and is mounted at %s",
                    fs.getName(), fs.getDescription().isEmpty() ? "file system" : fs.getDescription(), fs.getType(),
                    FormatUtil.formatBytes(usable), FormatUtil.formatBytes(fs.getTotalSpace()), 100d * usable / total,
                    FormatUtil.formatValue(fs.getFreeInodes(), ""), FormatUtil.formatValue(fs.getTotalInodes(), ""),
                    100d * fs.getFreeInodes() / fs.getTotalInodes(), fs.getVolume(), fs.getLogicalVolume(),
                    fs.getMount()));
        }
    }

    private static void printNetworkInterfaces(List<NetworkIF> list) {
        StringBuilder sb = new StringBuilder("Network Interfaces:");
        if (list.isEmpty()) {
            sb.append(" Unknown");
        } else {
            for (NetworkIF net : list) {
                sb.append("\n ").append(net.toString());
            }
        }
        oshi.add(sb.toString());
    }

    private static void printNetworkParameters(NetworkParams networkParams) {
        oshi.add("Network parameters:\n " + networkParams.toString());
    }

    private static void printInternetProtocolStats(InternetProtocolStats ip) {
        oshi.add("Internet Protocol statistics:");
        oshi.add(" TCPv4: " + ip.getTCPv4Stats());
        oshi.add(" TCPv6: " + ip.getTCPv6Stats());
        oshi.add(" UDPv4: " + ip.getUDPv4Stats());
        oshi.add(" UDPv6: " + ip.getUDPv6Stats());
    }

    private static void printDisplays(List<Display> list) {
        oshi.add("Displays:");
        int i = 0;
        for (Display display : list) {
            oshi.add(" Display " + i + ":");
            oshi.add(String.valueOf(display));
            i++;
        }
    }

    private static void printUsbDevices(List<UsbDevice> list) {
        oshi.add("USB Devices:");
        for (UsbDevice usbDevice : list) {
            oshi.add(String.valueOf(usbDevice));
        }
    }

    private static void printSoundCards(List<SoundCard> list) {
        oshi.add("Sound Cards:");
        for (SoundCard card : list) {
            oshi.add(" " + String.valueOf(card));
        }
    }

    private static void printGraphicsCards(List<GraphicsCard> list) {
        oshi.add("Graphics Cards:");
        if (list.isEmpty()) {
            oshi.add(" None detected.");
        } else {
            for (GraphicsCard card : list) {
                oshi.add(" " + String.valueOf(card));
            }
        }
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
