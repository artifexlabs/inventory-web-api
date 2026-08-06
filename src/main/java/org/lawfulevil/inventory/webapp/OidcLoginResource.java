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
package org.lawfulevil.inventory.webapp;

import java.net.URI;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * Google OIDC entry point. When OIDC is enabled (the {@code oidc} config
 * profile supplies Google credentials and an authenticated-path policy for
 * {@code /oidc/login}), arriving here means Google already verified the user;
 * the verified email is exchanged with inventory-server for an API token and
 * the normal session flow takes over. When OIDC is disabled (the default),
 * this simply bounces to the login page.
 */
@Path("/oidc")
@Blocking
public class OidcLoginResource {

  @ConfigProperty(name = "inventory.oidc.enabled", defaultValue = "false")
  boolean oidcEnabled;

  @Inject
  SecurityIdentity identity;

  @Inject
  ServerClient server;

  @Inject
  SessionStore sessions;

  @GET
  @Path("/login")
  public Response oidcLogin() {
    if (!this.oidcEnabled || this.identity.isAnonymous())
      return Response.seeOther(URI.create("/login")).build();
    String email = claim("email").orElse(this.identity.getPrincipal().getName());
    String displayName = claim("name").orElse(null);
    return this.server.exchange(email, displayName)
        .map(login -> {
          String sessionId = this.sessions.create(login.token(), login.user());
          return Response.seeOther(URI.create("/items"))
              .cookie(new NewCookie.Builder("inv_session").value(sessionId).path("/").httpOnly(true).build())
              .build();
        }).orElseGet(() -> Response.seeOther(URI.create("/login")).build());
  }

  private Optional<String> claim(String name) {
    Object v = this.identity.getAttribute(name);
    if (v == null && this.identity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken jwt)
      v = jwt.getClaim(name);
    return Optional.ofNullable(v).map(Object::toString).filter(s -> !s.isBlank());
  }
}
