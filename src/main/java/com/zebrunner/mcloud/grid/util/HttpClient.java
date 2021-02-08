/*******************************************************************************
 * Copyright 2018-2021 Zebrunner (https://zebrunner.com/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.zebrunner.mcloud.grid.util;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.lang3.StringUtils;
import org.seleniumhq.jetty9.http.HttpMethod;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.zebrunner.mcloud.grid.integration.client.Path;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

public class HttpClient {

	private static Logger LOGGER = Logger.getLogger(HttpClient.class.getName());

    private static final Integer CONNECT_TIMEOUT = 60000;
    private static final Integer READ_TIMEOUT = 60000;
    private static final Integer RETRY_DELAY = 10000;
    private static final Integer MAX_RETRY_COUNT = 3;

    private static Client client;

    static {
        client = Client.create(new DefaultClientConfig(GensonProvider.class));
        client.setConnectTimeout(CONNECT_TIMEOUT);
        client.setReadTimeout(READ_TIMEOUT);
    }

    public static Executor uri(Path path, String serviceUrl, Object... parameters) {
        String url = path.build(serviceUrl, parameters);
        return uri(url, null);
    }

    public static Executor uri(Path path, Map<String, String> queryParameters, String serviceUrl, Object... parameters) {
        String url = path.build(serviceUrl, parameters);
        return uri(url, queryParameters);
    }

    private static Executor uri(String url, Map<String, String> queryParameters) {
        WebResource webResource = client.resource(url);
        if (queryParameters != null) {
            MultivaluedMap<String, String> requestParameters = new MultivaluedMapImpl();
            queryParameters.forEach(requestParameters::add);
            webResource = webResource.queryParams(requestParameters);
        }
        return new Executor(webResource);
    }

    public static class Executor {

        private WebResource webResource;
        private String errorMessage;
        private String url;
        private HttpMethod httpMethod;
        private String mediaType;
        private String acceptType;
        private Map<String, String> headers = new HashMap<>();

        public Executor(WebResource webResource) {
            url = webResource.getURI().toString();
            this.webResource = webResource;
        }

        public WebResource.Builder prepareBuilder() {
            WebResource.Builder builder = webResource.type(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if (mediaType != null) {
                builder.type(mediaType);
            }
            if (acceptType != null) {
                builder.type(acceptType);
            }
            headers.keySet().stream().forEach(k -> builder.header(k, headers.get(k)));
            return builder;
        }

        public <R> Response<R> get(Class<R> responseClass) {
            this.httpMethod = HttpMethod.GET;
            return execute(responseClass, builder -> builder.get(ClientResponse.class));
        }

        public <R> Response<R> post(Class<R> responseClass, Object requestEntity) {
            this.httpMethod = HttpMethod.POST;
            return execute(responseClass, builder -> builder.post(ClientResponse.class, requestEntity));
        }

        public <R> Response<R> put(Class<R> responseClass, Object requestEntity) {
            this.httpMethod = HttpMethod.PUT;
            return execute(responseClass, builder -> builder.put(ClientResponse.class, requestEntity));
        }

        public <R> Response<R> delete(Class<R> responseClass) {
            return execute(responseClass, builder -> builder.delete(ClientResponse.class));
        }

        public Executor type(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        public Executor accept(String acceptType) {
            this.acceptType = acceptType;
            return this;
        }

        public Executor withAuthorization(String authToken) {
            initHeaders(authToken);
            return this;
        }

        private void initHeaders(String authToken) {
            if (!StringUtils.isEmpty(authToken)) {
                headers.put("Authorization", authToken);
            }
        }

        @SuppressWarnings("unchecked")
        private <R> Response<R> execute(Class<R> responseClass, Function<WebResource.Builder, ClientResponse> methodBuilder) {
            RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                    .withDelay(Duration.ofMillis(RETRY_DELAY))
                    .withMaxRetries(MAX_RETRY_COUNT)
                    .handleResultIf(result -> result != null && ((Response<R>) result).getStatus() / 100 != 2)
                    .onRetry(e -> LOGGER.log(
                            Level.SEVERE,
                            String.format("HTTP call '%s' failed with status '%s'. Failure #%d. Retrying. %s", 
                                    getEndpoint(),
                                    ((Response<R>) e.getLastResult()) == null ? "n/a" : ((Response<R>) e.getLastResult()).getStatus(),
                                    e.getAttemptCount(),
                                    errorMessage != null ? errorMessage : ""),
                            e.getLastFailure()))
                    .onRetriesExceeded(e -> LOGGER.log(
                            Level.SEVERE,
                            String.format("HTTP call '%s' failed with status '%s'. Max retries exceeded. %s",
                                    getEndpoint(),
                                    ((Response<R>) e.getResult()) == null ? "n/a" : ((Response<R>) e.getResult()).getStatus(),
                                    errorMessage != null ? errorMessage : ""),
                            e.getFailure()));

            try {
                return Failsafe.with(retryPolicy).get(() -> {
                    Response<R> rs = new Response<>();
                    ClientResponse response = methodBuilder.apply(prepareBuilder());
                    int status = response.getStatus();
                    rs.setStatus(status);
                    if (responseClass != null && !responseClass.isAssignableFrom(Void.class) && status == 200) {
                        rs.setObject(response.getEntity(responseClass));
                    }
                    return rs;
                });
            } catch (Exception e) {
                // do nothing
            }
            return new Response<>();
        }

        public Executor onFailure(String message) {
            this.errorMessage = message;
            return this;
        }

        private String getEndpoint() {
            return String.format("%s %s", httpMethod.toString(), url);
        }

    }

    public static class Response<T> {

        private int status;
        private T object;

        public Response() {
        }

        Response(int status, T object) {
            this.status = status;
            this.object = object;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public T getObject() {
            return object;
        }

        public void setObject(T object) {
            this.object = object;
        }
    }

}
