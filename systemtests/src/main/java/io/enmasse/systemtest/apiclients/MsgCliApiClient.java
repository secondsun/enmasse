/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.apiclients;

import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.Kubernetes;
import io.enmasse.systemtest.TimeoutBudget;
import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;

import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MsgCliApiClient extends ApiClient {


    public MsgCliApiClient(Kubernetes kubernetes, Endpoint endpoint) {
        super(kubernetes, () -> endpoint, "");
    }

    @Override
    protected String apiClientName() {
        return "Docker Messaging Clients";
    }

    @Override
    protected void connect() {
        this.client = WebClient.create(vertx, new WebClientOptions()
                .setSsl(false)
                .setTrustAll(true)
                .setVerifyHost(false));
    }

    /**
     * Start new messaging client(s)
     *
     * @param clientArguments list of arguments for client (together with client name!)
     * @param count           count of clients that will be started
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public JsonObject startClients(List<String> clientArguments, int count) throws InterruptedException, ExecutionException, TimeoutException {
        return startClients(clientArguments, count, new TimeoutBudget(300000, TimeUnit.MILLISECONDS));
    }

    private JsonObject startClients(List<String> clientArguments, int count, TimeoutBudget timeout) throws InterruptedException, ExecutionException, TimeoutException {
        JsonObject request = new JsonObject();
        request.put("command", new JsonArray(clientArguments));
        request.put("count", count);

        do {
            CompletableFuture<JsonObject> responsePromise = new CompletableFuture<>();
            client.post(endpoint.getPort(), endpoint.getHost(), "")
            .as(BodyCodec.jsonObject())
            .timeout(120_000)
            .sendJson(request,
                    ar -> responseHandler(ar, responsePromise, HttpURLConnection.HTTP_OK, "Error starting messaging clients"));

            try {
                return responsePromise.get(150_000, TimeUnit.SECONDS);
            } catch (ExecutionException ee) {
                if (ee.getCause() != null && ee.getCause() instanceof VertxException && "Connection was closed".equalsIgnoreCase(ee.getCause().getMessage())) {
                    log.warn("Failed to get response from {}", endpoint, ee);
                } else {
                    throw ee;
                }
            }
            log.info("retrying...");
        } while (!timeout.timeoutExpired());

        throw new IllegalStateException(String.format("Timed out trying to start clients"));
    }

    /**
     * Get all info about messaging client (stdOut, stdErr, code, isRunning)
     *
     * @param uuid client id
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public JsonObject getClientInfo(String uuid) throws InterruptedException, ExecutionException, TimeoutException {
        return getClientInfo(uuid, new TimeoutBudget(300000, TimeUnit.MILLISECONDS));
    }

    private JsonObject getClientInfo(String uuid, TimeoutBudget timeout) throws InterruptedException, ExecutionException, TimeoutException {
        JsonObject request = new JsonObject();
        request.put("id", uuid);

        do {
            CompletableFuture<JsonObject> responsePromise = new CompletableFuture<>();
            client.get(endpoint.getPort(), endpoint.getHost(), "")
                    .as(BodyCodec.jsonObject())
                    .timeout(120000)
                    .sendJson(request,
                            ar -> responseHandler(ar, responsePromise, HttpURLConnection.HTTP_OK, String.format("Error getting messaging clients info for %s", uuid)));
            try {
                JsonObject info = responsePromise.get(120000, TimeUnit.MILLISECONDS);
                Boolean isRunning = info.getBoolean("isRunning");
                if(isRunning != null && isRunning) {
                    if(timeout.timeoutExpired()) {
                        log.info(info.toString());
                        throw new IllegalStateException("Timeout expired waiting for client isRunning=false");
                    }
                    log.info("retrying getClientInfo because client is still running");
                } else {
                    return info;
                }
            } catch (ExecutionException ee) {
                if (ee.getCause() != null && ee.getCause() instanceof VertxException && "Connection was closed".equalsIgnoreCase(ee.getCause().getMessage())) {
                    log.warn("Failed to get response from {}", endpoint, ee);
                } else {
                    throw ee;
                }
            }
            log.info("retrying...");
        } while (!timeout.timeoutExpired());

        throw new IllegalStateException(String.format("Timed out getting messaging clients info for %s", uuid));
    }

    /**
     * Stop messaging client and get all informations about them (stdOut, stdErr, code, isRunning)
     *
     * @param uuid client id
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public JsonObject stopClient(String uuid) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<JsonObject> responsePromise = new CompletableFuture<>();
        JsonObject request = new JsonObject();
        request.put("id", uuid);

        client.delete(endpoint.getPort(), endpoint.getHost(), "")
                .as(BodyCodec.jsonObject())
                .timeout(120000)
                .sendJson(request,
                        ar -> responseHandler(ar, responsePromise, HttpURLConnection.HTTP_OK, "Error removing messaging clients"));
        return responsePromise.get(150000, TimeUnit.SECONDS);
    }

    /***
     * Send request with one client and receive result
     * @param client
     * @return result of client
     */
    public JsonObject sendAndGetStatus(AbstractClient client) throws InterruptedException, ExecutionException, TimeoutException {
        List<String> apiArgument = new LinkedList<>();
        apiArgument.addAll(client.getExecutable());
        apiArgument.addAll(client.getArguments());

        JsonObject response = startClients(apiArgument, 1);
        log.info(response.toString());
        JsonArray ids = response.getJsonArray("clients");
        String uuid = ids.getString(0);

        Thread.sleep(10000);

        response = getClientInfo(uuid);
        log.info(response.toString());
        return response;
    }

    /***
     * Send request with one client and receive id
     * @param client
     * @return id of client
     */
    public String sendAndGetId(AbstractClient client) throws Exception {
        List<String> apiArgument = new LinkedList<>();
        apiArgument.addAll(client.getExecutable());
        apiArgument.addAll(client.getArguments());

        JsonObject response = startClients(apiArgument, 1);
        log.info(response.toString());

        JsonArray ids = response.getJsonArray("clients");
        return ids.getString(0);
    }
}
