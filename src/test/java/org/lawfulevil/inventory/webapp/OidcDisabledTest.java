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
import static org.hamcrest.CoreMatchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** Default configuration: OIDC off — no Google button, entry point bounces to /login. */
@QuarkusTest
public class OidcDisabledTest {

  @Test
  public void testOidcEntryPointRedirectsToLoginWhenDisabled() {
    given().redirects().follow(false).get("/oidc/login").then().statusCode(303).header("Location",
        containsString("/login"));
  }

  @Test
  public void testLoginPageHidesGoogleButtonWhenDisabled() {
    given().get("/login").then().statusCode(200).body(not(containsString("Sign in with Google")));
  }
}
