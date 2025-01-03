package com.linagora.tmail.james.common

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.http.ContentType.JSON
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, BOB, BOB_PASSWORD, DOMAIN, authScheme, baseRequestSpecBuilder}
import org.apache.james.utils.DataProbeImpl
import org.hamcrest.Matchers.{equalTo, hasKey}
import org.junit.jupiter.api.{BeforeEach, Test}

object MemoryLinagoraContactSupportCapabilityContract {
  trait MailAddressConfigured {
    @BeforeEach
    def setUp(server: GuiceJamesServer): Unit = {
      server.getProbe(classOf[DataProbeImpl])
        .fluent()
        .addDomain(DOMAIN.asString())
        .addUser(BOB.asString(), BOB_PASSWORD)

      requestSpecification = baseRequestSpecBuilder(server)
        .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
        .build
    }

    @Test
    def shouldReturnCorrectInfoInContactSupportCapability(): Unit = {
      `given`()
      .when()
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .get("/session")
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .body("capabilities", hasKey("com:linagora:params:jmap:contact:support"))
        .body("capabilities.'com:linagora:params:jmap:contact:support'", hasKey("supportMailAddress"))
        .body("capabilities.'com:linagora:params:jmap:contact:support'.supportMailAddress", equalTo("support@linagora.com"))
    }
  }

  trait MailAddressNotConfigured {
    @BeforeEach
    def setUp(server: GuiceJamesServer): Unit = {
      server.getProbe(classOf[DataProbeImpl])
        .fluent()
        .addDomain(DOMAIN.asString())
        .addUser(BOB.asString(), BOB_PASSWORD)

      requestSpecification = baseRequestSpecBuilder(server)
        .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
        .build
    }

    @Test
    def shouldReturnNullSupportMailAddressWhenItIsNotConfigured(): Unit = {
      `given`()
      .when()
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .get("/session")
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .body("capabilities", hasKey("com:linagora:params:jmap:contact:support"))
        .body("capabilities.'com:linagora:params:jmap:contact:support'", hasKey("supportMailAddress"))
        .body("capabilities.'com:linagora:params:jmap:contact:support'.supportMailAddress", equalTo(null))
    }
  }
}