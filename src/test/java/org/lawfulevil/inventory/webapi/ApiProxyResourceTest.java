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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(StubBackend.class)
class ApiProxyResourceTest {

  @Test
  void forwardsMethodPathAndAuthorization() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/items/abc/containers").then().statusCode(200)
        .body("method", equalTo("GET")).body("path", equalTo("/api/v1/items/abc/containers"))
        .body("authorization", equalTo("Bearer tkn"));
  }

  @Test
  void forwardsQueryString() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/audit?limit=5&offset=2").then().statusCode(200)
        .body("query", equalTo("limit=5&offset=2"));
  }

  @Test
  void forwardsPostBodyAndContentType() {
    given().contentType("application/json").body("{\"email\":\"a@b.c\"}").post("/api/v1/auth/login").then()
        .statusCode(200).body("method", equalTo("POST")).body("body", equalTo("{\"email\":\"a@b.c\"}"))
        .body("contentType", containsString("application/json"));
  }

  @Test
  void forwardsExchangeAndFilenameHeaders() {
    given().header("X-Exchange-Secret", "shh").header("X-Filename", "cat.jpg").contentType("image/jpeg")
        .body(new byte[] { 9, 9 }).post("/api/v1/items/abc/assets").then().statusCode(200)
        .body("exchangeSecret", equalTo("shh")).body("filename", equalTo("cat.jpg"));
  }

  @Test
  void forwardsPutAndDelete() {
    given().contentType("application/json").body("{\"id\":\"abc\"}").put("/api/v1/items/abc").then()
        .statusCode(200).body("method", equalTo("PUT"));
    given().delete("/api/v1/items/abc").then().statusCode(200).body("method", equalTo("DELETE"))
        .body("body", equalTo(""));
  }

  @Test
  void passesStatusCodesThrough() {
    given().get("/api/v1/secure").then().statusCode(401);
    given().get("/api/v1/gone").then().statusCode(204);
  }

  @Test
  void passesBinaryResponsesThroughUntouched() {
    byte[] png = given().get("/api/v1/items/x/qr.png").then().statusCode(200).contentType("image/png")
        .header("X-Filename", equalTo("x.png")).extract().asByteArray();
    assertArrayEquals(StubBackend.PNG_BYTES, png);
  }

  @Test
  void servesNothingOutsideTheApiSurface() {
    given().redirects().follow(false).get("/").then().statusCode(404).header("Location", nullValue());
    given().get("/login").then().statusCode(404);
    given().get("/items").then().statusCode(404);
  }
}
