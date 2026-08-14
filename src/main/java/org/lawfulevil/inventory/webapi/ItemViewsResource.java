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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.bus.BusActions;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Read models: page-shaped aggregates composed from bus queries, so every
 * client (web UI now, mobile today) gets a page in one HTTP round trip.
 * Shaping only — pagination, filtering, and derived display fields
 * ({@code belowMin}, {@code locationName}); no business decisions are made
 * here. The composition fans out as several envelopes; that is gateway work,
 * not a reason to push view logic into the workers.
 *
 * Lives under {@code /api/v1/views/*} rather than shadowing
 * {@code /api/v1/items}: the flat items surface stays canonical; views are
 * additive conveniences.
 */
@Path("/api/v1/views")
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class ItemViewsResource {

  @Inject
  BusClient bus;

  @GET
  @Path("/items")
  public Response items(@QueryParam("query") String query, @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("25") int size) {
    List<JsonObject> all = objects((JsonArray) join(this.bus.request(BusActions.ITEMS_LIST, null, null)));
    List<JsonObject> matched = all.stream().filter(i -> matches(i, query)).toList();
    int from = Math.max(0, page) * Math.max(1, size);
    List<JsonObject> pageItems = matched.stream().skip(from).limit(Math.max(1, size))
        .map(ItemViewsResource::withBelowMin).toList();
    return Response.ok(new JsonObject().put("items", new JsonArray(pageItems)).put("total", matched.size())
        .put("page", page).put("size", size).put("query", query == null ? "" : query).encode()).build();
  }

  @GET
  @Path("/items/{id}/detail")
  public Response detail(@PathParam("id") String id) {
    final JsonObject item;
    try {
      item = (JsonObject) join(this.bus.request(BusActions.ITEMS_GET, id, null));
    } catch (CompletionException e) {
      return BusResponses.error(e);
    }

    JsonArray children = item.getJsonArray("containedItems", new JsonArray());
    List<JsonObject> containers = objects(
        (JsonArray) join(this.bus.request(BusActions.ITEMS_CONTAINERS_OF, id, null)));
    List<JsonObject> candidates = objects((JsonArray) join(this.bus.request(BusActions.ITEMS_LIST, null, null)))
        .stream().filter(i -> !id.equals(i.getString("id")))
        .map(i -> new JsonObject().put("id", i.getString("id")).put("name", i.getString("name"))).toList();
    List<JsonObject> history = objects((JsonArray) join(
        this.bus.request(BusActions.AUDIT_BY_TARGET, id, new JsonObject().put("limit", 20))));
    List<JsonObject> locations = objects((JsonArray) join(this.bus.request(BusActions.LOCATIONS_LIST, null, null)));
    List<JsonObject> assets = objects((JsonArray) join(this.bus.request(BusActions.ASSETS_LIST_FOR, id, null)));

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

  private static List<JsonObject> objects(JsonArray array) {
    return array.stream().map(JsonObject.class::cast).toList();
  }

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

  /** The views are @Blocking aggregates; joining on a worker thread is fine. */
  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }
}
