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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
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

        return sysInfo.toString();
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
