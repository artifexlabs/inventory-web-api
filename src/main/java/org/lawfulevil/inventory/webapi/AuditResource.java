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
 * Read the audit trail over the bus fabric. The global feed demands the
 * admin role — enforced by the worker's guard, translated here to 403; the
 * per-target history is available to any authenticated user (it backs the
 * item history view).
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

  @Inject
  BusClient bus;

  @GET
  public CompletionStage<Response> recent(@QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return BusResponses.respond(
        this.bus.request(BusActions.AUDIT_RECENT, null, new JsonObject().put("limit", limit).put("offset", offset)),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }

  @GET
  @Path("/target/{id}")
  public CompletionStage<Response> byTarget(@PathParam("id") String id,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return BusResponses.respond(
        this.bus.request(BusActions.AUDIT_BY_TARGET, id, new JsonObject().put("limit", limit)),
        body -> Response.ok(((JsonArray) body).encode()).build());
  }
}
