/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.olm;

import io.enmasse.systemtest.CustomLogger;
import io.enmasse.systemtest.bases.TestBase;
import io.enmasse.systemtest.cmdclients.KubeCMDClient;
import io.enmasse.systemtest.selenium.ISeleniumProviderFirefox;
import io.enmasse.systemtest.selenium.page.Openshift4WebPage;
import io.enmasse.systemtest.utils.TestUtils;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;

import java.io.IOException;

import static io.enmasse.systemtest.TestTag.isolated;

@Tag(isolated)
class OperatorLifecycleManagerTest extends TestBase implements ISeleniumProviderFirefox {
    private static Logger log = CustomLogger.getLogger();
    private final String marketplaceNamespace = "openshift-marketplace";
    private final String infraNamespace = "openshift-operators";

    @BeforeAll
    void registerCustomOperator() throws Exception {
        createOperatorSource();
        createCSC();
        createSub();
        Thread.sleep(60_000);
        TestUtils.waitUntilDeployed(infraNamespace);
    }

    @AfterAll
    void removeCustomOperator() {
        KubeCMDClient.deleteResource(marketplaceNamespace, "operatorsource", "enmasse-operators");
        KubeCMDClient.deleteResource(marketplaceNamespace, "catalogsourceconfig", "installed-enmasse-operators");
        KubeCMDClient.deleteResource(infraNamespace, "subscription", "enmasse");
        KubeCMDClient.deleteResource(infraNamespace, "clusterserviceversion", "enmasse.0.28.0");
    }

    @AfterEach
    void cleanResources() {
        TestUtils.cleanAllEnmasseResourcesFromNamespace(infraNamespace);
    }

    @Test
    void testCreateExampleResources() throws Exception {
        Openshift4WebPage page = new Openshift4WebPage(selenium, getOCConsoleRoute(), clusterUser);
        page.openOpenshiftPage();
        page.openInstalledOperators();
        page.selectNamespaceFromBar(infraNamespace);
        page.selectOperator("enmasse");
        page.createExampleResourceItem("standardinfraconfig");
        page.createExampleResourceItem("brokeredinfraconfig");
        page.createExampleResourceItem("addressplan");
        page.createExampleResourceItem("addressspaceplan");
        page.createExampleResourceItem("authenticationservice");
        page.createExampleResourceItem("addressspace");
        page.createExampleResourceItem("address");
        page.createExampleResourceItem("messaginguser");
        Thread.sleep(10_000);
        TestUtils.waitUntilDeployed(infraNamespace);
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // Help Methods
    /////////////////////////////////////////////////////////////////////////////////////

    private void createOperatorSource() throws IOException {
        String operatorSource = "apiVersion: operators.coreos.com/v1\n" +
                "kind: OperatorSource\n" +
                "metadata:\n" +
                "  name: enmasse-operators\n" +
                "  namespace: openshift-marketplace\n" +
                "spec:\n" +
                "  type: appregistry\n" +
                "  endpoint: https://quay.io/cnr\n" +
                "  registryNamespace: enmasse\n" +
                "  displayName: \"EnMasse Operators\"\n" +
                "  publisher: \"EnMasse\"";
        KubeCMDClient.applyCR(operatorSource);
    }

    private void createCSC() throws IOException {
        String operatorSource = "apiVersion: operators.coreos.com/v1\n" +
                "kind: CatalogSourceConfig\n" +
                "metadata:\n" +
                "  name: installed-enmasse-operators\n" +
                "  namespace: openshift-marketplace\n" +
                "spec:\n" +
                "  csDisplayName: EnMasse Operators\n" +
                "  csPublisher: EnMasse\n" +
                "  packages: enmasse\n" +
                "  targetNamespace: openshift-operators";
        KubeCMDClient.applyCR(operatorSource);
    }

    private void createSub() throws IOException {
        String operatorSource = "apiVersion: operators.coreos.com/v1alpha1\n" +
                "kind: Subscription\n" +
                "metadata:\n" +
                "  name: enmasse\n" +
                "  namespace: openshift-operators\n" +
                "spec:\n" +
                "  channel: alpha\n" +
                "  name: enmasse\n" +
                "  source: installed-enmasse-operators\n" +
                "  sourceNamespace: openshift-operators";
        KubeCMDClient.applyCR(operatorSource);
    }
}

