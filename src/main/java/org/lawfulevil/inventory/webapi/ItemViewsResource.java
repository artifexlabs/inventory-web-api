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
import java.util.Locale;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Read models: page-shaped aggregates composed from the same inventory-server
 * calls the proxy forwards, so every client (web UI now, mobile later) gets
 * them in one round trip. Shaping only — pagination, filtering, and derived
 * display fields ({@code belowMin}, {@code locationName}); no business
 * decisions are made here.
 *
 * Lives under {@code /api/v1/views/*} rather than shadowing
 * {@code /api/v1/items}: JAX-RS root-resource matching does not backtrack, so
 * prefix-shadowing the items surface would require re-declaring every proxied
 * method on it.
 */
@Path("/api/v1/views")
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class ItemViewsResource {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;

  public ItemViewsResource(
      @ConfigProperty(name = "inventory.server.url", defaultValue = "http://localhost:8080") String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @GET
  @Path("/items")
  public Response items(@HeaderParam("Authorization") String auth, @QueryParam("query") String query,
      @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("25") int size) {
    List<JsonObject> all = array(required(get("/api/v1/items", auth)));
    List<JsonObject> matched = all.stream().filter(i -> matches(i, query)).toList();
    int from = Math.max(0, page) * Math.max(1, size);
    List<JsonObject> pageItems = matched.stream().skip(from).limit(Math.max(1, size))
        .map(ItemViewsResource::withBelowMin).toList();
    return Response.ok(new JsonObject().put("items", new JsonArray(pageItems)).put("total", matched.size())
        .put("page", page).put("size", size).put("query", query == null ? "" : query).encode()).build();
  }

  @GET
  @Path("/items/{id}/detail")
  public Response detail(@HeaderParam("Authorization") String auth, @PathParam("id") String id) {
    HttpResponse<String> itemResponse = get("/api/v1/items/" + id, auth);
    if (itemResponse.statusCode() == 404)
      return Response.status(Response.Status.NOT_FOUND).build();
    JsonObject item = new JsonObject(required(itemResponse).body());

    JsonArray children = item.getJsonArray("containedItems", new JsonArray());
    List<JsonObject> containers = array(get("/api/v1/items/" + id + "/containers", auth));
    List<JsonObject> candidates = array(required(get("/api/v1/items", auth))).stream()
        .filter(i -> !id.equals(i.getString("id")))
        .map(i -> new JsonObject().put("id", i.getString("id")).put("name", i.getString("name"))).toList();
    List<JsonObject> history = array(get("/api/v1/audit/target/" + id + "?limit=20", auth));
    List<JsonObject> locations = array(get("/api/v1/locations", auth));
    List<JsonObject> assets = array(get("/api/v1/items/" + id + "/assets", auth));

    String locationName = item.getString("locationId") == null ? null
        : locations.stream().filter(l -> item.getString("locationId").equals(l.getString("id")))
            .map(l -> l.getString("name")).findFirst().orElse(null);

    JsonObject detail = new JsonObject().put("item", item).put("children", children)
        .put("containers", new JsonArray(containers)).put("candidates", new JsonArray(candidates))
        .put("history", new JsonArray(history)).put("locations", new JsonArray(locations))
        .put("assets", new JsonArray(assets));
    if (locationName != null)
      detail.put("locationName", locationName);
    return Response.ok(detail.encode()).build();
  }

  // --- shaping helpers --------------------------------------------------

  private static boolean matches(JsonObject item, String query) {
    if (query == null || query.isBlank())
      return true;
    String q = query.toLowerCase(Locale.ROOT);
    return contains(item.getString("name"), q) || contains(item.getString("displayName"), q)
        || contains(item.getString("type"), q);
  }

  private static boolean contains(String value, String lowercaseQuery) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(lowercaseQuery);
  }

  private static JsonObject withBelowMin(JsonObject item) {
    JsonObject copy = item.copy();
    Object quantity = copy.getValue("quantity");
    JsonObject par = copy.getJsonObject("parValues");
    if (quantity instanceof Number q && par != null && par.getValue("minOnHand") instanceof Number min
        && q.longValue() < min.longValue())
      copy.put("belowMin", true);
    return copy;
  }

  // --- backend plumbing -------------------------------------------------

  private HttpResponse<String> get(String path, String auth) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path));
    if (auth != null)
      request.header("Authorization", auth);
    try {
      HttpResponse<String> r = this.http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() == 401)
        throw new WebApplicationException(Response.Status.UNAUTHORIZED);
      return r;
    } catch (IOException e) {
      throw new WebApplicationException("inventory-server unreachable at " + this.baseUrl,
          Response.Status.BAD_GATEWAY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WebApplicationException("interrupted calling inventory-server", Response.Status.BAD_GATEWAY);
    }
  }

  /** For calls the aggregate cannot do without: any non-200 becomes 502. */
  private static HttpResponse<String> required(HttpResponse<String> r) {
    if (r.statusCode() != 200)
      throw new WebApplicationException("inventory-server returned " + r.statusCode(),
          Response.Status.BAD_GATEWAY);
    return r;
  }

  /** Optional sections degrade to empty lists instead of failing the page. */
  private static List<JsonObject> array(HttpResponse<String> r) {
    if (r.statusCode() != 200)
      return List.of();
    return new JsonArray(r.body()).stream().map(o -> (JsonObject) o).toList();
  }
}
