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

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Translates bus outcomes to HTTP. Worker refusals carry HTTP-aligned
 * failure codes (400/401/403/404/409/503) that map one-to-one; transport
 * failures (no handler, timeout) surface as 503 — the fabric is down, not
 * the request wrong.
 */
final class BusResponses {

  private BusResponses() {
  }

  /** Success mapper + error translation in one step. */
  static <T> CompletionStage<Response> respond(CompletionStage<T> stage, Function<T, Response> onSuccess) {
    return stage.thenApply(onSuccess).exceptionally(BusResponses::error);
  }

  static Response error(Throwable failure) {
    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause()
        : failure;
    if (cause instanceof ReplyException reply) {
      if (reply.failureType() == ReplyFailure.RECIPIENT_FAILURE && reply.failureCode() >= 400
          && reply.failureCode() < 600)
        return status(reply.failureCode(), reply.getMessage());
      return status(503, "inventory service unavailable");
    }
    return status(500, String.valueOf(cause.getMessage()));
  }

  private static Response status(int code, String message) {
    return Response.status(code).type(MediaType.APPLICATION_JSON)
        .entity(new JsonObject().put("error", message == null ? "request failed" : message).encode()).build();
  }
}
