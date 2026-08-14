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

import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.impl.bus.DefaultRegionBox;
import org.lawfulevil.inventory.impl.bus.DefaultRegionItemCreation;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/**
 * Asset download and deletion by asset id, over the bus fabric. Item-scoped
 * upload/list live on {@link ItemsResource} (JAX-RS resolves the resource
 * class by longest class-level path, so /items/... paths must live under
 * that class).
 */
@Path("/api/v1/assets")
public class AssetsResource {
  public final static String FILENAME_HEADER = "X-Filename";

  @Inject
  BusClient bus;

  @GET
  @Path("/{id}")
  public CompletionStage<Response> download(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_GET, id, null), body -> {
      JsonObject stored = (JsonObject) body;
      JsonObject info = stored.getJsonObject("info");
      return Response.ok(stored.getBinary("bytes"), info.getString("contentType"))
          .header(FILENAME_HEADER, info.getString("filename")).build();
    });
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> delete(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.ASSETS_DELETE, id, null),
        v -> Response.noContent().build());
  }

  // --- spatial annotation (Phase 8): boxes drawn on a picture asset --------

  @GET
  @Path("/{id}/regions")
  @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  public CompletionStage<Response> listRegions(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.REGIONS_LIST, id, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  /** Draw-then-describe step 1: persist a bare box ({x,y,w,h,label?}). */
  @jakarta.ws.rs.POST
  @Path("/{id}/regions")
  @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  public CompletionStage<Response> createRegion(@PathParam("id") String id, String body) {
    JsonObject j = new JsonObject(body);
    var box = new DefaultRegionBox(id, j.getDouble("x"), j.getDouble("y"), j.getDouble("w"), j.getDouble("h"),
        j.getString("label"));
    return BusResponses.respond(this.bus.request(BusActions.REGIONS_CREATE, id, box.toJson()),
        created -> Response.status(Response.Status.CREATED).entity(((JsonObject) created).encode()).build());
  }

  /** One-shot: box + item + containment in a single transaction. */
  @jakarta.ws.rs.POST
  @Path("/{id}/regions/make-item")
  @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  public CompletionStage<Response> createItemFromRegion(@PathParam("id") String id, String body) {
    JsonObject j = new JsonObject(body);
    var creation = new DefaultRegionItemCreation(
        new DefaultRegionBox(id, j.getDouble("x"), j.getDouble("y"), j.getDouble("w"), j.getDouble("h"), null),
        j.getString("name"), j.getString("type"), j.getString("containerId"));
    return BusResponses.respond(this.bus.request(BusActions.REGIONS_CREATE_ITEM, id, creation.toJson()),
        item -> Response.status(Response.Status.CREATED).entity(((JsonObject) item).encode()).build());
  }
}
