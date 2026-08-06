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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Server-side sessions mapping an opaque HTTP-only cookie to the user's API
 * token — nothing else. The user itself is looked up fresh from
 * inventory-server on every page load, so admin changes and revocations take
 * effect immediately.
 *
 * Sessions persist to a JSON file so a webapp restart does not log everyone
 * out. Set {@code inventory.webapp.session-file=none} to disable persistence.
 *
 * @author mykel
 *
 */
@ApplicationScoped
public class SessionStore {
  private final static Logger log = LoggerFactory.getLogger(SessionStore.class);

  private final ConcurrentHashMap<String, String> tokensBySession = new ConcurrentHashMap<>();
  private final Path file;

  @Inject
  public SessionStore(@ConfigProperty(name = "inventory.webapp.session-file",
      defaultValue = "${user.home}/.inventory-webapp-sessions.json") String sessionFile) {
    this.file = "none".equals(sessionFile) ? null : Path.of(sessionFile);
  }

  @PostConstruct
  void load() {
    if (this.file == null || !Files.exists(this.file))
      return;
    try {
      JsonObject j = new JsonObject(Files.readString(this.file));
      j.getMap().forEach((id, token) -> {
        if (token instanceof String t)
          this.tokensBySession.put(id, t);
      });
      log.info("Restored {} session(s) from {}", this.tokensBySession.size(), this.file);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not restore sessions from {}: {}", this.file, e.toString());
    }
  }

  private synchronized void persist() {
    if (this.file == null)
      return;
    try {
      if (this.file.getParent() != null)
        Files.createDirectories(this.file.getParent());
      Files.writeString(this.file, new JsonObject(Map.copyOf(this.tokensBySession)).encode());
    } catch (IOException e) {
      log.warn("Could not persist sessions to {}: {}", this.file, e.toString());
    }
  }

  public String create(String token) {
    String id = UUID.randomUUID().toString();
    this.tokensBySession.put(id, token);
    persist();
    return id;
  }

  /** The API token for a session, empty if the session is unknown. */
  public Optional<String> token(String id) {
    return Optional.ofNullable(id == null ? null : this.tokensBySession.get(id));
  }

  /** Ends a session, returning the token it held. */
  public Optional<String> invalidate(String id) {
    Optional<String> token = Optional.ofNullable(id == null ? null : this.tokensBySession.remove(id));
    if (token.isPresent())
      persist();
    return token;
  }
}
