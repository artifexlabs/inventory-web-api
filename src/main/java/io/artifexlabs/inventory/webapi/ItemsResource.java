/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
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
package io.artifexlabs.inventory.webapi;

import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.LatLong;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.impl.bus.DefaultAssetUpload;
import io.artifexlabs.inventory.impl.bus.DefaultContainmentChange;
import io.artifexlabs.inventory.impl.bus.DefaultItemCreation;
import io.artifexlabs.inventory.impl.bus.DefaultItemUpdate;

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

  /** The single container (Phase 15 tree); 404 when the item is a root. */
  @GET
  @Path("/{id}/container")
  public CompletionStage<Response> getContainer(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_CONTAINER_OF, id, null),
        body -> Response.ok(((JsonObject) body).encode()).build());
  }

  /** Own pin, else the nearest pinned ancestor's; 404 when nothing is pinned. */
  @GET
  @Path("/{id}/coordinates")
  public CompletionStage<Response> effectiveCoordinates(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_COORDINATES, id, null),
        body -> Response.ok(((JsonObject) body).encode()).build());
  }

  /** Attach a tag ({key, value?}), replacing any same-key tag. */
  @PUT
  @Path("/{id}/tags")
  public CompletionStage<Response> tag(@PathParam("id") String id, String body) {
    JsonObject j = new JsonObject(body);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_TAG, id, j),
        v -> Response.noContent().build());
  }

  @DELETE
  @Path("/{id}/tags/{key}")
  public CompletionStage<Response> untag(@PathParam("id") String id, @PathParam("key") String key) {
    return BusResponses.respond(
        this.bus.request(BusActions.ITEMS_UNTAG, id, new JsonObject().put("key", key)),
        v -> Response.noContent().build());
  }

  /** Items carrying a matching tag: ?key=...&value=...&mode=exact|glob|regex */
  @GET
  @Path("/by-tag")
  public CompletionStage<Response> findByTag(@jakarta.ws.rs.QueryParam("key") String key,
      @jakarta.ws.rs.QueryParam("value") String value,
      @jakarta.ws.rs.QueryParam("mode") @jakarta.ws.rs.DefaultValue("exact") String mode) {
    JsonObject query = new JsonObject().put("keyPattern", key).put("mode", mode.toUpperCase(java.util.Locale.ROOT));
    if (value != null && !value.isBlank())
      query.put("valuePattern", value);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_FIND_BY_TAG, null, query),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  /** Claim a physical marker ({kind, value}) for an item; 409 when the marker already claims another item. */
  @PUT
  @Path("/{id}/identities")
  public CompletionStage<Response> addIdentity(@PathParam("id") String id, String body) {
    JsonObject j = new JsonObject(body);
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_IDENTITY_ADD, id, j),
        v -> Response.noContent().build());
  }

  @DELETE
  @Path("/{id}/identities/{kind}/{value}")
  public CompletionStage<Response> removeIdentity(@PathParam("id") String id, @PathParam("kind") String kind,
      @PathParam("value") String value) {
    return BusResponses.respond(
        this.bus.request(BusActions.ITEMS_IDENTITY_REMOVE, id,
            new JsonObject().put("kind", kind).put("value", value)),
        v -> Response.noContent().build());
  }

  @GET
  @Path("/{id}/identities")
  public CompletionStage<Response> identitiesOf(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ITEMS_IDENTITIES_OF, id, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  /** The item a scanned marker resolves to: ?kind=upc&value=012345678905 → item or 404. */
  @GET
  @Path("/by-identity")
  public CompletionStage<Response> findByIdentity(@jakarta.ws.rs.QueryParam("kind") String kind,
      @jakarta.ws.rs.QueryParam("value") String value) {
    return BusResponses.respond(
        this.bus.request(BusActions.ITEMS_FIND_BY_IDENTITY, null,
            new JsonObject().put("kind", kind).put("value", value)),
        body -> Response.ok(((JsonObject) body).encode()).build());
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
      @jakarta.ws.rs.QueryParam("lat") Double lat, @jakarta.ws.rs.QueryParam("long") Double lng,
      @jakarta.ws.rs.QueryParam("kind") String kind, byte[] body) {
    String name = filename == null || filename.isBlank() ? "unnamed" : filename;
    String type = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : contentType;
    // explicit client coordinates (a phone's GPS at capture) beat EXIF
    var upload = new DefaultAssetUpload(itemId, name, type, body,
        lat != null && lng != null ? java.util.Optional.of(new LatLong(lat, lng)) : java.util.Optional.empty(),
        kind);
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_STORE, itemId, upload.toJson()),
        info -> Response.status(Response.Status.CREATED).entity(((JsonObject) info).encode()).build());
  }

  /**
   * A picture that IS a thing (ongoing item 2): create a NEW item — typically
   * {@code type=location} — with the uploaded photo attached, one
   * transaction. Coordinates: explicit {@code lat}/{@code long} beat EXIF;
   * either pins the created item itself. 404 when {@code container} names an
   * unknown item.
   */
  @POST
  @Path("/from-photo")
  @Consumes(MediaType.WILDCARD)
  public CompletionStage<Response> createItemFromPhoto(
      @jakarta.ws.rs.QueryParam("name") String name,
      @jakarta.ws.rs.QueryParam("displayName") String displayName,
      @jakarta.ws.rs.QueryParam("type") String type,
      @jakarta.ws.rs.QueryParam("container") String containerId,
      @jakarta.ws.rs.HeaderParam(AssetsResource.FILENAME_HEADER) String filename,
      @jakarta.ws.rs.HeaderParam("Content-Type") String contentType,
      @jakarta.ws.rs.QueryParam("lat") Double lat, @jakarta.ws.rs.QueryParam("long") Double lng,
      @jakarta.ws.rs.QueryParam("kind") String kind, byte[] body) {
    if (name == null || name.isBlank())
      return java.util.concurrent.CompletableFuture.completedStage(Response.status(Response.Status.BAD_REQUEST)
          .entity(new JsonObject().put("error", "name is required").encode()).build());
    String fname = filename == null || filename.isBlank() ? "unnamed" : filename;
    String ctype = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM
        : contentType;
    var req = new io.artifexlabs.inventory.impl.bus.DefaultPhotoItemRequest(name, displayName, type, containerId,
        fname, ctype, body,
        lat != null && lng != null ? java.util.Optional.of(new LatLong(lat, lng)) : java.util.Optional.empty(),
        kind);
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_CREATE_ITEM, null, req.toJson()),
        made -> Response.status(Response.Status.CREATED).entity(((JsonObject) made).encode()).build());
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

  /**
   * A scanned barcode that IS a thing: catalog prefill + item + identity +
   * tags + image asset, one worker-side flow (the catalog is prefill —
   * a miss still creates from the body). Body fields (name, displayName,
   * type, description, weightGrams) override the catalog. 201 {item,
   * asset?}; 400 bad check digit or no name from either side; 404 unknown
   * container; 409 when the code already claims another item.
   */
  @POST
  @Path("/from-upc")
  public CompletionStage<Response> createItemFromUpc(@jakarta.ws.rs.QueryParam("gtin") String gtin,
      @jakarta.ws.rs.QueryParam("container") String containerId, String body) {
    JsonObject data = body == null || body.isBlank() ? new JsonObject() : new JsonObject(body);
    data.put("gtin", gtin);
    if (containerId != null && !containerId.isBlank())
      data.put("container", containerId);
    return BusResponses.respond(this.bus.request(BusActions.CATALOG_CREATE_ITEM, null, data),
        made -> Response.status(Response.Status.CREATED).entity(((JsonObject) made).encode()).build());
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
  public CompletionStage<Response> printLabel(@PathParam("id") String id,
      @jakarta.ws.rs.QueryParam("format") String format) {
    JsonObject data = new JsonObject().put("url", scanUrl(id));
    if (format != null && !format.isBlank())
      data.put("format", format); // named label format; absent = printer default
    // 202, not 204: the printer ACCEPTS the job — TCP 9100 never told us it
    // printed — and the outcome arrives on the status stream (MORE_VERTX)
    return BusResponses.respond(this.bus.request(BusActions.LABELS_PRINT, id, data),
        v -> Response.accepted(((JsonObject) v).encode()).type(MediaType.APPLICATION_JSON).build());
  }
}
