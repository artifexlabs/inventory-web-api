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

import java.util.List;
import java.util.Optional;

import io.vertx.core.json.JsonObject;

/**
 * The webapp's view of inventory-server. The browser never talks to the server
 * directly — every call goes through this client with the session's token.
 *
 * @author mykel
 *
 */
public interface ServerClient {

  record Login(String token, JsonObject user) {
  }

  /** Thrown when the server rejects the token; callers end the session. */
  class Unauthorized extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  Optional<Login> login(String email, String password);

  /** OIDC path: trade a Google-verified email for a token via the shared secret. */
  Optional<Login> exchange(String email, String displayName);

  void logout(String token);

  List<JsonObject> items(String token);

  Optional<JsonObject> item(String token, String id);

  List<JsonObject> containersOf(String token, String id);

  boolean addToContainer(String token, String containerId, String itemId);

  boolean removeFromContainer(String token, String containerId, String itemId);

  boolean moveToContainer(String token, String itemId, String containerId);

  Optional<JsonObject> createItem(String token, String name, String displayName, String type);

  boolean updateItem(String token, JsonObject item);

  boolean deleteItem(String token, String id);

  List<JsonObject> users(String token);

  Optional<JsonObject> createUser(String token, String email, String displayName, String password, boolean admin);

  boolean deleteUser(String token, String id);

  boolean setAdmin(String token, String id, boolean admin);

  List<JsonObject> tokensFor(String token, String userId);

  boolean revokeToken(String token, String tokenToRevoke);

  List<JsonObject> auditRecent(String token, int limit, int offset);

  List<JsonObject> auditFor(String token, String targetId, int limit);
}
