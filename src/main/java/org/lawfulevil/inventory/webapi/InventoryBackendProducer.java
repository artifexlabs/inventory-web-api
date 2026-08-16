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

import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.impl.InMemoryAuditSink;
import org.lawfulevil.inventory.impl.PgAudit;
import org.lawfulevil.inventory.impl.InMemoryInventorySystem;
import org.lawfulevil.inventory.impl.InMemoryTokenService;
import org.lawfulevil.inventory.impl.InMemoryUserStore;
import org.lawfulevil.inventory.impl.PgInventorySystem;
import org.lawfulevil.inventory.impl.PgTokenService;
import org.lawfulevil.inventory.impl.PgUserStore;
import org.lawfulevil.inventory.impl.UserStore;

import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Selects the storage backend for EMBEDDED mode only. Since the event-bus
 * migration the gateway holds no domain beans of its own: in remote mode
 * ({@code inventory.bus.workers=remote}) these producers are never invoked —
 * nothing injects them — and inventory-server owns the storage. In embedded
 * mode {@link EmbeddedWorkers} wires these beans into the same worker set
 * the server would host. {@code inventory.storage=memory} (default) serves
 * everything from memory; {@code inventory.storage=pg} uses the Postgres
 * implementations against the configured reactive datasource. The Pool is
 * resolved lazily so memory mode needs no datasource at all.
 */
@ApplicationScoped
public class InventoryBackendProducer {

  // Config is read lazily (never injected into fields): in native images this
  // bean can be instantiated during static init, and field-injected values
  // would be frozen at their build-time defaults (e.g. storage=memory even
  // when the container says pg).
  private static String config(String name, String defaultValue) {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue(name, String.class).orElse(defaultValue);
  }

  private String storage() {
    return config("inventory.storage", "memory");
  }

  private String principal() {
    return config("inventory.principal", "inventory-web-api");
  }

  @Inject
  Instance<Pool> pools;

  private final InMemoryAuditSink memoryAudit = new InMemoryAuditSink();
  private volatile PgAudit pgAudit;

  private PgAudit pgAudit() {
    if (this.pgAudit == null)
      this.pgAudit = new PgAudit(this.pools.get());
    return this.pgAudit;
  }

  /**
   * Where committed domain facts go (VERTICLES.md):
   * {@code inventory.events.bus} = {@code none} (default — events go
   * nowhere), {@code local} (in-process Vert.x bus), or {@code clustered}
   * (same publisher; the cluster is Vert.x configuration, not code).
   */
  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.events.EventPublisher eventPublisher(
      Instance<io.vertx.core.Vertx> vertx) {
    return switch (config("inventory.events.bus", "none")) {
    case "local", "clustered" -> new org.lawfulevil.inventory.impl.bus.VertxEventPublisher(vertx.get());
    default -> org.lawfulevil.inventory.api.events.EventPublisher.NOOP;
    };
  }

  @Produces
  @Singleton
  public InventorySystem inventorySystem(org.lawfulevil.inventory.api.events.EventPublisher events,
      AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new PgInventorySystem(this.pools.get(), principal()).withEventPublisher(events);
    default -> new InMemoryInventorySystem(sink, principal());
    };
  }

  /**
   * The sink every recorder sees is the publishing decorator: recorded events
   * are also announced as domain facts. The Pg domain systems bypass this (in-
   * transaction audit rows) and publish after commit themselves.
   */
  @Produces
  @Singleton
  public AuditSink auditSink(org.lawfulevil.inventory.api.events.EventPublisher events) {
    AuditSink raw = switch (storage()) {
    case "pg" -> pgAudit();
    default -> this.memoryAudit;
    };
    return new org.lawfulevil.inventory.impl.PublishingAuditSink(raw, events);
  }

  @Produces
  @Singleton
  public AuditReader auditReader() {
    return switch (storage()) {
    case "pg" -> pgAudit();
    default -> this.memoryAudit;
    };
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.LabelPrinter labelPrinter(InventorySystem items) {
    return switch (config("inventory.printer", "log")) {
    case "brother-p750w" -> new org.lawfulevil.inventory.impl.BrotherPTouchPrinter(
        config("inventory.printer.host", "localhost"),
        Integer.parseInt(config("inventory.printer.port", "9100")),
        Integer.parseInt(config("inventory.printer.tape-mm", "24")),
        Boolean.parseBoolean(config("inventory.printer.chain", "false")));
    case "zebra-gk420t" -> new org.lawfulevil.inventory.impl.ZebraPrinter(
        config("inventory.printer.host", "localhost"),
        Integer.parseInt(config("inventory.printer.port", "9100")),
        config("inventory.printer.format", "standard"))
        // labels print the container's name — "where is it" IS the container
        .withContainerLookup(items::getItem);
    default -> new org.lawfulevil.inventory.impl.LoggingLabelPrinter();
    };
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.AssetStore assetStore(InventorySystem items,
      org.lawfulevil.inventory.api.events.EventPublisher events, AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new org.lawfulevil.inventory.impl.PgAssetStore(this.pools.get(), principal())
        .withEventPublisher(events);
    default -> new org.lawfulevil.inventory.impl.InMemoryAssetStore(items, sink, principal());
    };
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.RegionSystem regionSystem(InventorySystem items,
      org.lawfulevil.inventory.api.AssetStore assets,
      org.lawfulevil.inventory.api.events.EventPublisher events, AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new org.lawfulevil.inventory.impl.PgRegionSystem(this.pools.get(), principal())
        .withEventPublisher(events);
    default -> new org.lawfulevil.inventory.impl.InMemoryRegionSystem(items, assets, sink, principal());
    };
  }

  @Produces
  @Singleton
  public UserStore userStore() {
    return switch (storage()) {
    case "pg" -> new PgUserStore(this.pools.get());
    default -> new InMemoryUserStore();
    };
  }

  @Produces
  @Singleton
  public TokenService tokenService() {
    return switch (storage()) {
    case "pg" -> new PgTokenService(this.pools.get());
    default -> new InMemoryTokenService();
    };
  }

  // Admin seeding happens in EmbeddedWorkers (embedded mode only): in remote
  // mode the gateway owns no storage, so nothing here may touch these beans
  // at startup.
}
