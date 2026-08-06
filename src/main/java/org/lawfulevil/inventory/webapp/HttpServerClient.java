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
package org.lawfulevil.inventory.webapp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.DefaultBean;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * {@link ServerClient} over plain {@code java.net.http} against
 * {@code inventory.server.url}.
 *
 * @author mykel
 *
 */
@ApplicationScoped
@DefaultBean
public class HttpServerClient implements ServerClient {
  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final Optional<String> exchangeSecret;

  public HttpServerClient(
      @ConfigProperty(name = "inventory.server.url", defaultValue = "http://localhost:8080") String baseUrl,
      @ConfigProperty(name = "inventory.oidc.exchange-secret") Optional<String> exchangeSecret) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.exchangeSecret = exchangeSecret;
  }

  @Override
  public Optional<Login> login(String email, String password) {
    HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(this.baseUrl + "/api/v1/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers
            .ofString(new JsonObject().put("email", email).put("password", password).encode()))
        .build());
    if (r.statusCode() != 200)
      return Optional.empty();
    JsonObject j = new JsonObject(r.body());
    return Optional.of(new Login(j.getString("token"), j.getJsonObject("user")));
  }

  @Override
  public Optional<Login> exchange(String email, String displayName) {
    HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(this.baseUrl + "/api/v1/auth/exchange"))
        .header("Content-Type", "application/json").header("X-Exchange-Secret", this.exchangeSecret.orElse(""))
        .POST(HttpRequest.BodyPublishers
            .ofString(new JsonObject().put("email", email).put("displayName", displayName).encode()))
        .build());
    if (r.statusCode() != 200)
      return Optional.empty();
    JsonObject j = new JsonObject(r.body());
    return Optional.of(new Login(j.getString("token"), j.getJsonObject("user")));
  }

  @Override
  public void logout(String token) {
    send(authed(token, "/api/v1/auth/logout").POST(HttpRequest.BodyPublishers.noBody()).build());
  }

  @Override
  public List<JsonObject> items(String token) {
    return toList(checked(send(authed(token, "/api/v1/items").GET().build())));
  }

  @Override
  public Optional<JsonObject> item(String token, String id) {
    HttpResponse<String> r = checked(send(authed(token, "/api/v1/items/" + id).GET().build()));
    return r.statusCode() == 200 ? Optional.of(new JsonObject(r.body())) : Optional.empty();
  }

  @Override
  public List<JsonObject> containersOf(String token, String id) {
    return toList(checked(send(authed(token, "/api/v1/items/" + id + "/containers").GET().build())));
  }

  @Override
  public boolean addToContainer(String token, String containerId, String itemId) {
    return checked(send(authed(token, "/api/v1/items/" + containerId + "/contained/" + itemId)
        .PUT(HttpRequest.BodyPublishers.noBody()).build())).statusCode() == 204;
  }

  @Override
  public boolean removeFromContainer(String token, String containerId, String itemId) {
    return checked(send(authed(token, "/api/v1/items/" + containerId + "/contained/" + itemId).DELETE().build()))
        .statusCode() == 204;
  }

  @Override
  public boolean moveToContainer(String token, String itemId, String containerId) {
    return checked(send(authed(token, "/api/v1/items/" + itemId + "/move-to/" + containerId)
        .POST(HttpRequest.BodyPublishers.noBody()).build())).statusCode() == 204;
  }

  @Override
  public Optional<JsonObject> createItem(String token, String name, String displayName, String type) {
    HttpResponse<String> r = checked(send(authed(token, "/api/v1/items").POST(HttpRequest.BodyPublishers
        .ofString(new JsonObject().put("name", name).put("displayName", displayName).put("type", type).encode()))
        .build()));
    return r.statusCode() == 201 ? Optional.of(new JsonObject(r.body())) : Optional.empty();
  }

  @Override
  public boolean updateItem(String token, JsonObject item) {
    return checked(send(authed(token, "/api/v1/items/" + item.getString("id"))
        .PUT(HttpRequest.BodyPublishers.ofString(item.encode())).build())).statusCode() == 200;
  }

  @Override
  public boolean deleteItem(String token, String id) {
    return checked(send(authed(token, "/api/v1/items/" + id).DELETE().build())).statusCode() == 204;
  }

  @Override
  public List<JsonObject> users(String token) {
    return toList(checked(send(authed(token, "/api/v1/admin/users").GET().build())));
  }

  @Override
  public Optional<JsonObject> createUser(String token, String email, String displayName, String password,
      boolean admin) {
    HttpResponse<String> r = checked(send(authed(token, "/api/v1/admin/users")
        .POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("email", email)
            .put("displayName", displayName).put("password", password).put("admin", admin).encode()))
        .build()));
    return r.statusCode() == 201 ? Optional.of(new JsonObject(r.body())) : Optional.empty();
  }

  @Override
  public boolean deleteUser(String token, String id) {
    return checked(send(authed(token, "/api/v1/admin/users/" + id).DELETE().build())).statusCode() == 204;
  }

  @Override
  public boolean setAdmin(String token, String id, boolean admin) {
    return checked(send(authed(token, "/api/v1/admin/users/" + id + "/admin")
        .POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("admin", admin).encode())).build()))
        .statusCode() == 200;
  }

  @Override
  public List<JsonObject> tokensFor(String token, String userId) {
    return toList(checked(send(authed(token, "/api/v1/admin/users/" + userId + "/tokens").GET().build())));
  }

  @Override
  public boolean revokeToken(String token, String tokenToRevoke) {
    return checked(send(authed(token, "/api/v1/admin/tokens/" + tokenToRevoke).DELETE().build()))
        .statusCode() == 204;
  }

  @Override
  public List<JsonObject> auditRecent(String token, int limit, int offset) {
    return toList(checked(send(authed(token, "/api/v1/audit?limit=" + limit + "&offset=" + offset).GET().build())));
  }

  @Override
  public List<JsonObject> auditFor(String token, String targetId, int limit) {
    return toList(checked(send(authed(token, "/api/v1/audit/target/" + targetId + "?limit=" + limit).GET().build())));
  }

  private HttpRequest.Builder authed(String token, String path) {
    return HttpRequest.newBuilder(URI.create(this.baseUrl + path)).header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json");
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return this.http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new RuntimeException("inventory-server unreachable at " + this.baseUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted calling inventory-server", e);
    }
  }

  private static HttpResponse<String> checked(HttpResponse<String> r) {
    if (r.statusCode() == 401)
      throw new Unauthorized();
    return r;
  }

  private static List<JsonObject> toList(HttpResponse<String> r) {
    if (r.statusCode() != 200)
      return List.of();
    return new JsonArray(r.body()).stream().map(o -> (JsonObject) o).toList();
  }
}
