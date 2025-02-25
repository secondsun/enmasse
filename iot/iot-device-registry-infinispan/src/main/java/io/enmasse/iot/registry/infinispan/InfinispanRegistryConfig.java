/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.iot.registry.infinispan;

import java.util.UUID;
import org.eclipse.hono.deviceregistry.ApplicationConfig;
import org.eclipse.hono.service.tenant.TenantAmqpEndpoint;
import org.eclipse.hono.service.tenant.TenantHttpEndpoint;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.annotations.ProtoSchemaBuilder;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import org.springframework.context.annotation.Scope;

/**
 * Spring Boot configuration for the Device Registry application.
 *
 */
@Configuration
public class InfinispanRegistryConfig extends ApplicationConfig {

    @Bean
    public RemoteCache<CredentialsKey, RegistryCredentialObject> getCredentialsCache() throws IOException {
        return getCache();
    }

    @Bean
    public RemoteCache<RegistrationKey, String> getRegistrationCache() throws IOException {
        return getCache();
    }

    @Bean
    public RemoteCache<String, RegistryTenantObject> getTenantCache() throws IOException {
        return getCache();
    }

    /**
     * Connects to an infinispan server and create a randomly named RemoteCache.
     * The constructor will use the hotrod-client.properties file that must be in the classpath.
     *
     * @throws IOException if the Protobuf spec file cannot be created.
     * @return an RemoteCacheManager bean.
     */
    protected <K, V> RemoteCache<K,V> getCache() throws IOException {

        final RemoteCacheManager remoteCacheManager = new RemoteCacheManager();
        final SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);

        // genereate the protobuff schema
        String generatedSchema = new ProtoSchemaBuilder()
                .addClass(RegistryTenantObject.class)
                .addClass(RegistryCredentialObject.class)
                .addClass(CredentialsKey.class)
                .addClass(RegistrationKey.class)
                .packageName("registry")
                .fileName("registry.proto")
                .build(serCtx);

        // register the schema with the server
        remoteCacheManager.getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME)
            .put("registry.proto", generatedSchema);

        final String cacheName = UUID.randomUUID().toString();
        return remoteCacheManager.administration().createCache(cacheName, new ConfigurationBuilder().build());
    }

    /**
     * Creates a new instance of an AMQP 1.0 protocol handler for Hono's <em>Tenant</em> API.
     *
     * @return The handler.
     */
    @Bean
    @Override
    @Scope("prototype")
    @ConditionalOnBean(name="CacheTenantService")
    public TenantAmqpEndpoint tenantAmqpEndpoint() {
        return new TenantAmqpEndpoint(vertx());
    }

    /**
     * Creates a new instance of an HTTP protocol handler for Hono's <em>Tenant</em> API.
     *
     * @return The handler.
     */
    @Bean
    @Override
    @Scope("prototype")
    @ConditionalOnBean(name="CacheTenantService")
    public TenantHttpEndpoint tenantHttpEndpoint() {
        return new TenantHttpEndpoint(vertx());
    }
}
