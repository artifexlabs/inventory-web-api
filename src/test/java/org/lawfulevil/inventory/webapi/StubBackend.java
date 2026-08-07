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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A fake inventory-server: echoes what it receives so proxy tests can assert
 * exactly what was forwarded, plus fixed 401 and binary endpoints.
 */
public class StubBackend implements QuarkusTestResourceLifecycleManager {
  static final byte[] PNG_BYTES = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

  /** Canned inventory-server data for the /api/v1/views aggregate tests. */
  static final String WRENCH = "{\"id\":\"w-1\",\"name\":\"wrench\",\"type\":\"tool\",\"quantity\":2,"
      + "\"parValues\":{\"minOnHand\":5,\"maxOnHand\":10}}";
  static final String TOOLBOX = "{\"id\":\"box-1\",\"name\":\"toolbox\",\"displayName\":\"Big red toolbox\","
      + "\"type\":\"container\",\"locationId\":\"loc-1\","
      + "\"containedItems\":[{\"id\":\"w-1\",\"name\":\"wrench\"}]}";
  static final String SHELF = "{\"id\":\"shelf-1\",\"name\":\"shelf\",\"type\":\"container\"}";

  private HttpServer server;

  @Override
  public Map<String, String> start() {
    try {
      this.server = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.server.createContext("/", exchange -> {
      try {
        handle(exchange);
      } catch (RuntimeException | Error t) {
        respond(exchange, 500, "text/plain", ("stub failure: " + t).getBytes());
      }
    });
    this.server.start();
    return Map.of("inventory.server.url", "http://localhost:" + this.server.getAddress().getPort());
  }

  private static void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    {
      String path = exchange.getRequestURI().getPath();
      byte[] body = exchange.getRequestBody().readAllBytes();
      if ("Bearer bad".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        respond(exchange, 401, "text/plain", "unauthorized".getBytes());
      } else if (path.equals("/api/v1/items") && exchange.getRequestMethod().equals("GET")) {
        respond(exchange, 200, "application/json", ("[" + WRENCH + "," + TOOLBOX + "," + SHELF + "]").getBytes());
      } else if (path.equals("/api/v1/items/box-1") && exchange.getRequestMethod().equals("GET")) {
        respond(exchange, 200, "application/json", TOOLBOX.getBytes());
      } else if (path.equals("/api/v1/items/missing")) {
        respond(exchange, 404, "text/plain", "no such item".getBytes());
      } else if (path.equals("/api/v1/items/box-1/containers")) {
        respond(exchange, 200, "application/json", ("[" + SHELF + "]").getBytes());
      } else if (path.equals("/api/v1/audit/target/box-1")) {
        respond(exchange, 200, "application/json",
            ("[{\"timestamp\":\"2026-08-07T12:00:00Z\",\"principal\":\"a@b.c\","
                + "\"action\":\"item.update\",\"targetId\":\"box-1\"}]").getBytes());
      } else if (path.equals("/api/v1/locations")) {
        respond(exchange, 200, "application/json", "[{\"id\":\"loc-1\",\"name\":\"Garage\"}]".getBytes());
      } else if (path.equals("/api/v1/items/box-1/assets")) {
        respond(exchange, 200, "application/json",
            ("[{\"id\":\"asset-1\",\"itemId\":\"box-1\",\"filename\":\"photo.png\","
                + "\"contentType\":\"image/png\",\"sizeBytes\":3}]").getBytes());
      } else if (path.equals("/api/v1/secure")) {
        respond(exchange, 401, "text/plain", "unauthorized".getBytes());
      } else if (path.equals("/api/v1/items/x/qr.png")) {
        exchange.getResponseHeaders().set("X-Filename", "x.png");
        respond(exchange, 200, "image/png", PNG_BYTES);
      } else if (path.equals("/api/v1/gone")) {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      } else {
        // Built by hand: Vert.x JsonObject's SPI cannot load across the
        // QuarkusTest / test-resource classloader boundary.
        String echo = "{" + field("method", exchange.getRequestMethod()) + "," + field("path", path) + ","
            + field("query", exchange.getRequestURI().getRawQuery()) + ","
            + field("authorization", exchange.getRequestHeaders().getFirst("Authorization")) + ","
            + field("exchangeSecret", exchange.getRequestHeaders().getFirst("X-Exchange-Secret")) + ","
            + field("filename", exchange.getRequestHeaders().getFirst("X-Filename")) + ","
            + field("contentType", exchange.getRequestHeaders().getFirst("Content-Type")) + ","
            + field("body", new String(body)) + "}";
        respond(exchange, 200, "application/json", echo.getBytes());
      }
    }
  }

  private static String field(String name, String value) {
    String v = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + name + "\":\"" + v + "\"";
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
      byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  @Override
  public void stop() {
    if (this.server != null)
      this.server.stop(0);
  }
}
