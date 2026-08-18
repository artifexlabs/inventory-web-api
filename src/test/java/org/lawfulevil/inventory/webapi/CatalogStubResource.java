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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A local Open-Facts-shaped catalog for the e2e tests: knows exactly one
 * GTIN (with a product image), answers the OFF "status 0" shape for
 * everything else. Points {@code inventory.catalog} at itself so no test
 * ever touches the network.
 */
public class CatalogStubResource implements QuarkusTestResourceLifecycleManager {

  /** The one GTIN the stub catalog knows. */
  public final static String KNOWN_GTIN = "0049000006346";
  public final static byte[] IMAGE = new byte[] { (byte) 0x89, 'P', 'N', 'G', 9, 9 };

  private HttpServer server;

  @Override
  public Map<String, String> start() {
    try {
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("catalog stub failed to bind", e);
    }
    String base = "http://127.0.0.1:" + this.server.getAddress().getPort();
    this.server.createContext("/api/v2/product/", ex -> {
      String path = ex.getRequestURI().getPath();
      String body = path.contains(KNOWN_GTIN) ? """
          {"status":1,"product":{"product_name":"Stub Cola","brands":"StubCo",
           "generic_name":"A canned test beverage","categories":"en:beverages, en:test-drinks",
           "product_quantity":355,"product_quantity_unit":"g","image_url":"%s/image.png"}}"""
          .formatted(base) : "{\"status\":0}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(bytes);
      }
    });
    this.server.createContext("/image.png", ex -> {
      ex.getResponseHeaders().add("Content-Type", "image/png");
      ex.sendResponseHeaders(200, IMAGE.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(IMAGE);
      }
    });
    this.server.start();
    return Map.of("inventory.catalog", "open-facts", "inventory.catalog.open-facts.url", base);
  }

  @Override
  public void stop() {
    if (this.server != null)
      this.server.stop(0);
  }
}
