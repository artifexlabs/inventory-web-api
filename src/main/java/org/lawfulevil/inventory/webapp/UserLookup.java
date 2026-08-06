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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves a token to its current user via the server's {@code /auth/me},
 * with an optional short TTL cache ({@code inventory.webapp.me-cache-ttl}).
 * The default TTL of 0 disables caching entirely: every page sees the user
 * exactly as the server knows them right now. A non-zero TTL trades that
 * freshness window for one fewer server round trip per page.
 *
 * @author mykel
 *
 */
@ApplicationScoped
public class UserLookup {

  private record Cached(JsonObject user, long expiresAtNanos) {
  }

  private final ServerClient server;
  private final Duration ttl;
  private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

  @Inject
  public UserLookup(ServerClient server,
      @ConfigProperty(name = "inventory.webapp.me-cache-ttl", defaultValue = "0s") Duration ttl) {
    this.server = server;
    this.ttl = ttl;
  }

  /** The current user for this token; throws {@link ServerClient.Unauthorized} when invalid. */
  public JsonObject me(String token) {
    if (this.ttl.isZero() || this.ttl.isNegative())
      return this.server.me(token);
    long now = System.nanoTime();
    Cached c = this.cache.get(token);
    if (c != null && now < c.expiresAtNanos())
      return c.user().copy();
    try {
      JsonObject fresh = this.server.me(token);
      this.cache.put(token, new Cached(fresh.copy(), now + this.ttl.toNanos()));
      return fresh;
    } catch (ServerClient.Unauthorized e) {
      this.cache.remove(token);
      throw e;
    }
  }

  /** Drop a token's cached user immediately (logout, revocation seen elsewhere). */
  public void invalidate(String token) {
    this.cache.remove(token);
  }
}
