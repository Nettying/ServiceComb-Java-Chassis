/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.transport.rest.client.http;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import io.servicecomb.common.rest.RestConst;
import io.servicecomb.common.rest.codec.RestCodec;
import io.servicecomb.common.rest.codec.param.RestClientRequestImpl;
import io.servicecomb.common.rest.definition.RestOperationMeta;
import io.servicecomb.common.rest.filter.HttpClientFilter;
import io.servicecomb.core.Invocation;
import io.servicecomb.core.definition.OperationMeta;
import io.servicecomb.core.transport.AbstractTransport;
import io.servicecomb.foundation.common.net.IpPort;
import io.servicecomb.foundation.common.net.URIEndpointObject;
import io.servicecomb.foundation.common.utils.JsonUtils;
import io.servicecomb.foundation.vertx.client.http.HttpClientWithContext;
import io.servicecomb.serviceregistry.api.Const;
import io.servicecomb.swagger.invocation.AsyncResponse;
import io.servicecomb.swagger.invocation.Response;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;

public abstract class VertxHttpMethod {
  private static final Logger LOGGER = LoggerFactory.getLogger(VertxHttpMethod.class);

  protected List<HttpClientFilter> httpClientFilters = Collections.emptyList();

  public void setHttpClientFilters(List<HttpClientFilter> httpClientFilters) {
    this.httpClientFilters = httpClientFilters;
  }

  public void doMethod(HttpClientWithContext httpClientWithContext, Invocation invocation,
      AsyncResponse asyncResp) throws Exception {
    OperationMeta operationMeta = invocation.getOperationMeta();
    RestOperationMeta swaggerRestOperation = operationMeta.getExtData(RestConst.SWAGGER_REST_OPERATION);

    String path = this.createRequestPath(invocation, swaggerRestOperation);
    IpPort ipPort = (IpPort) invocation.getEndpoint().getAddress();

    HttpClientRequest clientRequest =
        this.createRequest(httpClientWithContext.getHttpClient(),
            invocation,
            ipPort,
            path,
            asyncResp);
    clientRequest.putHeader(io.servicecomb.core.Const.TARGET_MICROSERVICE, invocation.getMicroserviceName());
    RestClientRequestImpl restClientRequest = new RestClientRequestImpl(clientRequest);
    RestCodec.argsToRest(invocation.getArgs(), swaggerRestOperation, restClientRequest);

    Buffer requestBodyBuffer = restClientRequest.getBodyBuffer();
    for (HttpClientFilter filter : httpClientFilters) {
      filter.beforeSendRequest(invocation, clientRequest, requestBodyBuffer);
    }

    clientRequest.exceptionHandler(e -> {
      LOGGER.error(e.toString());
      asyncResp.fail(invocation.getInvocationType(), e);
    });

    LOGGER.debug(
        "Running HTTP method {} on {} at {}:{}{}",
        invocation.getOperationMeta().getMethod(),
        invocation.getMicroserviceName(),
        ipPort.getHostOrIp(),
        ipPort.getPort(),
        path);

    // 从业务线程转移到网络线程中去发送
    httpClientWithContext.runOnContext(httpClient -> {
      this.setCseContext(invocation, clientRequest);
      clientRequest.setTimeout(AbstractTransport.getRequestTimeout());
      try {
        restClientRequest.end();
      } catch (Exception e) {
        LOGGER.error("send http reqeust failed,", e);
        asyncResp.fail(invocation.getInvocationType(), e);
      }
    });
  }

  protected abstract HttpClientRequest createRequest(HttpClient client, Invocation invocation, IpPort ipPort,
      String path,
      AsyncResponse asyncResp);

  protected void handleResponse(Invocation invocation, HttpClientResponse httpResponse,
      AsyncResponse asyncResp) {
    httpResponse.bodyHandler(responseBuf -> {
      // 此时是在网络线程中，不应该就地处理，通过dispatcher转移线程
      invocation.getResponseExecutor().execute(() -> {
        try {
          for (HttpClientFilter filter : httpClientFilters) {
            Response response = filter.afterReceiveResponse(invocation, httpResponse, responseBuf);
            if (response != null) {
              asyncResp.complete(response);
              return;
            }
          }
        } catch (Throwable e) {
          asyncResp.fail(invocation.getInvocationType(), e);
        }
      });
    });
  }

  protected void setCseContext(Invocation invocation, HttpClientRequest request) {
    try {
      String cseContext = JsonUtils.writeValueAsString(invocation.getContext());
      request.putHeader(io.servicecomb.core.Const.CSE_CONTEXT, cseContext);
    } catch (Exception e) {
      LOGGER.debug(e.toString());
    }
  }

  protected String createRequestPath(Invocation invocation,
      RestOperationMeta swaggerRestOperation) throws Exception {
    URIEndpointObject address = (URIEndpointObject) invocation.getEndpoint().getAddress();
    String urlPrefix = address.getFirst(Const.URL_PREFIX);

    String path = (String) invocation.getHandlerContext().get(RestConst.REST_CLIENT_REQUEST_PATH);
    if (path == null) {
      path = swaggerRestOperation.getPathBuilder().createRequestPath(invocation.getArgs());
    }

    if (StringUtils.isEmpty(urlPrefix) || path.startsWith(urlPrefix)) {
      return path;
    }

    return urlPrefix + path;
  }
}
