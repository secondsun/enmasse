/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.controller.common;

/**
 * Template parameters that are dynamically set by the address space controller.
 */
public interface TemplateParameter {
    String ADDRESS_SPACE = "ADDRESS_SPACE";
    String ADDRESS_SPACE_NAMESPACE = "ADDRESS_SPACE_NAMESPACE";

    String CONSOLE_SECRET = "CONSOLE_SECRET";
    String MESSAGING_SECRET = "MESSAGING_SECRET";
    String MQTT_SECRET = "MQTT_SECRET";

    String AUTHENTICATION_SERVICE_HOST = "AUTHENTICATION_SERVICE_HOST";
    String AUTHENTICATION_SERVICE_PORT = "AUTHENTICATION_SERVICE_PORT";
    String AUTHENTICATION_SERVICE_CA_CERT = "AUTHENTICATION_SERVICE_CA_CERT";
    String AUTHENTICATION_SERVICE_CLIENT_SECRET = "AUTHENTICATION_SERVICE_CLIENT_SECRET";
    String AUTHENTICATION_SERVICE_SASL_INIT_HOST = "AUTHENTICATION_SERVICE_SASL_INIT_HOST";
    String INFRA_UUID = "INFRA_UUID";
    String ADDRESS_SPACE_PLAN = "ADDRESS_SPACE_PLAN";

    String INFRA_NAMESPACE = "INFRA_NAMESPACE";
    String BROKER_MEMORY_LIMIT = "BROKER_MEMORY_LIMIT";
    String BROKER_STORAGE_CAPACITY = "BROKER_STORAGE_CAPACITY";
    String BROKER_ADDRESS_FULL_POLICY = "BROKER_ADDRESS_FULL_POLICY";
    String BROKER_GLOBAL_MAX_SIZE = "BROKER_GLOBAL_MAX_SIZE";
    String ADMIN_MEMORY_LIMIT = "ADMIN_MEMORY_LIMIT";
    String ROUTER_MEMORY_LIMIT = "ROUTER_MEMORY_LIMIT";
    String ROUTER_LINK_CAPACITY = "ROUTER_LINK_CAPACITY";
    String ROUTER_HANDSHAKE_TIMEOUT = "ROUTER_HANDSHAKE_TIMEOUT";
    String ROUTER_IDLE_TIMEOUT = "ROUTER_IDLE_TIMEOUT";
    String ROUTER_WORKER_THREADS = "ROUTER_WORKER_THREADS";
    String ROUTER_ENABLE_VHOST_POLICY = "ROUTER_ENABLE_VHOST_POLICY";
    String ROUTER_VHOST_POLICY_JSON = "ROUTER_VHOST_POLICY_JSON";
    String ROUTER_VHOST_POLICY_NAME = "ROUTER_VHOST_POLICY_NAME";
    String STANDARD_INFRA_CONFIG_NAME = "STANDARD_INFRA_CONFIG_NAME";

    String IMAGE_PULL_POLICY = "IMAGE_PULL_POLICY";
    String ROUTER_IMAGE = "ROUTER_IMAGE";
    String STANDARD_CONTROLLER_IMAGE = "STANDARD_CONTROLLER_IMAGE";
    String AGENT_IMAGE = "AGENT_IMAGE";
    String BROKER_IMAGE = "BROKER_IMAGE";
    String BROKER_PLUGIN_IMAGE = "BROKER_PLUGIN_IMAGE";
    String TOPIC_FORWARDER_IMAGE = "TOPIC_FORWARDER_IMAGE";
    String MQTT_GATEWAY_IMAGE = "MQTT_GATEWAY_IMAGE";
    String MQTT_LWT_IMAGE = "MQTT_LWT_IMAGE";

    String CONSOLE_SSO_COOKIE_SECRET_SECRET_NAME = "CONSOLE_SSO_COOKIE_SECRET_SECRET_NAME";
    String CONSOLE_OAUTH_SECRET_SECRET_NAME = "CONSOLE_OAUTH_SECRET_SECRET_NAME";
    String CONSOLE_OAUTH_DISCOVERY_URL = "CONSOLE_OAUTH_DISCOVERY_URL";
    String CONSOLE_OAUTH_SCOPE = "CONSOLE_OAUTH_SCOPE";
    String CONSOLE_LINK = "CONSOLE_LINK";
}
