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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionStoreTest {

  @TempDir
  Path tmp;

  @Test
  public void testSessionsSurviveRestart() {
    String file = this.tmp.resolve("sessions.json").toString();

    SessionStore first = new SessionStore(file);
    first.load();
    String id = first.create("token-123");
    assertEquals("token-123", first.token(id).get());

    // a fresh instance over the same file sees the session
    SessionStore second = new SessionStore(file);
    second.load();
    assertEquals("token-123", second.token(id).get());

    // invalidation also persists
    assertEquals("token-123", second.invalidate(id).get());
    SessionStore third = new SessionStore(file);
    third.load();
    assertTrue(third.token(id).isEmpty());
  }

  @Test
  public void testPersistenceDisabled() {
    SessionStore store = new SessionStore("none");
    store.load();
    String id = store.create("token-xyz");
    assertEquals("token-xyz", store.token(id).get());
    assertTrue(store.token("unknown").isEmpty());
    assertTrue(store.invalidate("unknown").isEmpty());
  }
}
