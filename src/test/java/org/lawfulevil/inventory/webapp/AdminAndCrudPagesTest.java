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

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class AdminAndCrudPagesTest {

  @Inject
  StubServerClient stub;

  @BeforeEach
  public void reset() {
    this.stub.reset();
  }

  private String login() {
    return given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
        .formParam("email", StubServerClient.EMAIL).formParam("password", StubServerClient.PASSWORD)
        .post("/login").then().statusCode(303).extract().cookie("inv_session");
  }

  @Test
  public void testCreateEditDeleteItemFromUi() {
    String s = login();
    // create
    String location = given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "new-shelf")
        .formParam("type", "container").post("/items/create").then().statusCode(303).extract()
        .header("Location");
    String id = location.substring(location.lastIndexOf('/') + 1);
    given().cookie("inv_session", s).get("/items/" + id).then().statusCode(200).body(containsString("new-shelf"));

    // edit: set description and quantity
    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "new-shelf")
        .formParam("type", "container").formParam("description", "back wall").formParam("quantity", "3")
        .post("/items/" + id + "/edit").then().statusCode(303);
    given().cookie("inv_session", s).get("/items/" + id).then().statusCode(200)
        .body(containsString("back wall"));

    // history rendered from audit trail
    given().cookie("inv_session", s).get("/items/" + id).then().statusCode(200)
        .body(containsString("item.update"));

    // delete
    given().redirects().follow(false).cookie("inv_session", s).post("/items/" + id + "/delete").then()
        .statusCode(303).header("Location", containsString("/items"));
    given().cookie("inv_session", s).get("/items/" + id).then().statusCode(404);
  }

  @Test
  public void testAdminPageListsAndCreatesUsers() {
    String s = login();
    given().cookie("inv_session", s).get("/admin").then().statusCode(200)
        .body(containsString(StubServerClient.EMAIL));

    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("email", "new@example.com")
        .formParam("password", "pw").post("/admin/users").then().statusCode(303);
    given().cookie("inv_session", s).get("/admin").then().statusCode(200)
        .body(containsString("new@example.com"));
  }

  @Test
  public void testAdminPagesRequireSession() {
    // no session cookie, or a bogus one, redirects to login (server-side 403 for
    // authenticated non-admins is covered by inventory-server's tests)
    String s = login();
    given().redirects().follow(false).cookie("inv_session", "no-such-session").get("/admin").then()
        .statusCode(303).header("Location", containsString("/login"));
    given().redirects().follow(false).get("/audit").then().statusCode(303).header("Location",
        containsString("/login"));
    given().cookie("inv_session", s).get("/admin").then().statusCode(200);
  }

  @Test
  public void testAuditPageRendersEvents() {
    String s = login();
    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "tracked").post("/items/create")
        .then().statusCode(303);
    given().cookie("inv_session", s).get("/audit").then().statusCode(200)
        .body(containsString("item.create"));
  }

  @Test
  public void testTokensPageRevokeFlow() {
    String s = login();
    this.stub.seedToken("tok-abc", "u-1");

    given().cookie("inv_session", s).get("/admin/users/u-1/tokens").then().statusCode(200)
        .body(containsString("tok-abc")).body(containsString("active"));

    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("token", "tok-abc")
        .formParam("userId", "u-1").post("/admin/tokens/revoke").then().statusCode(303);
    given().cookie("inv_session", s).get("/admin/users/u-1/tokens").then().statusCode(200)
        .body(containsString("revoked"));
  }
}
