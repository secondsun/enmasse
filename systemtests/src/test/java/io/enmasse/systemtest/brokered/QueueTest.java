/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.brokered;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.systemtest.AddressType;
import io.enmasse.systemtest.CustomLogger;
import io.enmasse.systemtest.JmsProvider;
import io.enmasse.systemtest.ability.ITestBaseBrokered;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.TestBaseWithShared;
import io.enmasse.systemtest.resolvers.JmsProviderParameterResolver;
import io.enmasse.systemtest.utils.AddressUtils;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;

import javax.jms.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.enmasse.systemtest.TestTag.nonPR;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JmsProviderParameterResolver.class)
class QueueTest extends TestBaseWithShared implements ITestBaseBrokered {
    private static Logger log = CustomLogger.getLogger();
    private Connection connection;

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @Tag(nonPR)
    void testMessageGroup() throws Exception {
        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "message-group"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("message-group")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        setAddresses(dest);

        AmqpClient client = amqpClientFactory.createQueueClient(sharedAddressSpace);

        int msgsCount = 20;
        int msgCountGroupA = 15;
        int msgCountGroupB = 5;
        List<Message> listOfMessages = new ArrayList<>();
        for (int i = 0; i < msgsCount; i++) {
            Message msg = Message.Factory.create();
            msg.setAddress(dest.getSpec().getAddress());
            msg.setBody(new AmqpValue(dest.getSpec().getAddress()));
            msg.setSubject("subject");
            msg.setGroupId(((i + 1) % 4 != 0) ? "group A" : "group B");
            listOfMessages.add(msg);
        }

        Future<List<Message>> receivedGroupA = client.recvMessages(dest.getSpec().getAddress(), msgCountGroupA);
        Future<List<Message>> receivedGroupB = client.recvMessages(dest.getSpec().getAddress(), msgCountGroupB);
        Thread.sleep(2000);

        Future<Integer> sent = client.sendMessages(dest.getSpec().getAddress(),
                listOfMessages.toArray(new Message[0]));

        assertThat("Wrong count of messages sent", sent.get(1, TimeUnit.MINUTES), is(msgsCount));
        assertAll(
                () -> assertThat("Wrong count of messages received from group A",
                        receivedGroupA.get(1, TimeUnit.MINUTES).size(), is(msgCountGroupA)),
                () -> assertThat("Wrong count of messages received from group B",
                        receivedGroupB.get(1, TimeUnit.MINUTES).size(), is(msgCountGroupB)));

        String assertMessage = "Group id is different";
        for (Message m : receivedGroupA.get()) {
            assertEquals(m.getGroupId(), "group A", assertMessage);
        }

        for (Message m : receivedGroupB.get()) {
            assertEquals(m.getGroupId(), "group B", assertMessage);
        }
    }

    @Test
    @Tag(nonPR)
    @Disabled("issue #2852")
    void testRestApi() throws Exception {
        Address q1 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue1"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue1")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        Address q2 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue2"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue2")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();

        runRestApiTest(sharedAddressSpace, q1, q2);
    }

    @Test
    void testTransactionCommitReject(JmsProvider jmsProvider) throws Exception {
        Address addressQueue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "jms-queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("jmsQueue")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        setAddresses(addressQueue);

        connection = jmsProvider.createConnection(getMessagingRoute(sharedAddressSpace).toString(), defaultCredentials,
                "jmsCliId", addressQueue);
        connection.start();
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Queue testQueue = (Queue) jmsProvider.getDestination(addressQueue.getSpec().getAddress());

        MessageProducer sender = session.createProducer(testQueue);
        MessageConsumer receiver = session.createConsumer(testQueue);
        List<javax.jms.Message> recvd;

        int count = 50;

        List<javax.jms.Message> listMsgs = jmsProvider.generateMessages(session, count);

        //send messages and commit
        jmsProvider.sendMessages(sender, listMsgs);
        session.commit();
        log.info("messages sent commit");

        //receive commit messages
        recvd = jmsProvider.receiveMessages(receiver, count, 1000);
        for (javax.jms.Message message : recvd) {
            assertNotNull(message, "No message received");
        }
        session.commit();
        log.info("messages received commit");

        //send messages rollback
        jmsProvider.sendMessages(sender, listMsgs);
        session.rollback();
        log.info("messages sent rollback");

        //check if queue is empty
        javax.jms.Message received = receiver.receive(1000);
        assertNull(received, "Queue should be empty");
        log.info("queue is empty");

        //send messages
        jmsProvider.sendMessages(sender, listMsgs);
        session.commit();
        log.info("messages sent commit");

        //receive messages rollback
        recvd.clear();
        recvd = jmsProvider.receiveMessages(receiver, count, 1000);
        for (javax.jms.Message message : recvd) {
            assertNotNull(message, "No message received");
        }
        session.rollback();
        log.info("messages received rollback");

        //receive messages
        recvd.clear();
        recvd = jmsProvider.receiveMessages(receiver, count, 1000);
        for (javax.jms.Message message : recvd) {
            assertNotNull(message, "No message received");
        }
        session.commit();
        log.info("messages received commit");

        sender.close();
        receiver.close();
    }

    @Test
    void testLoadMessages(JmsProvider jmsProvider) throws Exception {
        Address addressQueue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "jms-queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("jmsQueue")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        setAddresses(addressQueue);

        connection = jmsProvider.createConnection(getMessagingRoute(sharedAddressSpace).toString(), defaultCredentials,
                "jmsCliId", addressQueue);
        connection.start();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue testQueue = (Queue) jmsProvider.getDestination(addressQueue.getSpec().getAddress());

        MessageProducer sender = session.createProducer(testQueue);
        MessageConsumer receiver = session.createConsumer(testQueue);
        List<javax.jms.Message> recvd;
        List<javax.jms.Message> recvd2;

        int count = 60_895;
        int batch = new java.util.Random().nextInt(count) + 1;

        List<javax.jms.Message> listMsgs = jmsProvider.generateMessages(session, count);

        jmsProvider.sendMessages(sender, listMsgs);
        log.info("{} messages sent", count);

        recvd = jmsProvider.receiveMessages(receiver, batch, 1000);
        assertThat("Wrong count of received messages", recvd.size(), Matchers.is(batch));
        log.info("{} messages received", batch);

        recvd2 = jmsProvider.receiveMessages(receiver, count - batch, 1000);
        assertThat("Wrong count of received messages", recvd2.size(), Matchers.is(count - batch));
        log.info("{} messages received", count - batch);

        jmsProvider.sendMessages(sender, listMsgs);
        log.info("{} messages sent", count);

        recvd = jmsProvider.receiveMessages(receiver, count, 1000);
        assertThat("Wrong count of received messages", recvd.size(), Matchers.is(count));
        log.info("{} messages received", count);

        sender.close();
        receiver.close();
    }

    @Test
    @Disabled("due to issue #1330")
    void testLargeMessages(JmsProvider jmsProvider) throws Exception {
        Address addressQueue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "jms-queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("jmsQueue")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        setAddresses(addressQueue);

        connection = jmsProvider.createConnection(getMessagingRoute(sharedAddressSpace).toString(), defaultCredentials,
                "jmsCliId", addressQueue);
        connection.start();

        sendReceiveLargeMessage(jmsProvider, 90, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 50, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 10, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 1, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 90, addressQueue, 1, DeliveryMode.PERSISTENT);
        sendReceiveLargeMessage(jmsProvider, 50, addressQueue, 1, DeliveryMode.PERSISTENT);
        sendReceiveLargeMessage(jmsProvider, 10, addressQueue, 1, DeliveryMode.PERSISTENT);
        sendReceiveLargeMessage(jmsProvider, 1, addressQueue, 1, DeliveryMode.PERSISTENT);
    }
}
