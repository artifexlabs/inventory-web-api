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
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.AssetStore;
import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.AuditEventFactory;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.Item;
import org.lawfulevil.inventory.api.ItemFactory;
import org.lawfulevil.inventory.api.Location;
import org.lawfulevil.inventory.api.LocationFactory;
import org.lawfulevil.inventory.api.LocationSystem;

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
 * Read models: page-shaped aggregates composed directly from the domain beans
 * (this service owns them since the HTTP consolidation), so every client (web
 * UI now, mobile today) gets a page in one round trip. Shaping only —
 * pagination, filtering, and derived display fields ({@code belowMin},
 * {@code locationName}); no business decisions are made here.
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
  InventorySystem inventory;

  @Inject
  LocationSystem locations;

  @Inject
  AuditReader audit;

  @Inject
  AssetStore assets;

  @GET
  @Path("/items")
  public Response items(@QueryParam("query") String query, @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("25") int size) {
    List<JsonObject> all = join(this.inventory.getAllItems()).stream().map(ItemFactory::serialize).toList();
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
    Optional<Item> found = join(this.inventory.getItem(id));
    if (found.isEmpty())
      return Response.status(Response.Status.NOT_FOUND).build();
    JsonObject item = ItemFactory.serialize(found.get());

    JsonArray children = item.getJsonArray("containedItems", new JsonArray());
    List<JsonObject> containers = join(this.inventory.getContainersOf(id)).stream()
        .map(ItemFactory::serialize).toList();
    List<JsonObject> candidates = join(this.inventory.getAllItems()).stream()
        .filter(i -> !id.equals(i.getId()))
        .map(i -> new JsonObject().put("id", i.getId()).put("name", i.getName())).toList();
    List<JsonObject> history = join(this.audit.byTarget(id, 20)).stream()
        .map(AuditEventFactory::serialize).toList();
    List<Location> allLocations = join(this.locations.getAllLocations());
    List<JsonObject> locationJson = allLocations.stream().map(LocationFactory::serialize).toList();
    List<JsonObject> assetJson = join(this.assets.listFor(id)).stream().map(a -> a.toJson()).toList();

    String locationName = item.getString("locationId") == null ? null
        : allLocations.stream().filter(l -> item.getString("locationId").equals(l.getId()))
            .map(Location::getName).findFirst().orElse(null);

    JsonObject detail = new JsonObject().put("item", item).put("children", children)
        .put("containers", new JsonArray(containers)).put("candidates", new JsonArray(candidates))
        .put("history", new JsonArray(history)).put("locations", new JsonArray(locationJson))
        .put("assets", new JsonArray(assetJson));
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

  /** The views are @Blocking aggregates; joining on a worker thread is fine. */
  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }
}
