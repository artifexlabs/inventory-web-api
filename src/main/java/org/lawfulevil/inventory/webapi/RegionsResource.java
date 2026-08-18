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
package org.lawfulevil.inventory.webapi;

import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.impl.bus.DefaultRegionPromotion;

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Region-id-scoped operations over the bus fabric (asset-scoped ones live on
 * {@link AssetsResource}). {@code make-item} is draw-then-describe step 2: an
 * existing bare box gets its data and becomes an item, transactionally.
 */
@Path("/api/v1/regions")
public class RegionsResource {

  @Inject
  BusClient bus;

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> delete(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.REGIONS_DELETE, id, null),
        v -> Response.noContent().build());
  }

  /** Describe an existing bare box ({name,type,containerId?}) → 201 item. */
  @POST
  @Path("/{id}/make-item")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> makeItem(@PathParam("id") String id, String body) {
    JsonObject j = new JsonObject(body);
    var promotion = new DefaultRegionPromotion(id, j.getString("name"), j.getString("type"),
        j.getString("containerId"));
    return BusResponses.respond(this.bus.request(BusActions.REGIONS_MAKE_ITEM, id, promotion.toJson()),
        item -> Response.status(Response.Status.CREATED).entity(((JsonObject) item).encode()).build());
  }
}
