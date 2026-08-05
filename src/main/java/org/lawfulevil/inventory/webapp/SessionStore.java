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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Server-side sessions: the browser holds only an opaque HTTP-only cookie; the
 * API token never leaves this process.
 *
 * @author mykel
 *
 */
@ApplicationScoped
public class SessionStore {

  public record Session(String token, JsonObject user) {
  }

  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  public String create(String token, JsonObject user) {
    String id = UUID.randomUUID().toString();
    this.sessions.put(id, new Session(token, user));
    return id;
  }

  public Optional<Session> get(String id) {
    return Optional.ofNullable(id == null ? null : this.sessions.get(id));
  }

  public Optional<Session> invalidate(String id) {
    return Optional.ofNullable(id == null ? null : this.sessions.remove(id));
  }
}
