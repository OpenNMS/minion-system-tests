package org.opennms.minion.stests;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import jersey.repackaged.com.google.common.collect.Lists;

import org.apache.karaf.features.management.FeaturesServiceMBean;
import org.opennms.minion.stests.junit.ExternalResourceRule;
import org.opennms.minion.stests.utils.DBUtils;
import org.opennms.minion.stests.utils.KarafMBeanProxyHelper;
import org.opennms.minion.stests.utils.RESTClient;
import org.opennms.minion.stests.utils.SSHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.opennms.minion.stests.utils.KarafMBeanHelper.isFeatureInstalled;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.LogsParameter;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;

/**
 * Spawns and configures a collection of Docker containers running
 * the Minion Service.
 *
 * In particular, this is composed of:
 *  1) postgres: An instance of PostgreSQL 
 *  2) dominion: An instance of OpenNMS with the SMNepO WAR 
 *  3) snmpd: An instance of Net-SNMP
 *  4) minion: An instance of Karaf runing the Minion features
 *
 * @author jwhite
 */
public class NewMinionSystem extends ExternalResourceRule implements MinionSystemTestRule {

    private static final Logger LOG = LoggerFactory.getLogger(NewMinionSystem.class);

    // Aliases used to refer to the containers
    // Note that these are not the container ids or names
    public static final String POSTGRES = "postgres";
    public static final String DOMINION = "dominion";
    public static final String MINION = "minion";
    public static final String SNMPD = "snmpd";
    public static final String TOMCAT = "tomcat";

    // Set if the containers should be kept running after the tests complete
    // (whether or not these were successful)
    private final boolean skipTearDown;

    private File dockerProvisioningDir;
    private DockerClient docker;
    // Used to keep track of the ids for all the created containers
    private final Set<String> createdContainerIds = Sets.newHashSet();
    private final Map<String, ContainerInfo> containerInfo = Maps.newHashMap();

    public NewMinionSystem() {
        this(false);
    }

    public NewMinionSystem(boolean skipTearDown) {
        this.skipTearDown = skipTearDown;
    }

    @Override
    protected void before() throws Throwable {
        dockerProvisioningDir = Paths.get(System.getProperty("user.dir"), "docker", "provisioning").toFile();
        // Fail early
        assertThat(dockerProvisioningDir.exists(), is(true));

        docker = DefaultDockerClient.fromEnv().build();

        spawnPostgres();
        // Wait for PostgreSQL to make sure that it's available when Dominion starts
        // Otherwise, the install script may fail
        waitForPostgres();
        spawnDominion();
        spawnSnmpd();
        spawnTomcat();
        spawnMinion();
        configureDominion();
        configureMinion();
    };

    @Override
    protected void after(boolean didFail) {
        if (docker == null) {
            LOG.warn("Docker instance is null. Skipping tear down.");
            return;
        }

        // Ideally, we would only gather the logs and container output
        // when we fail, but we can't detect this when using @ClassRules
        final ContainerInfo dominionContainerInfo = containerInfo.get(DOMINION);
        if (dominionContainerInfo != null) {
            LOG.info("Gathering Dominion logs...");
            final Path destination = Paths.get("target/dominion.logs.tar");
            try (
                    final InputStream in = docker.copyContainer(dominionContainerInfo.id(), "/opt/opennms/logs");
            ) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (DockerException|InterruptedException|IOException e) {
                LOG.warn("Failed to copy the logs directory from the Dominion container.", e);
            }
        } else {
            LOG.warn("No Dominion container provisioned. Logs won't be copied.");
        }

        LOG.info("Gathering container output...");
        for (String containerId : createdContainerIds) {
            try {
                LogStream logStream = docker.logs(containerId, LogsParameter.STDOUT, LogsParameter.STDERR);
                LOG.info("Stdout/stderr for {}: {}", containerId, logStream.readFully());
            } catch (DockerException | InterruptedException e) {
                LOG.warn("Failed to get stdout/stderr for container {}.", e);
            }
        }

        if (!skipTearDown) {
            // Kill and remove all of the containers we created
            for (String containerId : createdContainerIds) {
                try {
                    LOG.info("Killing and removing container with id: {}", containerId);
                    docker.killContainer(containerId);
                    docker.removeContainer(containerId);
                } catch (Exception e) {
                    LOG.error("Failed to kill and/or remove container with id: {}", containerId, e);
                }
            }
        } else {
            LOG.info("Skipping tear down.");
        }

        docker.close();
    };

    @Override
    public InetSocketAddress getServiceAddress(String alias, int port) {
        final ContainerInfo info = containerInfo.get(alias);
        if (info == null) {
            return null;
        }
        final PortBinding binding = info.networkSettings().ports().get(port + "/tcp").get(0);
        final String host = "0.0.0.0".equals(binding.hostIp()) ? "127.0.0.1" : binding.hostIp();
        return new InetSocketAddress(host, Integer.valueOf(binding.hostPort()));
    }

    @Override
    public Set<String> getContainerAliases() {
        return containerInfo.keySet();
    }

    @Override
    public ContainerInfo getContainerInfo(final String containerAlias) {
        return containerInfo.get(containerAlias);
    }

    /**
     * Spawns the PostgreSQL container.
     */
    private void spawnPostgres() throws DockerException, InterruptedException {
        final ContainerConfig postgresConfig = ContainerConfig.builder()
                .image("postgres")
                .env("POSTGRES_PASSWORD=postgres")
                .build();

        final ContainerCreation postgresCreation = docker.createContainer(postgresConfig);
        final String postgresContainerId = postgresCreation.id();
        createdContainerIds.add(postgresContainerId);

        final HostConfig postgresHostBuilder = HostConfig.builder()
                .publishAllPorts(true)
                .build();

        docker.startContainer(postgresContainerId, postgresHostBuilder);

        final ContainerInfo postgresInfo = docker.inspectContainer(postgresContainerId);
        LOG.info("Postgres container info: {}", postgresInfo);
        if (!postgresInfo.state().running()) {
            throw new IllegalStateException("Could not start Postgres container");
        }

        containerInfo.put(POSTGRES, postgresInfo);
    }

    /**
     * Spawns the Dominion container, linked to PostgreSQL.
     */
    private void spawnDominion() throws DockerException, InterruptedException {
        final ContainerConfig dominionConfig = ContainerConfig.builder()
                .image("dominion:v1")
                .build();

        final ContainerCreation dominionCreation = docker.createContainer(dominionConfig);
        final String dominionContainerId = dominionCreation.id();
        createdContainerIds.add(dominionContainerId);

        final HostConfig dominionHostConfig = HostConfig.builder()
                .privileged(true)
                .publishAllPorts(true)
                .binds(String.format("%s:/opt/provisioning",
                        new File(dockerProvisioningDir, "dominion").getAbsolutePath()))
                .links(String.format("%s:postgres", containerInfo.get(POSTGRES).name()))
                .build();

        docker.startContainer(dominionContainerId, dominionHostConfig);

        final ContainerInfo dominionInfo = docker.inspectContainer(dominionContainerId);
        LOG.info("Dominion container info: {}", dominionInfo);
        if (!dominionInfo.state().running()) {
            throw new IllegalStateException("Could not start Dominion container");
        }

        containerInfo.put(DOMINION, dominionInfo);
    }

    /**
     * Spawns the Net-SNMP container.
     */
    private void spawnSnmpd() throws DockerException, InterruptedException {
        final ContainerConfig snmpdConfig = ContainerConfig.builder()
                .image("snmpd:v1")
                .build();

        final ContainerCreation snmpdCreation = docker.createContainer(snmpdConfig);
        final String snmpdContainerId = snmpdCreation.id();
        createdContainerIds.add(snmpdContainerId);

        final HostConfig snmpdHostConfig = HostConfig.builder()
                .build();

        docker.startContainer(snmpdContainerId, snmpdHostConfig);

        final ContainerInfo snmpdInfo = docker.inspectContainer(snmpdContainerId);
        LOG.info("Snmpd container info: {}", snmpdInfo);
        if (!snmpdInfo.state().running()) {
            throw new IllegalStateException("Could not start snmpd container");
        }

        containerInfo.put(SNMPD, snmpdInfo);
    }

    /**
     * Spawns the Tomcat container.
     */
    private void spawnTomcat() throws DockerException, InterruptedException {
        final ContainerConfig tomcatConfig = ContainerConfig.builder()
                .image("tomcat:v1")
                .build();

        final ContainerCreation tomcatCreation = docker.createContainer(tomcatConfig);
        final String tomcatContainerId = tomcatCreation.id();
        createdContainerIds.add(tomcatContainerId);

        final HostConfig tomcatHostConfig = HostConfig.builder()
                .build();

        docker.startContainer(tomcatContainerId, tomcatHostConfig);

        final ContainerInfo tomcatInfo = docker.inspectContainer(tomcatContainerId);
        LOG.info("Tomcat container info: {}", tomcatInfo);
        if (!tomcatInfo.state().running()) {
            throw new IllegalStateException("Could not start Tomcat container");
        }

        containerInfo.put(TOMCAT, tomcatInfo);
    }

    /**
     * Spawns the Minion container, linked to Dominion, Net-SNMP and Tomcat.
     */
    private void spawnMinion() throws DockerException, InterruptedException {
        final ContainerConfig minionConfig = ContainerConfig.builder()
                .image("minion:v1")
                .build();

        final ContainerCreation minionCreation = docker.createContainer(minionConfig);
        final String minionContainerId = minionCreation.id();
        createdContainerIds.add(minionContainerId);
        final List<String> links = Lists.newArrayList();
        links.add(String.format("%s:dominion", containerInfo.get(DOMINION).name()));
        links.add(String.format("%s:snmpd", containerInfo.get(SNMPD).name()));
        links.add(String.format("%s:tomcat", containerInfo.get(TOMCAT).name()));

        final HostConfig minionHostConfig = HostConfig.builder()
                .publishAllPorts(true)
                .links(links)
                .build();

        docker.startContainer(minionContainerId, minionHostConfig);

        final ContainerInfo minionInfo = docker.inspectContainer(minionContainerId);
        LOG.info("Minion container info: {}", minionInfo);
        if (!minionInfo.state().running()) {
            throw new IllegalStateException("Could not start Minion container");
        }

        containerInfo.put(MINION, minionInfo);
    }

    /**
     * Blocks until the PostgreSQL server is up and running.
     */
    private void waitForPostgres() {
        final InetSocketAddress postgresAddr = getServiceAddress(POSTGRES, 5432);
        LOG.info("Waiting for PostgreSQL service @ {}.", postgresAddr);
        await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(DBUtils.canConnectToPostgres(postgresAddr, "postgres", "postgres", "postgres"));
        LOG.info("PostgreSQL service is online.");
    }

    /**
     * Configures the Dominion container:
     * 1) Waits for both the REST service and Karaf's SSH shell to be accessible.
     * 2) Installs the Dominion features via the Karaf shell.
     * 3) Installs the sampler-rrd-storage feature with an MBean call
     */
    private void configureDominion() throws Exception {
        final InetSocketAddress httpAddr = getServiceAddress(DOMINION, 8980);
        final RESTClient restClient = new RESTClient(httpAddr);
        final Callable<String> getDisplayVersion = new Callable<String>() {
            @Override
            public String call() throws Exception {
                try {
                    return restClient.getDisplayVersion();
                } catch (Throwable t) {
                    LOG.debug("Version lookup failed.", t);
                    return null;
                }
            }
        };

        LOG.info("Waiting for REST service @ {}.", httpAddr);
        await().atMost(5, MINUTES).pollInterval(15, SECONDS).until(getDisplayVersion, is(notNullValue()));
        LOG.info("Dominion's REST service is online.");

        final InetSocketAddress sshAddr = getServiceAddress(DOMINION, 8101);
        LOG.info("Waiting for SSH service @ {}.", sshAddr);
        await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(SSHClient.canConnectViaSsh(sshAddr, "admin", "admin"));
        LOG.info("Dominion's Karaf Shell is online.");

        try (
            final SSHClient sshClient = new SSHClient(sshAddr, "admin", "admin");
        ) {
            PrintStream pipe = sshClient.openShell();
            pipe.println("source http://localhost:8980/minion/opennms-setup.karaf");
            pipe.println("config:edit org.opennms.netmgt.sampler.storage.rrd");
            pipe.println("config:propset rrdStorageDirectory /opt/opennms/share/rrd/snmp");
            pipe.println("config:propset step 15");
            pipe.println("config:propset heartBeat 30");
            pipe.println("config:update");
            pipe.println("logout");
            try {
                await().atMost(2, MINUTES).until(sshClient.isShellClosedCallable());
            } finally {
                LOG.info("Karaf output: {}", sshClient.getStdout());
            }
        }

        final InetSocketAddress jmxAddr = getServiceAddress(DOMINION, 18980);
        final InetSocketAddress rmiAddr = getServiceAddress(DOMINION, 1099);
        KarafMBeanProxyHelper mbeanHelper = new KarafMBeanProxyHelper(jmxAddr, rmiAddr, "admin", "admin");
        FeaturesServiceMBean featuresService = mbeanHelper.getFeaturesService("opennms");

        // These features should be installed by the setup script
        assertThat(isFeatureInstalled(featuresService, "opennms-activemq"), is(true));
        assertThat(isFeatureInstalled(featuresService, "sample-receiver-activemq"), is(true));
        assertThat(isFeatureInstalled(featuresService, "minion-base"), is(true));
        assertThat(isFeatureInstalled(featuresService, "dominion-controller-statuswriter-dao"), is(true));
        assertThat(isFeatureInstalled(featuresService, "dominion-controller"), is(true));

        // Used to persist samples to RRDs
        featuresService.installFeature("sample-storage-rrd");
        assertThat(isFeatureInstalled(featuresService, "sample-storage-rrd"), is(true));
    }

    /**
     * Configures the Minion container by invoking the setup script
     * hosted on Dominion with the various instance names.
     */
    private void configureMinion() throws Exception {
        final Map<String, Integer> instanceNameToPort = Maps.newLinkedHashMap();
        instanceNameToPort.put("activemq", 8202);
        instanceNameToPort.put("minion", 8203);
        instanceNameToPort.put("sampler", 8204);

        final ContainerInfo minionInfo = containerInfo.get(MINION);
        configureMinionKarafInstance("root", 8101, minionInfo);

        for (Map.Entry<String, Integer> entry : instanceNameToPort.entrySet()) {
            final String instanceName = entry.getKey();
            final int servicePort = entry.getValue();
            configureMinionKarafInstance(instanceName, servicePort, minionInfo);
        }
    }

    private void configureMinionKarafInstance(final String instanceName, final int servicePort, final ContainerInfo minionInfo ) throws Exception {
        final InetSocketAddress sshAddr = getServiceAddress(MINION, servicePort);

        LOG.info("Waiting for SSH service for Karaf instance {} @ {}.", instanceName, sshAddr);
        await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(SSHClient.canConnectViaSsh(sshAddr, "karaf", "karaf"));

        // These scripts can fail from time to time, so we try running them a few times before failing.
        final int NUM_RETRIES = 2;
        for (int k = 0; k <= NUM_RETRIES; k++) {
            LOG.info("Running setup script on Karaf instance {} (retry #{} of {}).", instanceName, k, NUM_RETRIES);
            final String script = String.format(
                    "addcommand system (($.context bundle) loadClass java.lang.System)"
                  + ";DOMINION_HTTP_HOST=system:getenv DOMINION_PORT_8980_TCP_ADDR"
                  + ";DOMINION_HTTP_PORT=system:getenv DOMINION_PORT_8980_TCP_PORT"
                  + ";DOMINION_MQ_HOST=system:getenv DOMINION_PORT_61616_TCP_ADDR"
                  + ";DOMINION_MQ_PORT=system:getenv DOMINION_PORT_61616_TCP_PORT"
                  + ";DOMINION_HTTP=http://$DOMINION_HTTP_HOST:$DOMINION_HTTP_PORT"
                  + ";source $DOMINION_HTTP/minion/minion-setup.karaf %s admin admin $DOMINION_HTTP minion1",
                  instanceName);

            try (
                    final SSHClient sshClient = new SSHClient(sshAddr, "karaf", "karaf");
                ) {
                    PrintStream pipe = sshClient.openShell();
                    pipe.println(script);
                    pipe.println("logout");
                    try {
                        await().atMost(1, MINUTES).until(sshClient.isShellClosedCallable());
                    } finally {
                        LOG.info("Karaf output: {}", sshClient.getStdout());
                    }
                }
            catch (Throwable t) {
                if (k == NUM_RETRIES) {
                    // Re-throw the exception after the last retry
                    throw t;
                }
                // Otherwise, try again
                continue;
            }

            // No exceptions, we're done
            break;
        }
    }
}
