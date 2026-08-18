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

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class AuthAndContainmentTest {

  private static io.restassured.specification.RequestSpecification withToken(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String create(String token, String name, String type) {
    return new JsonObject(withToken(token).body(new JsonObject().put("name", name).put("type", type).encode())
        .post("/api/v1/items").then().statusCode(201).extract().asString()).getString("id");
  }

  @Test
  public void testLoginIssuesWorkingToken() {
    String body = given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
        .post("/api/v1/auth/login").then().statusCode(200).extract().asString();
    JsonObject login = new JsonObject(body);
    String token = login.getString("token");
    assertNotNull(token);
    assertNotNull(login.getJsonObject("user").getString("id"));

    withToken(token).get("/api/v1/items").then().statusCode(200);
  }

  @Test
  public void testBadCredentialsRejected() {
    given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "nope").encode())
        .post("/api/v1/auth/login").then().statusCode(401);
    given().contentType(ContentType.JSON).body("{}").post("/api/v1/auth/login").then().statusCode(401);
  }

  @Test
  public void testMeReflectsCurrentUser() {
    withToken("dev-token").get("/api/v1/auth/me").then().statusCode(200)
        .body("email", equalTo("admin@example.com")).body("admin", is(true));
    given().get("/api/v1/auth/me").then().statusCode(401);
  }

  @Test
  public void testLogoutRevokesToken() {
    String token = new JsonObject(given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
        .post("/api/v1/auth/login").then().statusCode(200).extract().asString()).getString("token");

    withToken(token).get("/api/v1/items").then().statusCode(200);
    withToken(token).post("/api/v1/auth/logout").then().statusCode(200).body("revoked", is(true));
    withToken(token).get("/api/v1/items").then().statusCode(401);
  }

  @Test
  public void testContainmentEndpoints() {
    String token = "dev-token";
    String box = create(token, "box", "cont-box");
    String bin = create(token, "bin", "cont-bin");
    String bolt = create(token, "bolt", "cont-part");

    withToken(token).put("/api/v1/items/" + box + "/contained/" + bolt).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/container").then().statusCode(200)
        .body("id", equalTo(box));

    // single-parent tree: contain into a second container RE-PARENTS
    withToken(token).put("/api/v1/items/" + bin + "/contained/" + bolt).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/container").then().statusCode(200)
        .body("id", equalTo(bin));

    withToken(token).post("/api/v1/items/" + bolt + "/move-to/" + box).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/container").then().statusCode(200)
        .body("id", equalTo(box));

    withToken(token).delete("/api/v1/items/" + box + "/contained/" + bolt).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/container").then().statusCode(404); // a root
    withToken(token).delete("/api/v1/items/" + box + "/contained/" + bolt).then().statusCode(404);
    withToken(token).put("/api/v1/items/missing/contained/" + bolt).then().statusCode(404);

    // cycle refusal end to end: box > bin, then bin cannot contain box
    withToken(token).put("/api/v1/items/" + box + "/contained/" + bin).then().statusCode(204);
    withToken(token).put("/api/v1/items/" + bin + "/contained/" + box).then().statusCode(404);
  }

  @Test
  public void testTagsAndCoordinatesEndpoints() {
    String token = "dev-token";
    String garage = create(token, "tag-garage", "location");
    String crate = create(token, "tag-crate", "cont-box");

    // pin the garage, contain the crate, and the crate inherits
    JsonObject full = new JsonObject(withToken(token).get("/api/v1/items/" + garage)
        .then().statusCode(200).extract().asString()).put("latitude", 33.7).put("longitude", -84.4);
    withToken(token).contentType(ContentType.JSON).body(full.encode())
        .put("/api/v1/items/" + garage).then().statusCode(200);
    withToken(token).put("/api/v1/items/" + garage + "/contained/" + crate).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + crate + "/coordinates").then().statusCode(200)
        .body("latitude", is(33.7f));

    // tags: attach, search, remove
    withToken(token).contentType(ContentType.JSON)
        .body(new JsonObject().put("key", "color").put("value", "orange").encode())
        .put("/api/v1/items/" + crate + "/tags").then().statusCode(204);
    withToken(token).get("/api/v1/items/by-tag?key=color&value=ora*&mode=glob").then().statusCode(200)
        .body("size()", is(1)).body("[0].id", equalTo(crate));
    withToken(token).delete("/api/v1/items/" + crate + "/tags/color").then().statusCode(204);
    withToken(token).get("/api/v1/items/by-tag?key=color").then().statusCode(200).body("size()", is(0));
  }

  @Test
  public void testItemIdentityEndpoints() {
    String token = "dev-token";
    String scanner = create(token, "scanner", "ident-tool");
    String rival = create(token, "rival", "ident-tool");

    // claim a UPC and an NFC UID for the scanner
    withToken(token).contentType(ContentType.JSON)
        .body(new JsonObject().put("kind", "upc").put("value", "012345678905").encode())
        .put("/api/v1/items/" + scanner + "/identities").then().statusCode(204);
    withToken(token).contentType(ContentType.JSON)
        .body(new JsonObject().put("kind", "NFC-UID").put("value", "04:A2:B3").encode())
        .put("/api/v1/items/" + scanner + "/identities").then().statusCode(204);

    // a scanned marker resolves to the item (kind normalized to lowercase)
    withToken(token).get("/api/v1/items/by-identity?kind=upc&value=012345678905").then().statusCode(200)
        .body("id", equalTo(scanner));
    withToken(token).get("/api/v1/items/by-identity?kind=nfc-uid&value=04:A2:B3").then().statusCode(200)
        .body("id", equalTo(scanner));
    withToken(token).get("/api/v1/items/" + scanner + "/identities").then().statusCode(200)
        .body("size()", is(2)).body("[1].kind", equalTo("upc"));

    // reusing a claimed marker on another item is a 409, and nothing moves
    withToken(token).contentType(ContentType.JSON)
        .body(new JsonObject().put("kind", "upc").put("value", "012345678905").encode())
        .put("/api/v1/items/" + rival + "/identities").then().statusCode(409);
    withToken(token).get("/api/v1/items/by-identity?kind=upc&value=012345678905").then().statusCode(200)
        .body("id", equalTo(scanner));

    // release frees the marker; unknown markers and blank claims are refused
    withToken(token).delete("/api/v1/items/" + scanner + "/identities/upc/012345678905").then().statusCode(204);
    withToken(token).get("/api/v1/items/by-identity?kind=upc&value=012345678905").then().statusCode(404);
    withToken(token).delete("/api/v1/items/" + scanner + "/identities/upc/012345678905").then().statusCode(404);
    withToken(token).contentType(ContentType.JSON).body(new JsonObject().put("kind", "upc").encode())
        .put("/api/v1/items/" + scanner + "/identities").then().statusCode(400);
    withToken(token).contentType(ContentType.JSON)
        .body(new JsonObject().put("kind", "upc").put("value", "1").encode())
        .put("/api/v1/items/missing-item/identities").then().statusCode(404);
  }
}
