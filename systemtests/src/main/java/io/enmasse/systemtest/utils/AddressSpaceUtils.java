/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.EndpointSpec;
import io.enmasse.address.model.EndpointStatus;
import io.enmasse.systemtest.*;
import io.enmasse.systemtest.timemeasuring.SystemtestsOperation;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class AddressSpaceUtils {
    private static Logger log = CustomLogger.getLogger();

    public static void syncAddressSpaceObject(AddressSpace addressSpace) {
        AddressSpace data = Kubernetes.getInstance().getAddressSpaceClient(addressSpace.getMetadata().getNamespace())
                .withName(addressSpace.getMetadata().getName()).get();
        addressSpace.setMetadata(data.getMetadata());
        addressSpace.setSpec(data.getSpec());
        addressSpace.setStatus(data.getStatus());
    }

    public static JsonObject addressSpaceToJson(AddressSpace addressSpace) throws Exception {
        return new JsonObject(new ObjectMapper().writeValueAsString(addressSpace));
    }

    public static String getAddressSpaceInfraUuid(AddressSpace addressSpace) {
        return addressSpace.getMetadata().getAnnotations().get("enmasse.io/infra-uuid");
    }

    public static boolean existAddressSpace(String namespace, String addressSpaceName) {
        log.info("Following addressspaces are deployed {} in namespace {}", Kubernetes.getInstance().getAddressSpaceClient(namespace).list().getItems().stream()
                .map(addressSpace -> addressSpace.getMetadata().getName()).collect(Collectors.toList()), namespace);
        return Kubernetes.getInstance().getAddressSpaceClient(namespace).list().getItems().stream()
                .map(addressSpace -> addressSpace.getMetadata().getName()).collect(Collectors.toList()).contains(addressSpaceName);
    }

    public static boolean isAddressSpaceReady(AddressSpace addressSpace) {
        return addressSpace != null && addressSpace.getStatus().isReady();
    }

    public static boolean matchAddressSpacePlan(AddressSpace received, AddressSpace expected) {
        return received != null && received.getMetadata().getAnnotations().get("enmasse.io/applied-plan").equals(expected.getSpec().getPlan());
    }

    public static AddressSpace waitForAddressSpaceReady(AddressSpace addressSpace) throws Exception {
        return waitForAddressSpaceReady(addressSpace, new TimeoutBudget(15, TimeUnit.MINUTES));
    }

    public static AddressSpace waitForAddressSpaceReady(AddressSpace addressSpace, TimeoutBudget budget) throws Exception {
        boolean isReady = false;
        var client = Kubernetes.getInstance().getAddressSpaceClient(addressSpace.getMetadata().getNamespace());
        while (budget.timeLeft() >= 0 && !isReady) {
            addressSpace = client.withName(addressSpace.getMetadata().getName()).get();
            isReady = isAddressSpaceReady(addressSpace);
            if (!isReady) {
                Thread.sleep(10000);
            }
            log.info("Waiting until Address space: '{}' messages {} will be in ready state", addressSpace.getMetadata().getName(), addressSpace.getStatus().getMessages());
        }
        if (!isReady) {
            String status = addressSpace.getStatus().toString();
            throw new IllegalStateException("Address Space " + addressSpace + " is not in Ready state within timeout: " + status);
        }
        log.info("Address space {} is ready for use", addressSpace);
        return addressSpace;
    }

    public static void waitForAddressSpacePlanApplied(AddressSpace addressSpace) throws Exception {
        AddressSpace addressSpaceObject = null;
        TimeoutBudget budget = new TimeoutBudget(15, TimeUnit.MINUTES);

        boolean isPlanApplied = false;
        while (budget.timeLeft() >= 0 && !isPlanApplied) {
            addressSpaceObject = Kubernetes.getInstance().getAddressSpaceClient(addressSpace.getMetadata().getNamespace()).withName(addressSpace.getMetadata().getName()).get();
            isPlanApplied = matchAddressSpacePlan(addressSpaceObject, addressSpace);
            if (!isPlanApplied) {
                Thread.sleep(2000);
            }
            log.info("Waiting until Address space plan will be applied: '{}', current: {}",
                    addressSpace.getSpec().getPlan(),
                    addressSpaceObject.getMetadata().getAnnotations().get("enmasse.io/applied-plan"));
        }
        isPlanApplied = matchAddressSpacePlan(addressSpaceObject, addressSpace);
        if (!isPlanApplied) {
            String jsonStatus = addressSpaceObject != null ? addressSpaceObject.getMetadata().getAnnotations().get("enmasse.io/applied-plan") : "";
            throw new IllegalStateException("Address Space " + addressSpace + " contains wrong plan: " + jsonStatus);
        }
        log.info("Address plan {} successfully applied", addressSpace.getSpec().getPlan());
    }

    public static void deleteAddressSpaceAndWait(AddressSpace addressSpace, GlobalLogCollector logCollector) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.DELETE_ADDRESS_SPACE);
        deleteAddressSpace(addressSpace, logCollector);
        waitForAddressSpaceDeleted(addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    private static void deleteAddressSpace(AddressSpace addressSpace, GlobalLogCollector logCollector) throws Exception {
        logCollector.collectEvents();
        logCollector.collectApiServerJmapLog();
        logCollector.collectLogsTerminatedPods();
        logCollector.collectConfigMaps();
        logCollector.collectRouterState("deleteAddressSpace");
        Kubernetes.getInstance().getAddressSpaceClient(addressSpace.getMetadata().getNamespace()).withName(addressSpace.getMetadata().getName()).cascading(true).delete();
    }

    public static void deleteAllAddressSpaces(GlobalLogCollector logCollector) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.DELETE_ADDRESS_SPACE);
        logCollector.collectEvents();
        logCollector.collectApiServerJmapLog();
        logCollector.collectLogsTerminatedPods();
        logCollector.collectConfigMaps();
        logCollector.collectRouterState("deleteAddressSpace");
        Kubernetes.getInstance().getAddressSpaceClient().delete();
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void waitForAddressSpaceDeleted(AddressSpace addressSpace) throws Exception {
        Kubernetes kube = Kubernetes.getInstance();
        log.info("Waiting for AddressSpace {} to be deleted", addressSpace.getMetadata().getName());
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        waitForItems(addressSpace, budget, () -> kube.listPods(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listConfigMaps(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listServices(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listSecrets(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listDeployments(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listStatefulSets(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listServiceAccounts(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
        waitForItems(addressSpace, budget, () -> kube.listPersistentVolumeClaims(Collections.singletonMap("infraUuid", getAddressSpaceInfraUuid(addressSpace))));
    }

    private static <T> void waitForItems(AddressSpace addressSpace, TimeoutBudget budget, Callable<List<T>> callable) throws Exception {
        List<T> resources = null;
        while (budget.timeLeft() >= 0) {
            resources = callable.call();
            if (resources == null || resources.isEmpty()) {
                break;
            }
            Thread.sleep(1000);
        }
        resources = callable.call();
        if (resources != null && resources.size() > 0) {
            throw new TimeoutException("Timed out waiting for address space " + addressSpace.getMetadata().getName() + " to disappear. Resources left: " + resources);
        }
    }

    public static Endpoint getEndpointByName(AddressSpace addressSpace, String endpoint) {
        for (EndpointSpec addrSpaceEndpoint : addressSpace.getSpec().getEndpoints()) {
            if (addrSpaceEndpoint.getName().equals(endpoint)) {
                EndpointStatus status = getEndpointByName(addrSpaceEndpoint.getName(), addressSpace.getStatus().getEndpointStatuses());
                log.debug("Got endpoint: name: {}, service-name: {}, host: {}, port: {}",
                        addrSpaceEndpoint.getName(), addrSpaceEndpoint.getService(), status.getExternalHost(),
                        status.getExternalPorts().values().stream().findAny().get());
                if (status.getExternalHost() == null) {
                    return null;
                } else {
                    return new Endpoint(status.getExternalHost(), status.getExternalPorts().values().stream().findAny().get());
                }
            }
        }
        throw new IllegalStateException(String.format("Endpoint wih name '%s-%s' doesn't exist in address space '%s'",
                endpoint, getAddressSpaceInfraUuid(addressSpace), addressSpace.getMetadata().getName()));
    }

    public static Endpoint getEndpointByServiceName(AddressSpace addressSpace, String endpointService) {
        for (EndpointSpec addrSpaceEndpoint : addressSpace.getSpec().getEndpoints()) {
            if (addrSpaceEndpoint.getService().equals(endpointService)) {
                EndpointStatus status = getEndpointByServiceName(addrSpaceEndpoint.getService(), addressSpace.getStatus().getEndpointStatuses());
                log.info("Got endpoint: name: {}, service-name: {}, host: {}, port: {}",
                        addrSpaceEndpoint.getName(), addrSpaceEndpoint.getService(), status.getExternalHost(),
                        status.getExternalPorts().values().stream().findAny().get());
                if (status.getExternalHost() == null) {
                    return null;
                } else {
                    return new Endpoint(status.getExternalHost(), status.getExternalPorts().values().stream().findAny().get());
                }
            }
        }
        throw new IllegalStateException(String.format("Endpoint with service name '%s' doesn't exist in address space '%s'",
                endpointService, addressSpace.getMetadata().getName()));
    }

    public static String getExternalEndpointName(AddressSpace addressSpace, String service) {
        for (EndpointSpec endpoint : addressSpace.getSpec().getEndpoints()) {
            if (endpoint.getService().equals(service) && endpoint.getName() != null && !endpoint.getName().isEmpty()) {
                return endpoint.getName();
            }
        }
        return service;
    }

    private static EndpointStatus getEndpointByName(String name, List<EndpointStatus> endpoints) {
        return endpoints.stream().filter(endpointStatus -> endpointStatus.getName().equals(name)).findAny().get();
    }

    private static EndpointStatus getEndpointByServiceName(String serviceName, List<EndpointStatus> endpoints) {
        return endpoints.stream().filter(endpointStatus -> endpointStatus.getServiceHost().startsWith(serviceName)).findAny().get();
    }
}
