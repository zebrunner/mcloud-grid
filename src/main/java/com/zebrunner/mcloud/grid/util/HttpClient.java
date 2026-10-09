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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.integration.client.Path;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON HTTP client of the STF API.
 */
public final class HttpClient {
    private static final Logger LOGGER = Logger.getLogger(HttpClient.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final CloseableHttpClient CLIENT = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(3000)
                    .setConnectionRequestTimeout(3000)
                    .setSocketTimeout(3000)
                    .build())
            .setConnectionManager(connectionManager())
            .evictIdleConnections(30, TimeUnit.SECONDS)
            // no retries: a slow STF must not block new session requests longer than the timeouts above
            .disableAutomaticRetries()
            .build();

    private static PoolingHttpClientConnectionManager connectionManager() {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        // STF calls are made for every device of the grid
        manager.setDefaultMaxPerRoute(50);
        manager.setMaxTotal(100);
        // keep-alive connections closed by STF or a proxy in front of it are detected before every reuse (~1ms)
        manager.setValidateAfterInactivity(1);
        return manager;
    }

    private HttpClient() {
        //hide
    }

    public static Executor uri(Path path, String serviceUrl, Object... parameters) {
        return new Executor(path.build(serviceUrl, parameters));
    }

    public static final class Executor {
        private final String url;
        private String authorization;

        private Executor(String url) {
            this.url = url;
        }

        public Executor withAuthorization(String authToken) {
            this.authorization = authToken;
            return this;
        }

        public <R> Response<R> get(Class<R> responseClass) {
            return execute(responseClass, HttpGet::new, null);
        }

        public <R> Response<R> post(Class<R> responseClass, Object requestEntity) {
            return execute(responseClass, HttpPost::new, requestEntity);
        }

        public <R> Response<R> put(Class<R> responseClass, Object requestEntity) {
            return execute(responseClass, HttpPut::new, requestEntity);
        }

        public <R> Response<R> delete(Class<R> responseClass) {
            return execute(responseClass, HttpDelete::new, null);
        }

        private <R> Response<R> execute(Class<R> responseClass, Function<String, HttpRequestBase> method, Object requestEntity) {
            Response<R> rs = new Response<>();
            try {
                HttpRequestBase request = method.apply(url);
                request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
                if (StringUtils.isNotEmpty(authorization)) {
                    request.setHeader(HttpHeaders.AUTHORIZATION, authorization);
                }
                if (request instanceof HttpEntityEnclosingRequestBase) {
                    String body = requestEntity == null ? "" : MAPPER.writeValueAsString(requestEntity);
                    ((HttpEntityEnclosingRequestBase) request).setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
                }
                try (CloseableHttpResponse response = CLIENT.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    rs.setStatus(status);
                    String body = response.getEntity() == null ? null : EntityUtils.toString(response.getEntity());
                    if (responseClass != null && !responseClass.isAssignableFrom(Void.class) && status == 200 && StringUtils.isNotBlank(body)) {
                        rs.setObject(MAPPER.readValue(body, responseClass));
                    }
                }
            } catch (Exception e) {
                // status 0 tells the caller the request failed; the stack trace is useful only for debugging
                LOGGER.warning(() -> String.format("STF request %s %s failed: %s: %s", method.apply(url).getMethod(), url,
                        e.getClass().getSimpleName(), e.getMessage()));
                LOGGER.log(Level.FINE, "STF request failure", e);
            }
            return rs;
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

        @Override
        public String toString() {
            return "Response [status=" + status + ", object=" + object + "]";
        }

    }

}
