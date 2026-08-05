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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class PageResourceTest {

  @Inject
  StubServerClient stub;

  @BeforeEach
  public void reset() {
    this.stub.reset();
  }

  private String loginAndGetSessionCookie() {
    String cookie = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
        .formParam("email", StubServerClient.EMAIL).formParam("password", StubServerClient.PASSWORD)
        .post("/login").then().statusCode(303).header("Location", containsString("/items")).extract()
        .cookie("inv_session");
    assertNotNull(cookie);
    return cookie;
  }

  @Test
  public void testUnauthenticatedBrowsingRedirectsToLogin() {
    given().redirects().follow(false).get("/items").then().statusCode(303).header("Location",
        containsString("/login"));
    given().redirects().follow(false).get("/items/box-1").then().statusCode(303).header("Location",
        containsString("/login"));
  }

  @Test
  public void testBadLoginShowsError() {
    given().contentType("application/x-www-form-urlencoded").formParam("email", StubServerClient.EMAIL)
        .formParam("password", "wrong").post("/login").then().statusCode(200)
        .body(containsString("Invalid email or password"));
  }

  @Test
  public void testItemsPageRendersAndLinksToDetail() {
    String session = loginAndGetSessionCookie();
    given().cookie("inv_session", session).get("/items").then().statusCode(200)
        .body(containsString("toolbox")).body(containsString("/items/box-1"))
        .body(containsString(StubServerClient.EMAIL));
  }

  @Test
  public void testDetailShowsChildrenAndContainersBothWays() {
    String session = loginAndGetSessionCookie();
    // the container lists its child as a link
    given().cookie("inv_session", session).get("/items/box-1").then().statusCode(200)
        .body(containsString("/items/wrench-1"));
    // the child links back to its container
    given().cookie("inv_session", session).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("/items/box-1"));
  }

  @Test
  public void testMoveBetweenContainers() {
    String session = loginAndGetSessionCookie();
    given().redirects().follow(false).cookie("inv_session", session)
        .contentType("application/x-www-form-urlencoded").formParam("containerId", "bin-1")
        .post("/items/wrench-1/move-to").then().statusCode(303)
        .header("Location", containsString("/items/wrench-1"));

    // wrench now reports the bin as its container, and the box no longer lists it
    given().cookie("inv_session", session).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("/items/bin-1"));
    given().cookie("inv_session", session).get("/items/box-1").then().statusCode(200)
        .body(containsString("This item contains nothing"));
  }

  @Test
  public void testAddAndRemoveContainer() {
    String session = loginAndGetSessionCookie();
    given().redirects().follow(false).cookie("inv_session", session)
        .contentType("application/x-www-form-urlencoded").formParam("containerId", "bin-1")
        .post("/items/wrench-1/add-to").then().statusCode(303);
    // now in two containers
    given().cookie("inv_session", session).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("/items/box-1")).body(containsString("/items/bin-1"));

    given().redirects().follow(false).cookie("inv_session", session)
        .contentType("application/x-www-form-urlencoded").formParam("containerId", "box-1")
        .post("/items/wrench-1/remove-from").then().statusCode(303);
    given().cookie("inv_session", session).get("/items/box-1").then().statusCode(200)
        .body(containsString("This item contains nothing"));
  }

  @Test
  public void testLogoutEndsSession() {
    String session = loginAndGetSessionCookie();
    given().redirects().follow(false).cookie("inv_session", session).post("/logout").then().statusCode(303);
    given().redirects().follow(false).cookie("inv_session", session).get("/items").then().statusCode(303)
        .header("Location", containsString("/login"));
  }
}
