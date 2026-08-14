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

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.LatLong;
import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.impl.bus.DefaultLocationCreation;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * First-class locations, served over the bus fabric. Deletion answers 409
 * while items still reference the location, mirroring the worker's refusal.
 */
@Path("/api/v1/locations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocationsResource {

  @Inject
  BusClient bus;

  @GET
  public CompletionStage<Response> getAllLocations() {
    return BusResponses.respond(this.bus.request(BusActions.LOCATIONS_LIST, null, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @GET
  @Path("/{id}")
  public CompletionStage<Response> getLocation(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.LOCATIONS_GET, id, null),
        body -> Response.ok(((JsonObject) body).encode()).build());
  }

  @POST
  public CompletionStage<Response> createLocation(String body) {
    JsonObject j = new JsonObject(body);
    Optional<LatLong> coords = j.containsKey("latitude") && j.containsKey("longitude")
        ? Optional.of(new LatLong(j.getDouble("latitude"), j.getDouble("longitude")))
        : Optional.empty();
    var creation = new DefaultLocationCreation(j.getString("name"), coords);
    return BusResponses.respond(this.bus.request(BusActions.LOCATIONS_CREATE, null, creation.toJson()),
        created -> Response.status(Response.Status.CREATED).entity(((JsonObject) created).encode()).build());
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> deleteLocation(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.LOCATIONS_DELETE, id, null),
        v -> Response.noContent().build());
  }
}
