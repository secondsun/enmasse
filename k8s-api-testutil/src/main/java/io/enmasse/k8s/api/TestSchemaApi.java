/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.k8s.api;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import io.enmasse.address.model.AddressSpaceTypeBuilder;
import io.enmasse.address.model.AddressTypeBuilder;
import io.enmasse.address.model.EndpointSpecBuilder;
import io.enmasse.address.model.Schema;
import io.enmasse.address.model.SchemaBuilder;
import io.enmasse.admin.model.v1.AddressPlanBuilder;
import io.enmasse.admin.model.v1.AddressSpacePlanBuilder;
import io.enmasse.admin.model.v1.AuthenticationServiceBuilder;
import io.enmasse.admin.model.v1.ResourceAllowanceBuilder;
import io.enmasse.admin.model.v1.ResourceRequestBuilder;
import io.enmasse.admin.model.v1.StandardInfraConfigBuilder;
import io.enmasse.admin.model.v1.StandardInfraConfigSpecBuilder;
import io.enmasse.config.AnnotationKeys;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

public class TestSchemaApi implements SchemaApi {
    public Schema getSchema() {
        return new SchemaBuilder()
                .withAddressSpaceTypes(Collections.singletonList(
                        new AddressSpaceTypeBuilder()
                                .withName("type1")
                                .withDescription("Test Type")
                                .withAvailableEndpoints(Collections.singletonList(new EndpointSpecBuilder()
                                        .withName("messaging")
                                        .withService("messaging")
                                        .build()))
                                .withAddressTypes(Arrays.asList(
                                        new AddressTypeBuilder()
                                                .withName("anycast")
                                                .withDescription("Test direct")
                                                .withPlans(Arrays.asList(
                                                        new AddressPlanBuilder()
                                                                .withMetadata(new ObjectMetaBuilder()
                                                                        .withName("plan1")
                                                                        .build())
                                                                .withAddressType("anycast")
                                                                .withRequiredResources(Arrays.asList(
                                                                        new ResourceRequestBuilder()
                                                                        .withName("router")
                                                                        .withCredit(1.0)
                                                                        .build()))
                                                                .build()
                                                ))
                                                .build(),
                                        new AddressTypeBuilder()
                                                .withName("queue")
                                                .withDescription("Test queue")
                                                .withPlans(Arrays.asList(
                                                        new AddressPlanBuilder()
                                                                .withMetadata(new ObjectMetaBuilder()
                                                                        .withName("pooled-inmemory")
                                                                        .build())
                                                                .withAddressType("queue")
                                                                .withRequiredResources(Arrays.asList(
                                                                        new ResourceRequestBuilder()
                                                                                .withName("broker")
                                                                                .withCredit(0.1)
                                                                                .build()))
                                                                .build(),
                                                        new AddressPlanBuilder()
                                                                .withMetadata(new ObjectMetaBuilder()
                                                                        .withName("plan1")
                                                                        .build())
                                                                .withAddressType("queue")
                                                                .withRequiredResources(Arrays.asList(
                                                                        new ResourceRequestBuilder()
                                                                                .withName("broker")
                                                                                .withCredit(1.0)
                                                                                .build()))
                                                                .build())
                                                ).build()
                                ))
                                .withInfraConfigs(Arrays.asList(new StandardInfraConfigBuilder()
                                        .withMetadata(new ObjectMetaBuilder()
                                                .withName("infra")
                                                .build())
                                        .withSpec(new StandardInfraConfigSpecBuilder()
                                                .withVersion("1.0")
                                                .build())
                                        .build()))
                                .withPlans(Collections.singletonList(
                                        new AddressSpacePlanBuilder()
                                                .withMetadata(new ObjectMetaBuilder()
                                                        .addToAnnotations(AnnotationKeys.DEFINED_BY, "infra")
                                                        .withName("myplan")
                                                        .build())
                                                .withAddressSpaceType("type1")
                                                .withResources(Arrays.asList(new ResourceAllowanceBuilder()
                                                        .withName("broker")
                                                        .withMax(1.0)
                                                        .build()))
                                                .withAddressPlans(Arrays.asList("plan1"))
                                                .build()
                                ))
                                .build()

                ))
                .withAuthenticationServices(new AuthenticationServiceBuilder()
                        .withNewMetadata()
                        .withName("standard")
                        .endMetadata()
                        .withNewSpec()
                        .withNewStandard()
                        .endStandard()
                        .endSpec()
                        .withNewStatus()
                        .withHost("example.com")
                        .withPort(5671)
                        .endStatus()
                        .build())
                .build();
    }

    @Override
    public Watch watchSchema(Watcher<Schema> schemaStore, Duration resyncInterval) throws Exception {
        return null;
    }

}
