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

import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.Item;
import org.lawfulevil.inventory.api.ItemFactory;
import org.lawfulevil.inventory.api.LatLong;
import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.impl.bus.DefaultAssetUpload;
import org.lawfulevil.inventory.impl.bus.DefaultContainmentChange;
import org.lawfulevil.inventory.impl.bus.DefaultItemCreation;
import org.lawfulevil.inventory.impl.bus.DefaultItemUpdate;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CRUD over the inventory — a thin authenticated gateway: every operation
 * becomes an envelope on the bus fabric, answered by inventory-server's
 * workers. The wire format is exactly {@link ItemFactory#serialize(Item)} —
 * the same JSON that travels the event bus, so REST and bus consumers share
 * one contract.
 */
@Path("/api/v1/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ItemsResource {

  @Inject
  BusClient bus;

  @GET
  public CompletionStage<Response> getAllItems() {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_LIST, null, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @GET
  @Path("/type/{type}")
  public CompletionStage<Response> getItemsOfType(@PathParam("type") String type) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_LIST_OF_TYPE, type, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @GET
  @Path("/{id}")
  public CompletionStage<Response> getItem(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_GET, id, null),
        body -> Response.ok(((JsonObject) body).encode()).build());
  }

  @POST
  public CompletionStage<Response> createItem(String body) {
    JsonObject j = new JsonObject(body);
    var creation = new DefaultItemCreation(j.getString("name"), j.getString("displayName"), j.getString("type"));
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_CREATE, null, creation.toJson()),
        created -> Response.status(Response.Status.CREATED).entity(((JsonObject) created).encode()).build());
  }

  @PUT
  @Path("/{id}")
  public CompletionStage<Response> updateItem(@PathParam("id") String id, String body) {
    Item item = ItemFactory.deserialize(new JsonObject(body));
    if (!item.getId().equals(id))
      return java.util.concurrent.CompletableFuture.completedStage(
          Response.status(Response.Status.BAD_REQUEST)
              .entity(new JsonObject().put("error", "body id does not match path id").encode()).build());
    var update = new DefaultItemUpdate(id, item);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_UPDATE, id, update.toJson()),
        updated -> Response.ok(((JsonObject) updated).encode()).build());
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> deleteItem(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_DELETE, id, null),
        v -> Response.noContent().build());
  }

  @GET
  @Path("/{id}/containers")
  public CompletionStage<Response> getContainers(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_CONTAINERS_OF, id, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @PUT
  @Path("/{containerId}/contained/{itemId}")
  public CompletionStage<Response> addToContainer(@PathParam("containerId") String containerId,
      @PathParam("itemId") String itemId) {
    var change = new DefaultContainmentChange(containerId, itemId);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_CONTAIN, null, change.toJson()),
        v -> Response.noContent().build());
  }

  @DELETE
  @Path("/{containerId}/contained/{itemId}")
  public CompletionStage<Response> removeFromContainer(@PathParam("containerId") String containerId,
      @PathParam("itemId") String itemId) {
    var change = new DefaultContainmentChange(containerId, itemId);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_UNCONTAIN, null, change.toJson()),
        v -> Response.noContent().build());
  }

  @POST
  @Path("/{itemId}/move-to/{containerId}")
  public CompletionStage<Response> moveToContainer(@PathParam("itemId") String itemId,
      @PathParam("containerId") String containerId) {
    var change = new DefaultContainmentChange(containerId, itemId);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_MOVE, null, change.toJson()),
        v -> Response.noContent().build());
  }

  @POST
  @Path("/{itemId}/assets")
  @Consumes(MediaType.WILDCARD)
  public CompletionStage<Response> uploadAsset(@PathParam("itemId") String itemId,
      @jakarta.ws.rs.HeaderParam(AssetsResource.FILENAME_HEADER) String filename,
      @jakarta.ws.rs.HeaderParam("Content-Type") String contentType,
      @jakarta.ws.rs.QueryParam("lat") Double lat, @jakarta.ws.rs.QueryParam("long") Double lng, byte[] body) {
    String name = filename == null || filename.isBlank() ? "unnamed" : filename;
    String type = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : contentType;
    // explicit client coordinates (a phone's GPS at capture) beat EXIF
    var upload = new DefaultAssetUpload(itemId, name, type, body,
        lat != null && lng != null ? java.util.Optional.of(new LatLong(lat, lng)) : java.util.Optional.empty());
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_STORE, itemId, upload.toJson()),
        info -> Response.status(Response.Status.CREATED).entity(((JsonObject) info).encode()).build());
  }

  @GET
  @Path("/{itemId}/assets")
  public CompletionStage<Response> listAssets(@PathParam("itemId") String itemId) {
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_LIST_FOR, itemId, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "inventory.qr.base-url",
      defaultValue = "http://localhost:8081")
  String qrBaseUrl;

  /** Public addressing is the gateway's knowledge; the worker renders it. */
  private String scanUrl(String id) {
    return this.qrBaseUrl + "/i/" + id;
  }

  @GET
  @Path("/{id}/qr.png")
  @Produces("image/png")
  public CompletionStage<Response> qr(@PathParam("id") String id) {
    return BusResponses.respond(
        this.bus.request(BusActions.LABELS_QR, id, new JsonObject().put("url", scanUrl(id))),
        body -> Response.ok(((JsonObject) body).getBinary("png"), "image/png").build());
  }

  @POST
  @Path("/{id}/print-label")
  @Consumes(MediaType.WILDCARD)
  public CompletionStage<Response> printLabel(@PathParam("id") String id) {
    return BusResponses.respond(
        this.bus.request(BusActions.LABELS_PRINT, id, new JsonObject().put("url", scanUrl(id))),
        v -> Response.noContent().build());
  }
}
