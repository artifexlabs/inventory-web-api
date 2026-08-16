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

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * External-catalog prefill for scanned barcodes: what the configured
 * catalogs know about a GTIN, so a create form can start filled in. 400 for
 * a bad check digit, 404 when no catalog knows the code, 503 when lookups
 * are disabled ({@code inventory.catalog=off}).
 */
@Path("/api/v1/catalog")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

  @Inject
  BusClient bus;

  @GET
  @Path("/upc/{gtin}")
  public CompletionStage<Response> lookup(@PathParam("gtin") String gtin) {
    return BusResponses.respond(
        this.bus.request(BusActions.CATALOG_UPC, null, new JsonObject().put("gtin", gtin)),
        body -> Response.ok(((JsonObject) body).encode()).build());
  }
}
