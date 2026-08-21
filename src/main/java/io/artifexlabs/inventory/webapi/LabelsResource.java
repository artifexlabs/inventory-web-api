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

import io.vertx.core.json.JsonObject;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Printer-level label operations — actions on the printer itself rather than
 * on an item (those live beside the item: {@code /items/{id}/print-label}).
 */
@Path("/api/v1/labels")
@Produces(MediaType.APPLICATION_JSON)
public class LabelsResource {

  @Inject
  BusClient bus;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "inventory.qr.base-url",
      defaultValue = "http://localhost:8081")
  String qrBaseUrl;

  /** Public addressing is the gateway's knowledge; the worker renders it. */
  private String scanUrl(String id) {
    return this.qrBaseUrl + "/i/" + id;
  }

  /**
   * Feed blank tape and cut — the "extend the tape" action that ends a
   * chain-printing run. 202 = accepted (see print-batch); 503 when no
   * printer is listening.
   */
  @POST
  @Path("/feed")
  @Consumes(MediaType.WILDCARD)
  public CompletionStage<Response> feed() {
    // 202: accepted for printing, not confirmed printed (MORE_VERTX)
    return BusResponses.respond(this.bus.request(BusActions.LABELS_FEED, null, null),
        v -> Response.accepted(((JsonObject) v).encode()).type(MediaType.APPLICATION_JSON).build());
  }

  /**
   * Print several labels as ONE printer job (ongoing item 10): on continuous
   * tape they share a single leader instead of wasting ~25 mm per cut. Body:
   * {@code {"itemIds":[...], "format":"large"?, "halfCut":true?}} —
   * halfCut (default true) perforates between labels so the strip tears
   * apart by hand; false takes a full cut between each. 400 on an empty list,
   * 404 when any id is unknown (the whole run is refused rather than
   * printing a partial strip), 503 when no printer is listening.
   * Answers 202: the run was ACCEPTED; the outcome follows on the status
   * stream, because a TCP-9100 printer never reports completion.
   */
  @POST
  @Path("/print-batch")
  @Consumes(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> printBatch(String body) {
    JsonObject in = body == null || body.isBlank() ? new JsonObject() : new JsonObject(body);
    io.vertx.core.json.JsonArray ids = in.getJsonArray("itemIds");
    if (ids == null || ids.isEmpty())
      return java.util.concurrent.CompletableFuture.completedStage(Response
          .status(Response.Status.BAD_REQUEST)
          .entity(new JsonObject().put("error", "itemIds is required and must be non-empty").encode())
          .build());
    // public addressing belongs to this tier, so the scan URLs are resolved
    // here and travel with the envelope
    JsonObject urls = new JsonObject();
    ids.forEach(o -> urls.put(String.valueOf(o), scanUrl(String.valueOf(o))));
    JsonObject data = new JsonObject().put("itemIds", ids).put("urls", urls);
    if (in.getString("format") != null)
      data.put("format", in.getString("format"));
    // half cut between labels unless explicitly disabled
    if (in.getBoolean("halfCut") != null)
      data.put("halfCut", in.getBoolean("halfCut"));
    return BusResponses.respond(this.bus.request(BusActions.LABELS_PRINT_BATCH, null, data),
        made -> Response.accepted(((JsonObject) made).encode()).type(MediaType.APPLICATION_JSON).build());
  }
}
