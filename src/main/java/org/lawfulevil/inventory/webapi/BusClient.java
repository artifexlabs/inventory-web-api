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

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lawfulevil.inventory.api.InventoryUser;
import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.api.bus.Roles;
import org.lawfulevil.inventory.impl.bus.DefaultBusEnvelope;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The gateway's side of the bus fabric: builds a {@link DefaultBusEnvelope}
 * for the authenticated user of the current request — their id and principal
 * for attribution, their {@link Roles} for the worker's role check, the
 * shared fabric token — and sends it request/reply to the action's service
 * address. The reply body resolves the stage; a worker refusal or failure
 * rejects it with the worker's HTTP-aligned code
 * ({@link BusResponses#error(Throwable)} translates).
 *
 * Envelopes are built on the request thread: the request-scoped
 * {@link CurrentUser} proxy is unreachable from the event-loop threads that
 * deliver replies.
 */
@ApplicationScoped
public class BusClient {

  @ConfigProperty(name = "inventory.bus.token", defaultValue = "dev-bus-token")
  String fabricToken;

  @ConfigProperty(name = "inventory.bus.timeout-ms", defaultValue = "30000")
  long timeoutMs;

  @Inject
  Vertx vertx;

  @Inject
  CurrentUser currentUser;

  /** Send as the authenticated user of the current request. */
  public CompletionStage<Object> request(String action, String targetId, JsonObject data) {
    Optional<InventoryUser> user = this.currentUser.get();
    return send(action, targetId, data, user.map(InventoryUser::getId).orElse(""),
        user.map(InventoryUser::getEmail).orElse("anonymous"),
        user.map(Roles::rolesFor).orElse(Set.of()));
  }

  /** Send with no acting user — the pre-auth {@code auth.*} actions only. */
  public CompletionStage<Object> anonymous(String action, JsonObject data) {
    return send(action, null, data, "", "anonymous", Set.of());
  }

  private CompletionStage<Object> send(String action, String targetId, JsonObject data, String userId,
      String principal, Set<String> roles) {
    JsonObject envelope = new DefaultBusEnvelope(DefaultBusEnvelope.VERSION, this.fabricToken, userId, principal,
        roles, action, Optional.ofNullable(targetId), data == null ? new JsonObject() : data).toJson();
    CompletableFuture<Object> reply = new CompletableFuture<>();
    this.vertx.eventBus().request(BusActions.addressOf(action), envelope,
        new DeliveryOptions().setSendTimeout(this.timeoutMs), ar -> {
          if (ar.succeeded())
            reply.complete(ar.result().body());
          else
            reply.completeExceptionally(ar.cause());
        });
    return reply;
  }
}
