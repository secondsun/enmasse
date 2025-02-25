/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.common.api;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressSpaceBuilder;
import io.enmasse.systemtest.*;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.amqp.UnauthorizedAccessException;
import io.enmasse.systemtest.bases.TestBase;
import io.enmasse.systemtest.cmdclients.KubeCMDClient;
import io.enmasse.systemtest.executor.ExecutionResultData;
import io.enmasse.systemtest.utils.AddressSpaceUtils;
import io.enmasse.systemtest.utils.AddressUtils;
import io.enmasse.systemtest.utils.UserUtils;
import io.enmasse.user.model.v1.DoneableUser;
import io.enmasse.user.model.v1.Operation;
import io.enmasse.user.model.v1.User;
import io.enmasse.user.model.v1.UserAuthorizationBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.enmasse.systemtest.TestTag.isolated;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(isolated)
class UserApiTest extends TestBase {
    private static Logger log = CustomLogger.getLogger();
    private AddressSpace brokered = new AddressSpaceBuilder()
            .withNewMetadata()
            .withName("user-api-brokered")
            .withNamespace(kubernetes.getInfraNamespace())
            .endMetadata()
            .withNewSpec()
            .withType(AddressSpaceType.BROKERED.toString())
            .withPlan(AddressSpacePlans.BROKERED)
            .withNewAuthenticationService()
            .withName("standard-authservice")
            .endAuthenticationService()
            .endSpec()
            .build();
    private AddressSpace standard = new AddressSpaceBuilder()
            .withNewMetadata()
            .withName("user-api-standard")
            .withNamespace(kubernetes.getInfraNamespace())
            .endMetadata()
            .withNewSpec()
            .withType(AddressSpaceType.STANDARD.toString())
            .withPlan(AddressSpacePlans.STANDARD_SMALL)
            .withNewAuthenticationService()
            .withName("standard-authservice")
            .endAuthenticationService()
            .endSpec()
            .build();
    private Map<AddressSpace, User> users = new HashMap<>();

    @BeforeEach
    void deployAddressSpaces() throws Exception {
        if (!AddressSpaceUtils.existAddressSpace(brokered.getMetadata().getNamespace(), brokered.getMetadata().getName())) {
            createAddressSpace(brokered);
        }
        if (!AddressSpaceUtils.existAddressSpace(standard.getMetadata().getNamespace(), standard.getMetadata().getName())) {
            createAddressSpace(standard);
        }
        users.clear();
    }

    @AfterEach
    void cleanUsers() {
        users.forEach((addressSpace, user) -> {
            try {
                removeUser(addressSpace, user);
            } catch (Exception e) {
                log.info("Clean: User not exists {}", user.getSpec().getUsername());
            }
        });
    }

    @BeforeAll
    void beforeAll() {
        setReuseAddressSpace();
    }

    @AfterAll
    void removeAddressSpaces() throws Exception {
        unsetReuseAddressSpace();
        deleteAddressspacesFromList();
    }


    @Test
    void testCreateDeleteUserUsingCRD() throws Exception {
        UserCredentials cred = new UserCredentials("pepanatestovani", "pepaNaTestovani");
        User testUser = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("jenda")
                                .withOperations(Operation.send, Operation.recv).build()))
                .endSpec()
                .done();

        JsonObject userDefinitionPayload = UserUtils.userToJson(brokered.getMetadata().getName(), testUser);
        users.put(brokered, testUser);

        //create user
        assertThat(KubeCMDClient.createCR(kubernetes.getInfraNamespace(), userDefinitionPayload.toString()).getRetCode(), is(true));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(true));

        //patch user
        assertThat(KubeCMDClient.patchCR(User.KIND.toLowerCase(), brokered.getMetadata().getName() + "." + cred.getUsername(), "{\"spec\":{\"authentication\":{\"password\":\"aGVp\"}}}").getRetCode(), is(true));

        //delete user
        assertThat(KubeCMDClient.deleteUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(true));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(false));
    }

    @Test
    void testCreateUserWithWrongPayloadCRD() throws Exception {
        UserCredentials cred = new UserCredentials("pepanatestovani", "pepaNaTestovani");
        User testUser = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("jenda")
                                .withOperations(Operation.send, Operation.recv).build()))
                .endSpec()
                .done();

        JsonObject userDefinitionPayload = UserUtils.userToJson(brokered.getMetadata().getName(), testUser);
        userDefinitionPayload.getJsonObject("spec").getJsonArray("authorization").getJsonObject(0).getJsonArray("operations").add("unknown");
        users.put(brokered, testUser);

        //create user
        ExecutionResultData createUserResponse = KubeCMDClient.createCR(kubernetes.getInfraNamespace(), userDefinitionPayload.toString());
        assertThat(createUserResponse.getRetCode(), is(false));
        assertTrue(createUserResponse.getStdErr().contains("value not one of declared Enum instance names: [send, view, recv, manage]"));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(false));

        User testUser2 = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("jenda")
                                .withOperations(Operation.send, Operation.recv).build()))
                .endSpec()
                .done();

        JsonObject userDefinitionPayload2 = UserUtils.userToJson("", testUser2);

        //create user
        ExecutionResultData createUserResponse2 = KubeCMDClient.createCR(kubernetes.getInfraNamespace(), userDefinitionPayload2.toString());
        assertThat(createUserResponse2.getRetCode(), is(false));
        assertTrue(createUserResponse2.getStdErr().contains(String.format("The name of the object (.%s) is not valid", cred.getUsername())));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(false));
    }

    @Test
    void testUpdateUserPermissionsCRD() throws Exception {
        UserCredentials cred = new UserCredentials("pepanatestovani", "pepaNaTestovani");
        User testUser = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("jenda")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();

        JsonObject userDefinitionPayload = UserUtils.userToJson(brokered.getMetadata().getName(), testUser);
        users.put(brokered, testUser);

        //create user
        assertThat(KubeCMDClient.createCR(kubernetes.getInfraNamespace(), userDefinitionPayload.toString()).getRetCode(), is(true));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(true));

        testUser = new DoneableUser(testUser)
                .editSpec()
                .editFirstAuthorization()
                .removeFromOperations(Operation.send)
                .addToOperations(Operation.recv)
                .endAuthorization()
                .endSpec().done();

        userDefinitionPayload = UserUtils.userToJson(brokered.getMetadata().getName(), testUser);

        //update user
        assertThat(KubeCMDClient.updateCR(kubernetes.getInfraNamespace(), userDefinitionPayload.toString()).getRetCode(), is(true));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(true));
        assertTrue(getUser(brokered, testUser.getSpec().getUsername()).toString().contains(Operation.recv.toString()));


        //delete user
        assertThat(KubeCMDClient.deleteUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(true));
        assertThat(KubeCMDClient.getUser(kubernetes.getInfraNamespace(), brokered.getMetadata().getName(), cred.getUsername()).getRetCode(), is(false));
    }


    @Test
    void testUpdateUserPermissionsUserAPI() throws Exception {
        Address queue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(standard.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(standard, "test-queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("test-queue")
                .withPlan(DestinationPlan.STANDARD_LARGE_QUEUE)
                .endSpec()
                .build();
        setAddresses(queue);

        UserCredentials cred = new UserCredentials("pepa", "pepapw");
        User testUser = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses(queue.getSpec().getAddress())
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();

        users.put(brokered, testUser);
        testUser = createOrUpdateUser(standard, testUser);

        AmqpClient client = amqpClientFactory.createQueueClient(standard);
        client.getConnectOptions().setCredentials(cred);
        assertThat(client.sendMessages(queue.getSpec().getAddress(), Arrays.asList("kuk", "puk")).get(1, TimeUnit.MINUTES), is(2));

        testUser = new DoneableUser(testUser)
                .editSpec()
                .withAuthorization(Collections.singletonList(new UserAuthorizationBuilder()
                        .withAddresses(queue.getSpec().getAddress())
                        .withOperations(Operation.recv).build()))
                .endSpec()
                .done();

        createOrUpdateUser(standard, testUser);
        Throwable exception = assertThrows(ExecutionException.class,
                () -> client.sendMessages(queue.getSpec().getAddress(), Arrays.asList("kuk", "puk")).get(10, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof UnauthorizedAccessException);
        assertThat(client.recvMessages(queue.getSpec().getAddress(), 2).get(1, TimeUnit.MINUTES).size(), is(2));
    }

    @Test
    void testUserWithSimilarNamesAndAlreadyExistingUser() throws Exception {
        UserCredentials cred = new UserCredentials("user2", "user2");
        User testUser = createOrUpdateUser(brokered, cred);

        UserCredentials cred2 = new UserCredentials("user23", "test_user23");
        User testUser2 = createOrUpdateUser(brokered, cred2);

        users.put(brokered, testUser);
        users.put(brokered, testUser2);

        assertThrows(KubernetesClientException.class, () ->
                kubernetes.getUserClient(brokered.getMetadata().getNamespace()).create(getUser(brokered, testUser.getSpec().getUsername())));
    }

    @Test
    void testCreateUserUppercaseUsername() throws Exception {
        UserCredentials cred = new UserCredentials("UserPepinator", "ff^%fh16");
        User testUser = UserUtils.createUserResource(cred)
                .editMetadata()
                .withName(brokered.getMetadata().getName() + "." + "userpepinator")
                .endMetadata()
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send, Operation.recv).build()))
                .endSpec()
                .done();

        assertThrows(KubernetesClientException.class, () -> createOrUpdateUser(brokered, testUser));
    }

    @Test
    void testCreateUsersWithSymbolsInVariousPlaces() throws Exception {
        //valid user is created (response HTTP:201)
        UserCredentials cred = new UserCredentials("normalusername", "password");
        User testUser = UserUtils.createUserResource(cred)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        users.put(brokered, testUser);
        createOrUpdateUser(brokered, testUser);

        //first char of username must be a-z0-9 (other symbols respond with HTTP:400)
        UserCredentials cred2 = new UserCredentials("-hyphensymbolfirst", "password");
        User testUser2 = UserUtils.createUserResource(cred2)
                .editMetadata()
                .withName(brokered.getMetadata().getName() + "." + "userpepinator")
                .endMetadata()
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        assertThrows(KubernetesClientException.class, () -> createOrUpdateUser(brokered, testUser2));

        //hyphen is allowed elsewhere in user name (response HTTP:201)
        UserCredentials cred3 = new UserCredentials("user-pepinator", "password");
        User testUser3 = UserUtils.createUserResource(cred3)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        users.put(brokered, testUser3);
        createOrUpdateUser(brokered, testUser3);

        //underscore is also allowed in user name (response HTTP:201)
        UserCredentials cred4 = new UserCredentials("user_pepinator", "password");
        User testUser4 = UserUtils.createUserResource(cred4)
                .editMetadata()
                .withName(brokered.getMetadata().getName() + "." + "testuser")
                .endMetadata()
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        users.put(brokered, testUser4);
        createOrUpdateUser(brokered, testUser4);

        //last char must also be a-z (other symbols respond with HTTP:400)
        UserCredentials cred5 = new UserCredentials("hyphensymbollast-", "password");
        User testUser5 = UserUtils.createUserResource(cred5)
                .editMetadata()
                .withName(brokered.getMetadata().getName() + "." + "userpepinator")
                .endMetadata()
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        assertThrows(KubernetesClientException.class, () ->
                createOrUpdateUser(brokered, testUser5));

        //username may start/end with 0-9 ()
        UserCredentials cred6 = new UserCredentials("01234usernamehere56789", "password");
        User testUser6 = UserUtils.createUserResource(cred6)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        users.put(brokered, testUser6);
        createOrUpdateUser(brokered, testUser6);

        //Foreign symbols may not be used in username (response HTTP:400)
        UserCredentials cred7 = new UserCredentials("invalid_Ö_username", "password");
        User testUser7 = UserUtils.createUserResource(cred7)
                .editSpec()
                .withAuthorization(Collections.singletonList(
                        new UserAuthorizationBuilder()
                                .withAddresses("*")
                                .withOperations(Operation.send).build()))
                .endSpec()
                .done();
        testUser7.getMetadata().setName("userpepinator");
        assertThrows(KubernetesClientException.class, () ->
                createOrUpdateUser(brokered, testUser5));
    }

    @Test
    void testServiceaccountUser() throws Exception {
        Address queue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(standard.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(standard, "test-queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("test-queue")
                .withPlan(DestinationPlan.STANDARD_LARGE_QUEUE)
                .endSpec()
                .build();
        setAddresses(queue);
        UserCredentials serviceAccount = new UserCredentials("test-service-account", "");
        try {
            createUserServiceaccount(standard, serviceAccount);
            UserCredentials messagingUser = new UserCredentials("@@serviceaccount@@",
                    kubernetes.getServiceaccountToken(serviceAccount.getUsername(), environment.namespace()));
            log.info("username: {}, password: {}", messagingUser.getUsername(), messagingUser.getPassword());

            assertCanConnect(standard, messagingUser, Collections.singletonList(queue));

            //delete user
            assertThat("User deleting failed using oc cmd",
                    KubeCMDClient.deleteUser(kubernetes.getInfraNamespace(), standard.getMetadata().getName(), serviceAccount.getUsername()).getRetCode(), is(true));
            assertThat("User is still present",
                    KubeCMDClient.getUser(kubernetes.getInfraNamespace(), standard.getMetadata().getName(), serviceAccount.getUsername()).getRetCode(), is(false));

            assertCannotConnect(standard, messagingUser, Collections.singletonList(queue));
        } finally {
            kubernetes.deleteServiceAccount("test-service-account", environment.namespace());
        }
    }
}
