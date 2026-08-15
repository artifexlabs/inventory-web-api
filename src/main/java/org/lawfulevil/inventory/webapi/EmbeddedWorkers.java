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
package org.lawfulevil.inventory.webapi;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lawfulevil.inventory.api.AssetStore;
import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.InventoryUser;
import org.lawfulevil.inventory.api.LabelPrinter;
import org.lawfulevil.inventory.api.RegionSystem;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.impl.InMemoryTokenService;
import org.lawfulevil.inventory.impl.UserStore;
import org.lawfulevil.inventory.impl.bus.BusGuard;
import org.lawfulevil.inventory.impl.bus.BusWorkers;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Single-process mode ({@code inventory.bus.workers=embedded}, the dev/test
 * default): the gateway deploys the SAME worker set inventory-server hosts,
 * on its local bus, against its own backend beans — every request still
 * crosses the envelope contract, so embedded and remote behave identically
 * minus the network. In {@code remote} mode nothing here runs and no backend
 * bean is ever created: the domain lives in inventory-server, reached over
 * the clustered bus.
 *
 * Admin seeding lives here (not in the producer) so remote mode never touches
 * storage from the gateway.
 */
@ApplicationScoped
public class EmbeddedWorkers {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmbeddedWorkers.class);

  @ConfigProperty(name = "inventory.bus.workers", defaultValue = "embedded")
  String mode;

  @ConfigProperty(name = "inventory.bus.token", defaultValue = "dev-bus-token")
  String fabricToken;

  @ConfigProperty(name = "inventory.oidc.provision", defaultValue = "invited")
  String provision;

  @Inject
  Vertx vertx;

  void onStart(@Observes StartupEvent ev, Instance<InventorySystem> inventory,
      Instance<AssetStore> assets, Instance<RegionSystem> regions,
      Instance<AuditReader> auditReader, Instance<AuditSink> auditSink, Instance<LabelPrinter> printer,
      Instance<UserStore> users, Instance<TokenService> tokens) {
    if (!"embedded".equals(this.mode)) {
      log.info("bus workers remote: the gateway sends envelopes to inventory-server");
      return;
    }
    var services = new BusWorkers.BackendServices(inventory.get(), assets.get(), regions.get(),
        auditReader.get(), auditSink.get(), printer.get(), users.get(), tokens.get());
    try {
      BusWorkers.deploy(this.vertx, services, new BusGuard(this.fabricToken), this.provision).toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("embedded bus workers failed to deploy", e);
    }
    seedAdmin(services);
    log.info("embedded bus workers deployed on the local bus");
  }

  private static String config(String name, String defaultValue) {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue(name, String.class).orElse(defaultValue);
  }

  /** Ensure the configured admin exists; seed the static dev token in memory mode. */
  private static void seedAdmin(BusWorkers.BackendServices services) {
    InventoryUser admin = services.users()
        .ensureUser(config("inventory.admin.email", "admin@example.com"), "Administrator",
            config("inventory.admin.password", "change-me"), true)
        .toCompletableFuture().join();
    if (services.tokens() instanceof InMemoryTokenService memoryTokens)
      memoryTokens.seed(config("inventory.api.token", "dev-token"), admin);
  }
}
