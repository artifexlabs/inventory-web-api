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

import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.impl.bus.DefaultAdminChange;
import io.artifexlabs.inventory.impl.bus.DefaultUserCreation;

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
 * Admin-only management over the bus fabric: users, their admin flag, and issued tokens. The admin requirement is the
 * worker guard's role check (403); every mutation's audit entry is written by the worker with the envelope's principal
 * — the acting admin — as the actor. The self-delete refusal (409) keys on the envelope's acting userId.
 */
@Path("/api/v1/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject
  BusClient bus;

  @GET
  @Path("/users")
  public CompletionStage<Response> listUsers() {
    return BusResponses.respond(this.bus.request(BusActions.USERS_LIST, null, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @POST
  @Path("/users")
  public CompletionStage<Response> createUser(String body) {
    JsonObject j = new JsonObject(body);
    // Build the typed payload when the submission is complete; an incomplete
    // one still crosses the bus raw so the worker orders its refusals
    // correctly — role (403) before validation (400).
    JsonObject data;
    try {
      data = new DefaultUserCreation(j.getString("email"), j.getString("displayName"), j.getString("password"),
          Boolean.TRUE.equals(j.getBoolean("admin"))).toJson();
    } catch (IllegalArgumentException incomplete) {
      data = j;
    }
    return BusResponses.respond(this.bus.request(BusActions.USERS_CREATE, null, data),
        user -> Response.status(Response.Status.CREATED).entity(((JsonObject) user).encode()).build());
  }

  @DELETE
  @Path("/users/{id}")
  public CompletionStage<Response> deleteUser(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.USERS_DELETE, id, null), v -> Response.noContent().build());
  }

  @POST
  @Path("/users/{id}/admin")
  public CompletionStage<Response> setAdmin(@PathParam("id") String id, String body) {
    var change = new DefaultAdminChange(id, Boolean.TRUE.equals(new JsonObject(body).getBoolean("admin")));
    return BusResponses.respond(this.bus.request(BusActions.USERS_SET_ADMIN, id, change.toJson()),
        user -> Response.ok(((JsonObject) user).encode()).build());
  }

  @GET
  @Path("/users/{id}/tokens")
  public CompletionStage<Response> listTokens(@PathParam("id") String id) {
    return BusResponses.respond(this.bus.request(BusActions.TOKENS_FOR_USER, id, null),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @DELETE
  @Path("/tokens/{token}")
  public CompletionStage<Response> revokeToken(@PathParam("token") String token) {
    return BusResponses.respond(this.bus.request(BusActions.TOKENS_REVOKE, token, null),
        v -> Response.noContent().build());
  }
}
