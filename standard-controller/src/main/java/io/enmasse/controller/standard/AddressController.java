/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller.standard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.enmasse.address.model.*;
import io.enmasse.admin.model.AddressPlan;
import io.enmasse.admin.model.AddressSpacePlan;
import io.enmasse.admin.model.v1.InfraConfig;
import io.enmasse.admin.model.v1.StandardInfraConfig;
import io.enmasse.admin.model.v1.StandardInfraConfigBuilder;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.k8s.api.*;
import io.enmasse.metrics.api.*;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.enmasse.address.model.Phase.*;
import static io.enmasse.controller.standard.ControllerKind.AddressSpace;
import static io.enmasse.controller.standard.ControllerKind.Broker;
import static io.enmasse.controller.standard.ControllerReason.BrokerUpgraded;
import static io.enmasse.controller.standard.ControllerReason.RouterCheckFailed;
import static io.enmasse.k8s.api.EventLogger.Type.Normal;
import static io.enmasse.k8s.api.EventLogger.Type.Warning;

/**
 * Controller for a single standard address space
 */
public class AddressController implements Watcher<Address> {
    private static final Logger log = LoggerFactory.getLogger(AddressController.class);
    private final StandardControllerOptions options;
    private final AddressApi addressApi;
    private final Kubernetes kubernetes;
    private final BrokerSetGenerator clusterGenerator;
    private Watch watch;
    private final EventLogger eventLogger;
    private final SchemaProvider schemaProvider;
    private final Vertx vertx;
    private final Metrics metrics;
    private final BrokerIdGenerator brokerIdGenerator;
    private final BrokerClientFactory brokerClientFactory;
    private int routerCheckFailures;

    public AddressController(StandardControllerOptions options, AddressApi addressApi, Kubernetes kubernetes, BrokerSetGenerator clusterGenerator, EventLogger eventLogger, SchemaProvider schemaProvider, Vertx vertx, Metrics metrics, BrokerIdGenerator brokerIdGenerator, BrokerClientFactory brokerClientFactory) {
        this.options = options;
        this.addressApi = addressApi;
        this.kubernetes = kubernetes;
        this.clusterGenerator = clusterGenerator;
        this.eventLogger = eventLogger;
        this.schemaProvider = schemaProvider;
        this.vertx = vertx;
        this.metrics = metrics;
        this.brokerIdGenerator = brokerIdGenerator;
        this.brokerClientFactory = brokerClientFactory;
        this.routerCheckFailures = 0;
    }

    public void start() throws Exception {
        ResourceChecker<Address> checker = new ResourceChecker<>(this, options.getRecheckInterval());
        checker.start();
        watch = addressApi.watchAddresses(checker, options.getResyncInterval());
    }

    public void stop() throws Exception {
        if (watch != null) {
            watch.close();
        }
    }

    @Override
    public void onUpdate(List<Address> addressList) throws Exception {
        long start = System.nanoTime();
        Schema schema = schemaProvider.getSchema();
        if (schema == null) {
            log.info("No schema available");
            return;
        }
        AddressSpaceType addressSpaceType = schema.findAddressSpaceType("standard").orElseThrow(() -> new RuntimeException("Unable to handle updates: standard address space not found in schema!"));
        AddressResolver addressResolver = new AddressResolver(addressSpaceType);
        if (addressSpaceType.getPlans().isEmpty()) {
            log.info("No address space plan available");
            return;
        }

        AddressSpaceResolver addressSpaceResolver = new AddressSpaceResolver(schema);
        Set<Address> addressSet = new LinkedHashSet<>(addressList);

        final Map<String, ProvisionState> previousStatus = addressSet.stream()
                .collect(Collectors.toMap(a -> a.getSpec().getAddress(),
                                          a -> new ProvisionState(a.getStatus(), a.getAnnotation(AnnotationKeys.APPLIED_PLAN))));

        AddressSpacePlan addressSpacePlan = addressSpaceType.findAddressSpacePlan(options.getAddressSpacePlanName()).orElseThrow(() -> new RuntimeException("Unable to handle updates: address space plan " + options.getAddressSpacePlanName() + " not found!"));
        InfraConfig infraConfig = addressSpaceResolver.getInfraConfig("standard", addressSpacePlan.getMetadata().getName());
        boolean withMqtt = isWithMqtt(infraConfig);

        long resolvedPlan = System.nanoTime();

        AddressProvisioner provisioner = new AddressProvisioner(addressSpaceResolver, addressResolver, addressSpacePlan, clusterGenerator, kubernetes, eventLogger, options.getInfraUuid(), brokerIdGenerator);

        List<Phase> readyPhases = Arrays.asList(Configuring, Active);
        for (Address address : addressList) {
            address.getStatus().clearMessages();
            if (readyPhases.contains(address.getStatus().getPhase())) {
                address.getStatus().setReady(true);
            }
        }

        Map<Phase, Long> countByPhase = countPhases(addressSet);
        log.info("Total: {}, Active: {}, Configuring: {}, Pending: {}, Terminating: {}, Failed: {}", addressSet.size(), countByPhase.get(Active), countByPhase.get(Configuring), countByPhase.get(Pending), countByPhase.get(Terminating), countByPhase.get(Failed));

        Map<String, Map<String, UsageInfo>> usageMap = provisioner.checkUsage(filterByNotPhases(addressSet, EnumSet.of(Pending)));

        log.info("Usage: {}", usageMap);

        long calculatedUsage = System.nanoTime();
        Set<Address> pendingAddresses = filterBy(addressSet, address -> address.getStatus().getPhase().equals(Pending) ||
                    AddressProvisioner.hasPlansChanged(addressResolver, address));

        Map<String, Map<String, UsageInfo>> neededMap = provisioner.checkQuota(usageMap, pendingAddresses, addressSet);

        log.info("Needed: {}", neededMap);

        long checkedQuota = System.nanoTime();

        List<BrokerCluster> clusterList = kubernetes.listClusters();
        RouterCluster routerCluster = kubernetes.getRouterCluster();
        long listClusters = System.nanoTime();

        StandardInfraConfig desiredConfig = (StandardInfraConfig) addressSpaceResolver.getInfraConfig("standard", addressSpacePlan.getMetadata().getName());
        provisioner.provisionResources(routerCluster, clusterList, neededMap, pendingAddresses, desiredConfig);

        long provisionResources = System.nanoTime();

        Set<Address> liveAddresses = filterByPhases(addressSet, EnumSet.of(Configuring, Active));
        List<RouterStatus> routerStatusList = checkRouterStatuses();

        Set<String> subserveTopics = Collections.emptySet();
        if (!routerStatusList.isEmpty()) {
            if (withMqtt) {
                subserveTopics = checkRegisteredSubserveTopics();
            }
            checkAddressStatuses(liveAddresses, addressResolver, routerStatusList, subserveTopics, withMqtt);
        }

        long checkStatuses = System.nanoTime();
        for (Address address : liveAddresses) {
            if (address.getStatus().isReady()) {
                address.getStatus().setPhase(Active);
            }
        }

        checkAndMoveMigratingBrokersToDraining(addressSet, clusterList);
        checkAndRemoveDrainingBrokers(addressSet);

        deprovisionUnused(clusterList, filterByNotPhases(addressSet, EnumSet.of(Terminating)));
        long deprovisionUnused = System.nanoTime();

        upgradeClusters(desiredConfig, addressResolver, clusterList, filterByNotPhases(addressSet, EnumSet.of(Terminating)));

        long upgradeClusters = System.nanoTime();

        int staleCount = 0;
        for (Address address : addressSet) {
            ProvisionState previous = previousStatus.get(address.getSpec().getAddress());
            ProvisionState current = new ProvisionState(address.getStatus(), address.getAnnotation(AnnotationKeys.APPLIED_PLAN));
            if (!current.equals(previous)) {
                try {
                    addressApi.replaceAddress(address);
                } catch (KubernetesClientException e) {
                    if (e.getStatus().getCode() == 409) {
                        // The address record is stale.  The address controller will be notified again by the watcher,
                        // so safe ignore the stale record.
                        log.debug("Address {} has stale resource version {}", address.getMetadata().getName(), address.getMetadata().getResourceVersion());
                        staleCount++;
                    } else {
                        throw e;
                    }
                }
            }
        }

        if (staleCount > 0) {
            log.info("{} address(es) were stale.", staleCount);
        }

        long replaceAddresses = System.nanoTime();
        garbageCollectTerminating(filterByPhases(addressSet, EnumSet.of(Terminating)), addressResolver, routerStatusList, subserveTopics, withMqtt);
        long gcTerminating = System.nanoTime();

        log.info("total: {} ns, resolvedPlan: {} ns, calculatedUsage: {} ns, checkedQuota: {} ns, listClusters: {} ns, provisionResources: {} ns, checkStatuses: {} ns, deprovisionUnused: {} ns, upgradeClusters: {} ns, replaceAddresses: {} ns, gcTerminating: {} ns", gcTerminating - start, resolvedPlan - start, calculatedUsage - resolvedPlan,  checkedQuota  - calculatedUsage, listClusters - checkedQuota, provisionResources - listClusters, checkStatuses - provisionResources, deprovisionUnused - checkStatuses, upgradeClusters - deprovisionUnused, replaceAddresses - upgradeClusters, gcTerminating - replaceAddresses);

        float ready = 0;
        for (Address address : addressList) {
            ready += address.getStatus().isReady() ? 1 : 0;
        }
        float notReady = addressList.size() - ready;

        long now = System.currentTimeMillis();

        String componentName = "standard-controller-" + options.getInfraUuid();
        metrics.reportMetric(new Metric("version", "The version of the standard-controller", MetricType.gauge, new MetricValue(0, now, new MetricLabel("name", componentName), new MetricLabel("version", options.getVersion()))));

        MetricLabel [] metricLabels = new MetricLabel[]{new MetricLabel("addressspace", options.getAddressSpace()), new MetricLabel("namespace", options.getAddressSpaceNamespace())};
        if (routerStatusList.isEmpty()) {
            ready = Float.NaN;
            notReady = Float.NaN;

        }
        metrics.reportMetric(new Metric("addresses_ready_total", "Total number of addresses in ready state", MetricType.gauge, new MetricValue(ready, now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_not_ready_total", "Total number of address in a not ready state", MetricType.gauge, new MetricValue(notReady, now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_total", "Total number of addresses", MetricType.gauge, new MetricValue(addressList.size(), now, metricLabels)));

        metrics.reportMetric(new Metric("addresses_pending_total", "Total number of addresses in Pending state", MetricType.gauge, new MetricValue(countByPhase.get(Pending), now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_failed_total", "Total number of addresses in Failed state", MetricType.gauge, new MetricValue(countByPhase.get(Failed), now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_terminating_total", "Total number of addresses in Terminating state", MetricType.gauge, new MetricValue(countByPhase.get(Terminating), now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_configuring_total", "Total number of addresses in Configuring state", MetricType.gauge, new MetricValue(countByPhase.get(Configuring), now, metricLabels)));
        metrics.reportMetric(new Metric("addresses_active_total", "Total number of addresses in Active state", MetricType.gauge, new MetricValue(countByPhase.get(Active), now, metricLabels)));

        long totalTime = gcTerminating - start;
        metrics.reportMetric(new Metric("standard_controller_loop_duration_seconds", "Time spent in controller loop", MetricType.gauge, new MetricValue((double) totalTime / 1_000_000_000.0, now, metricLabels)));

        metrics.reportMetric(new Metric("standard_controller_router_check_failures_total", "Number of RouterCheckFailures", MetricType.counter, new MetricValue(routerCheckFailures, now, metricLabels)));

    }

    private Set<String> checkRegisteredSubserveTopics() {
        SubserveStatusCollector statusCollector = new SubserveStatusCollector(vertx, options.getCertDir());

        for (Pod router : kubernetes.listRouters()) {
            if (Readiness.isPodReady(router)) {
                try {
                    return statusCollector.collect(router);
                } catch (Exception e) {
                    log.info("Error requesting registered topics from {}. Ignoring", router.getMetadata().getName(), e);
                }
            }
        }
        return Collections.emptySet();
    }

    private boolean isWithMqtt(InfraConfig infraConfig) {
        return infraConfig.getMetadata().getAnnotations() != null && Boolean.parseBoolean(infraConfig.getMetadata().getAnnotations().getOrDefault(AnnotationKeys.WITH_MQTT, "false"));
    }

    private void upgradeClusters(StandardInfraConfig desiredConfig, AddressResolver addressResolver, List<BrokerCluster> clusterList, Set<Address> addresses) throws Exception {
        for (BrokerCluster cluster : clusterList) {
            final StandardInfraConfig currentConfig = cluster.getInfraConfig();
            if (!desiredConfig.equals(currentConfig)) {
                if (options.getVersion().equals(desiredConfig.getSpec().getVersion())) {
                    if (!desiredConfig.getUpdatePersistentVolumeClaim() && currentConfig != null && !currentConfig.getSpec().getBroker().getResources().getStorage().equals(desiredConfig.getSpec().getBroker().getResources().getStorage())) {
                        desiredConfig = new StandardInfraConfigBuilder(desiredConfig)
                                .editSpec()
                                .editBroker()
                                .editResources()
                                .withStorage(currentConfig.getSpec().getBroker().getResources().getStorage())
                                .endResources()
                                .endBroker()
                                .endSpec()
                                .build();
                    }
                    BrokerCluster upgradedCluster = null;
                    if (!cluster.getClusterId().startsWith("broker-sharded")) {
                        upgradedCluster = clusterGenerator.generateCluster(cluster.getClusterId(), 1, null, null, desiredConfig);
                    } else {
                        Address address = addresses.stream()
                                .filter(a -> a.getStatus().getBrokerStatuses().stream().map(BrokerStatus::getClusterId).collect(Collectors.toSet()).contains(cluster.getClusterId()))
                                .findFirst()
                                .orElse(null);
                        if (address != null) {
                            AddressPlan plan = addressResolver.getPlan(address);
                            int brokerNeeded = 0;
                            for (Map.Entry<String, Double> resourceRequest : plan.getResources().entrySet()) {
                                if (resourceRequest.getKey().equals("broker")) {
                                    brokerNeeded = resourceRequest.getValue().intValue();
                                    break;
                                }
                            }
                            upgradedCluster = clusterGenerator.generateCluster(cluster.getClusterId(), brokerNeeded, address, plan, desiredConfig);
                        }
                    }
                    log.info("Upgrading broker {}", cluster.getClusterId());
                    cluster.updateResources(upgradedCluster, desiredConfig);
                    boolean updatePersistentVolumeClaim = desiredConfig.getUpdatePersistentVolumeClaim();
                    List<HasMetadata> itemsToBeApplied = new ArrayList<>(cluster.getResources().getItems());
                    try {
                        kubernetes.apply(cluster.getResources(), updatePersistentVolumeClaim, itemsToBeApplied::remove);
                    } catch (KubernetesClientException original) {
                        // Workaround for #2880 Failure executing: PATCH... Message: Unable to access invalid index: 20.
                        if (!itemsToBeApplied.isEmpty() && original.getMessage() != null && original.getMessage().contains("Unable to access invalid index")) {
                            HasMetadata failedResource = itemsToBeApplied.get(0);
                            if (failedResource instanceof StatefulSet || failedResource instanceof Deployment) {
                                log.warn("Failed to apply {} for cluster {}, will try #2880 workaround", failedResource, cluster.getClusterId(), original);
                                try {
                                    if (failedResource instanceof StatefulSet) {
                                        StatefulSetSpec spec = ((StatefulSet) failedResource).getSpec();
                                        stripEnvironmentFromResource(spec.getTemplate());
                                        spec.setReplicas(0);
                                    } else {
                                        DeploymentSpec spec = ((Deployment) failedResource).getSpec();
                                        stripEnvironmentFromResource(spec.getTemplate());
                                        spec.setReplicas(0);
                                    }
                                    Kubernetes.addObjectAnnotation(failedResource, AnnotationKeys.APPLIED_INFRA_CONFIG, new ObjectMapper().writeValueAsString(currentConfig));
                                    kubernetes.apply(failedResource, updatePersistentVolumeClaim);
                                    log.warn("Applied #2880 workaround for {} of {}, next upgrade cycle should complete upgrade.", failedResource.getMetadata(), cluster.getClusterId());
                                } catch (KubernetesClientException e) {
                                    log.error("Failed to apply failed resource {} of {} for #2880 workaround. " +
                                            "Manual intervention may be required to complete upgrade", failedResource.getMetadata(), cluster.getClusterId());
                                }
                            } else {
                                log.warn("Don't know how to work around #2880 for resource type {}", failedResource.getClass());
                            }
                        }
                        throw original;
                    }
                    eventLogger.log(BrokerUpgraded, "Upgraded broker", Normal, Broker, cluster.getClusterId());
                } else {
                    log.info("Version of desired config ({}) does not match controller version ({}), skipping upgrade", desiredConfig.getSpec().getVersion(), options.getVersion());
                }
            }
        }
    }

    private void stripEnvironmentFromResource(PodTemplateSpec resource) {
        resource.getSpec().getContainers().forEach(
                c -> {
                    c.setEnv(Collections.emptyList());
                }
        );
        resource.getSpec().getInitContainers().forEach(
                c -> {
                    c.setEnv(Collections.emptyList());
                }
        );
    }

    private void deprovisionUnused(List<BrokerCluster> clusters, Set<Address> addressSet) {
        for (BrokerCluster cluster : clusters) {
            int numFound = 0;
            for (Address address : addressSet) {
                Set<String> clusterIds = address.getStatus().getBrokerStatuses().stream()
                        .map(BrokerStatus::getClusterId)
                        .collect(Collectors.toSet());
                if (clusterIds.contains(cluster.getClusterId())) {
                    numFound++;
                }
                for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                    if (brokerStatus.getClusterId().equals(cluster.getClusterId())) {
                        numFound++;
                    }
                }
            }

            if (numFound == 0) {
                try {
                    kubernetes.delete(cluster.getResources());
                    eventLogger.log(ControllerReason.BrokerDeleted, "Deleted broker " + cluster.getClusterId(), Normal, ControllerKind.Address, cluster.getClusterId());
                } catch (Exception e) {
                    log.warn("Error deleting cluster {}", cluster.getClusterId(), e);
                    eventLogger.log(ControllerReason.BrokerDeleteFailed, "Error deleting broker cluster " + cluster.getClusterId() + ": " + e.getMessage(), EventLogger.Type.Warning, ControllerKind.Address, cluster.getClusterId());
                }
            }
        }
    }

    private Set<Address> filterBy(Set<Address> addressSet, Predicate<Address> predicate) {
        return addressSet.stream()
                .filter(predicate::test)
                .collect(Collectors.toSet());
    }

    private Set<Address> filterByPhases(Set<Address> addressSet, Set<Phase> phases) {
        return addressSet.stream()
                .filter(address -> phases.contains(address.getStatus().getPhase()))
                .collect(Collectors.toSet());
    }

    private Map<Phase,Long> countPhases(Set<Address> addressSet) {
        Map<Phase, Long> countMap = new HashMap<>();
        for (Phase phase : Phase.values()) {
            countMap.put(phase, 0L);
        }
        for (Address address : addressSet) {
            countMap.put(address.getStatus().getPhase(), 1 + countMap.get(address.getStatus().getPhase()));
        }
        return countMap;
    }

    private Set<Address> filterByNotPhases(Set<Address> addressSet, Set<Phase> phases) {
        return addressSet.stream()
                .filter(address -> !phases.contains(address.getStatus().getPhase()))
                .collect(Collectors.toSet());
    }

    private void garbageCollectTerminating(Set<Address> addresses, AddressResolver addressResolver, List<RouterStatus> routerStatusList, Set<String> subserveTopics, boolean withMqtt) throws Exception {
        Map<Address, Integer> okMap = checkAddressStatuses(addresses, addressResolver, routerStatusList, subserveTopics, withMqtt);
        for (Map.Entry<Address, Integer> entry : okMap.entrySet()) {
            if (entry.getValue() == 0) {
                log.info("Garbage collecting {}", entry.getKey());
                addressApi.deleteAddress(entry.getKey());
            }
        }
    }

    private void checkAndMoveMigratingBrokersToDraining(Set<Address> addresses, List<BrokerCluster> brokerList) throws Exception {
        for (Address address : addresses) {
            int numActive = 0;
            int numReadyActive = 0;
            for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                if (BrokerState.Active.equals(brokerStatus.getState())) {
                    numActive++;
                    for (BrokerCluster cluster : brokerList) {
                        if (brokerStatus.getClusterId().equals(cluster.getClusterId()) && cluster.getReadyReplicas() > 0) {
                            numReadyActive++;
                            break;
                        }
                    }
                }
            }

            if (numActive == numReadyActive) {
                for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                    if (BrokerState.Migrating.equals(brokerStatus.getState())) {
                        brokerStatus.setState(BrokerState.Draining);
                    }
                }
            }
        }
    }

    private void checkAndRemoveDrainingBrokers(Set<Address> addresses) throws Exception {
        BrokerStatusCollector brokerStatusCollector = new BrokerStatusCollector(kubernetes, brokerClientFactory);
        for (Address address : addresses) {
            List<BrokerStatus> brokerStatuses = new ArrayList<>();
            for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                if (BrokerState.Draining.equals(brokerStatus.getState())) {
                    try {
                        long messageCount = brokerStatusCollector.getQueueMessageCount(address.getSpec().getAddress(), brokerStatus.getClusterId());
                        if (messageCount > 0) {
                            brokerStatuses.add(brokerStatus);
                        }
                    } catch (Exception e) {
                        log.warn("Error checking status of broker {}:{} in state Draining. Keeping.", brokerStatus.getClusterId(), brokerStatus.getContainerId(), e);
                        brokerStatuses.add(brokerStatus);
                    }
                } else {
                    brokerStatuses.add(brokerStatus);
                }
            }
            address.getStatus().setBrokerStatuses(brokerStatuses);
        }
    }

    private List<RouterStatus> checkRouterStatuses() throws Exception {

        RouterStatusCollector routerStatusCollector = new RouterStatusCollector(vertx, options.getCertDir());
        List<RouterStatus> routerStatusList = new ArrayList<>();
        for (Pod router : kubernetes.listRouters()) {
            if (Readiness.isPodReady(router)) {
                try {
                    RouterStatus routerStatus = routerStatusCollector.collect(router);
                    if (routerStatus != null) {
                        routerStatusList.add(routerStatus);
                    }
                } catch (Exception e) {
                    log.info("Error requesting router status from {}. Ignoring", router.getMetadata().getName(), e);
                    eventLogger.log(RouterCheckFailed, e.getMessage(), Warning, AddressSpace, options.getAddressSpace());
                    routerCheckFailures++;
                }
            }
        }
        return routerStatusList;
    }

    private Map<Address, Integer> checkAddressStatuses(Set<Address> addresses, AddressResolver addressResolver, List<RouterStatus> routerStatusList, Set<String> subserveTopics, boolean withMqtt) throws Exception {

        Map<Address, Integer> numOk = new HashMap<>();
        if (addresses.isEmpty()) {
            return numOk;
        }
        Map<String, Integer> clusterOk = new HashMap<>();
        for (Address address : addresses) {
            AddressType addressType = addressResolver.getType(address);
            AddressPlan addressPlan = addressResolver.getPlan(addressType, address);

            int ok = 0;
            switch (addressType.getName()) {
                case "queue":
                    ok += checkBrokerStatus(address, clusterOk);
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkAddress(address);
                        ok += routerStatus.checkAutoLinks(address);
                    }
                    ok += RouterStatus.checkActiveAutoLink(address, routerStatusList);
                    break;
                case "topic":
                    ok += checkBrokerStatus(address, clusterOk);
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkLinkRoutes(address);
                    }
                    if (isPooled(addressPlan)) {
                        ok += RouterStatus.checkActiveLinkRoute(address, routerStatusList);
                    } else {
                        ok += RouterStatus.checkConnection(address, routerStatusList);
                        if (withMqtt) {
                            SubserveStatusCollector.checkTopicRegistration(subserveTopics, address, addressPlan);
                        }
                    }
                    break;
                case "anycast":
                case "multicast":
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkAddress(address);
                    }
                    break;
            }
            numOk.put(address, ok);
        }

        return numOk;
    }

    private int checkBrokerStatus(Address address, Map<String, Integer> clusterOk) {
        Set<String> clusterIds = address.getStatus().getBrokerStatuses().stream()
                .map(BrokerStatus::getClusterId)
                .collect(Collectors.toSet());
        int numOk = 0;
        for (String clusterId : clusterIds) {
            if (!clusterOk.containsKey(clusterId)) {
                if (!kubernetes.isDestinationClusterReady(clusterId)) {
                    address.getStatus().setReady(false).appendMessage("Cluster " + clusterId + " is unavailable");
                    clusterOk.put(clusterId, 0);
                } else {
                    clusterOk.put(clusterId, 1);
                }
            }
            numOk += clusterOk.get(clusterId);
        }
        return numOk;
    }

    private boolean isPooled(AddressPlan plan) {
        for (Map.Entry<String, Double> request : plan.getResources().entrySet()) {
            if ("broker".equals(request.getKey()) && request.getValue() < 1.0) {
                return true;
            }
        }
        return false;
    }

    private class ProvisionState {
        private final Status status;
        private final String plan;

        public ProvisionState(Status status, String plan) {
            this.status = new Status(status);
            this.plan = plan;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProvisionState that = (ProvisionState) o;
            return Objects.equals(status, that.status) &&
                    Objects.equals(plan, that.plan);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, plan);
        }

        @Override
        public String toString() {
            return "ProvisionState{" +
                    "status=" + status +
                    ", plan='" + plan + '\'' +
                    '}';
        }
    }
}
