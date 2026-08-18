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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.impl.bus.DefaultIdentityClaim;

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Trusted webapp-to-gateway identity exchange for OIDC logins: the webapp has
 * already verified the user's identity with the provider, and presents the
 * shared secret plus the verified claims to obtain an API token. The secret
 * gate is HTTP-tier policy and stays here; the identity/link/provision work
 * is the auth worker's, reached pre-auth over the bus.
 *
 * When the body carries {@code provider} + {@code subject}, identity wins
 * over email (providers like Apple hand out relay addresses, so email is
 * profile data, not the key). Disabled entirely (404) until
 * {@code inventory.oidc.exchange-secret} is configured. Provisioning policy
 * ({@code inventory.oidc.provision}) is enforced by the worker.
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcExchangeResource {
  public final static String SECRET_HEADER = "X-Exchange-Secret";

  @ConfigProperty(name = "inventory.oidc.exchange-secret")
  Optional<String> exchangeSecret;

  @Inject
  BusClient bus;

  @POST
  @Path("/exchange")
  public CompletionStage<Response> exchange(@HeaderParam(SECRET_HEADER) String presentedSecret, String body) {
    if (this.exchangeSecret.isEmpty())
      return CompletableFuture.completedStage(Response.status(Response.Status.NOT_FOUND).build());
    if (presentedSecret == null || !presentedSecret.equals(this.exchangeSecret.get()))
      return CompletableFuture.completedStage(error(Response.Status.UNAUTHORIZED, "bad exchange secret"));
    JsonObject j = new JsonObject(body == null || body.isBlank() ? "{}" : body);
    String email = j.getString("email");
    if (email == null || email.isBlank())
      return CompletableFuture.completedStage(error(Response.Status.BAD_REQUEST, "email is required"));
    var claim = new DefaultIdentityClaim(email, j.getString("displayName"), j.getString("provider"),
        j.getString("subject"));
    return BusResponses.respond(this.bus.anonymous(BusActions.AUTH_EXCHANGE, claim.toJson()),
        granted -> Response.ok(((JsonObject) granted).encode()).build());
  }

  private static Response error(Response.Status status, String message) {
    return Response.status(status).entity(new JsonObject().put("error", message).encode()).build();
  }
}
