/*
 * @formatter:off
 * Copyright © 2019 admin (admin@infrastructurebuilder.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package org.lawfulevil.inventory.webapi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The browser-facing API tier: a transparent pass-through of the whole
 * {@code /api/v1/*} surface to inventory-server. Clients (the inventory-webapp
 * UI today, mobile apps later) authenticate with the same bearer tokens
 * inventory-server issues; this tier adds no session state and serves no HTML.
 *
 * Forwarded per request: method, path, query string, body, and the headers
 * that carry meaning across the surface (Authorization, Content-Type,
 * X-Exchange-Secret for the OIDC exchange, X-Filename for asset upload).
 * Returned per response: status, body, Content-Type, and X-Filename.
 */
@Path("/api/v1/{path: .*}")
@Blocking
public class ApiProxyResource {
  private static final List<String> FORWARDED_REQUEST_HEADERS = List.of(
      "Authorization", "Content-Type", "X-Exchange-Secret", "X-Filename");
  private static final List<String> RETURNED_RESPONSE_HEADERS = List.of("Content-Type", "X-Filename");

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;

  public ApiProxyResource(
      @ConfigProperty(name = "inventory.server.url", defaultValue = "http://localhost:8080") String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @GET
  public Response get(@PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
    return forward("GET", path, uri, headers, null);
  }

  @POST
  public Response post(@PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers,
      byte[] body) {
    return forward("POST", path, uri, headers, body);
  }

  @PUT
  public Response put(@PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers,
      byte[] body) {
    return forward("PUT", path, uri, headers, body);
  }

  @DELETE
  public Response delete(@PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
    return forward("DELETE", path, uri, headers, null);
  }

  private Response forward(String method, String path, UriInfo uri, HttpHeaders headers, byte[] body) {
    String query = uri.getRequestUri().getRawQuery();
    URI target = URI.create(this.baseUrl + "/api/v1/" + path + (query == null ? "" : "?" + query));
    HttpRequest.Builder request = HttpRequest.newBuilder(target).method(method,
        body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    for (String name : FORWARDED_REQUEST_HEADERS)
      Optional.ofNullable(headers.getHeaderString(name)).ifPresent(v -> request.header(name, v));
    try {
      HttpResponse<byte[]> r = this.http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      Response.ResponseBuilder response = Response.status(r.statusCode());
      if (r.body() != null && r.body().length > 0 && r.statusCode() != 204 && r.statusCode() != 304)
        response.entity(r.body());
      for (String name : RETURNED_RESPONSE_HEADERS)
        r.headers().firstValue(name).ifPresent(v -> response.header(name, v));
      return response.build();
    } catch (IOException e) {
      return Response.status(Response.Status.BAD_GATEWAY)
          .entity("inventory-server unreachable at " + this.baseUrl + ": " + e).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Response.status(Response.Status.BAD_GATEWAY)
          .entity("interrupted calling inventory-server").build();
    }
  }
}
