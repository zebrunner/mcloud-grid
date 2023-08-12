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

import java.io.IOException;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.util.HttpClient.Response;

public class HttpClientApache {

    private static Logger LOGGER = Logger.getLogger(HttpClientApache.class.getName());

    private final static RequestConfig DEFAULT_REQUEST_CFG = RequestConfig.custom()
            .setConnectionRequestTimeout(1000)
            .setConnectTimeout(1000)
            .setSocketTimeout(1000)
            .build();

    private RequestConfig requestConfig = DEFAULT_REQUEST_CFG;

    private String url;

    private HttpClientApache() {
    }

    public static HttpClientApache create() {
        return new HttpClientApache();
    }

    public HttpClientApache withRequestConfig(RequestConfig requestConfig) {
        this.requestConfig = requestConfig;
        return this;
    }

    public HttpClientApache withUri(Path path, String serviceUrl, Object... parameters) {
        this.url = path.build(serviceUrl, parameters);
        return this;
    }

    public Response<String> get() {
        if (url == null) {
            LOGGER.log(Level.WARNING, "url should be specified!");
            return null;
        }
        return execute(new HttpGet(url));
    }

    public static class HttpGetWithEntity extends HttpEntityEnclosingRequestBase {
        public static final String METHOD_NAME = "GET";

        public HttpGetWithEntity(final String uri) {
            super();
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return METHOD_NAME;
        }
    }

    public Response<String> get(HttpEntity entity) {
        if (url == null) {
            LOGGER.log(Level.WARNING, "url should be specified!");
            return null;
        }
        HttpGetWithEntity get = new HttpGetWithEntity(url);
        get.setEntity(entity);
        return execute(get);
    }

    public Response<String> post(HttpEntity entity) {
        if (url == null) {
            LOGGER.log(Level.WARNING, "url should be specified!");
            return null;
        }
        HttpPost post = new HttpPost(url);
        post.setEntity(entity);
        return execute(post);
    }

    public Response<String> put(HttpEntity entity) {
        if (url == null) {
            LOGGER.log(Level.WARNING, "url should be specified!");
            return null;
        }
        HttpPut put = new HttpPut(url);
        put.setEntity(entity);
        return execute(put);
    }

    public Response<String> delete() {
        if (url == null) {
            LOGGER.log(Level.WARNING, "url should be specified!");
            return null;
        }
        HttpDelete delete = new HttpDelete(url);
        return execute(delete);
    }

    private Response<String> execute(HttpUriRequest req) {
        Response<String> result = new Response<String>();
        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .build();
                CloseableHttpResponse response = httpClient.execute(req)) {
            result.setStatus(response.getStatusLine().getStatusCode());
            result.setObject(EntityUtils.toString(response.getEntity()));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        return result;
    }

}
