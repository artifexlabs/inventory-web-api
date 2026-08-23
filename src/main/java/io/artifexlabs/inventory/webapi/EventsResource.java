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

import io.artifexlabs.inventory.api.InventoryUser;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * The live status channel a frontend keeps open (PLAN.md Phase 21, ask 3): trouble reaches the user through the UI
 * instead of a log file nobody reads.
 *
 * <p>
 * Server-Sent Events rather than a bus bridge on purpose — the event bus stays sealed behind the gateway, and the
 * gateway's existing bearer-token authentication is what scopes the fan-out. Each SSE message carries the StatusEvent
 * JSON (machine face) whose {@code message}/{@code detail} are the human face; the SSE event NAME is the severity, so a
 * client can bind handlers per severity without parsing.
 */
@Path("/api/v1/events")
public class EventsResource {

  @Inject
  CurrentUser current;

  @Inject
  StatusStreamBroadcaster broadcaster;

  /**
   * Attach to the status stream. A reconnecting client sends the standard {@code Last-Event-ID} header and receives
   * whatever it missed that is still within the replay ring. {@code all=true} is honored for admins only — everyone
   * else receives their own events either way.
   */
  @GET
  @Path("/stream")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public void stream(@Context SseEventSink sink, @Context Sse sse, @HeaderParam("Last-Event-ID") String lastEventId,
      @QueryParam("all") boolean all) {
    InventoryUser user = this.current.get().orElseThrow(
        () -> new jakarta.ws.rs.WebApplicationException("authentication required", Response.Status.UNAUTHORIZED));
    this.broadcaster.register(sink, sse, user.getId(), user.isAdmin(), all, lastEventId);
  }
}
