package com.linagora.tmail.james.common

import com.linagora.tmail.james.common.probe.JmapSettingsProbe
import com.linagora.tmail.james.jmap.settings.JmapSettingsStateFactory
import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.http.ContentType.JSON
import io.restassured.path.json.JsonPath
import net.javacrumbs.jsonunit.JsonMatchers.jsonEquals
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.core.ResponseObject.SESSION_STATE
import org.apache.james.jmap.core.UuidState
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, ACCOUNT_ID, ANDRE, ANDRE_PASSWORD, BOB, BOB_PASSWORD, DOMAIN, authScheme, baseRequestSpecBuilder}
import org.apache.james.jmap.rfc8621.contract.probe.DelegationProbe
import org.apache.james.utils.DataProbeImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

trait JmapSettingsSetMethodContract {
  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent()
      .addDomain(DOMAIN.asString)
      .addUser(BOB.asString(), BOB_PASSWORD)
      .addUser(ANDRE.asString(), ANDRE_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .addHeader(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .build()
  }

  @Test
  def missingSettingsCapabilityShouldFail(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"tdrive.attachment.import.enabled": "true"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("", jsonEquals(
        s"""{
           |	"sessionState": "${SESSION_STATE.value}",
           |	"methodResponses": [
           |		[
           |			"error",
           |			{
           |				"type": "unknownMethod",
           |				"description": "Missing capability(ies): com:linagora:params:jmap:settings"
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin))

  @Test
  def settingsSetShouldFailWhenWrongAccountId(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "wrongAccountId",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"tdrive.attachment.import.enabled": "true",
           |							"firebase.enabled": "true"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("", jsonEquals(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [
           |    ["error", {
           |      "type": "accountNotFound"
           |    }, "c1"]
           |  ]
           |}""".stripMargin))

  @Test
  def fullResetShouldInsertNewSettings(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			}, "c1"
           |		],
           |		[
           |			"Settings/get", {
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"ids": ["singleton"]
           |			}, "c2"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("", jsonEquals(
        s"""{
           |	"sessionState": "${SESSION_STATE.value}",
           |	"methodResponses": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"oldState": "$${json-unit.ignore}",
           |				"newState": "$${json-unit.ignore}",
           |				"updated": {
           |					"singleton": {}
           |				}
           |			},
           |			"c1"
           |		],
           |		[
           |			"Settings/get",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"state": "$${json-unit.ignore}",
           |				"list": [{
           |					"id": "singleton",
           |					"settings": {
           |						"key1": "value1",
           |						"key2": "value2"
           |					}
           |				}],
           |				"notFound": []
           |			},
           |			"c2"
           |		]
           |	]
           |}""".stripMargin))

  @Test
  def fullResetShouldPerformFullUpdateAndOverrideExistingSettings(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[JmapSettingsProbe])
      .reset(BOB, Map(("toBeOverrideKey", "toBeOverrideValue")))

    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			}, "c1"
           |		],
           |		[
           |			"Settings/get", {
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"ids": ["singleton"]
           |			}, "c2"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("", jsonEquals(
        s"""{
           |	"sessionState": "${SESSION_STATE.value}",
           |	"methodResponses": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"oldState": "$${json-unit.ignore}",
           |				"newState": "$${json-unit.ignore}",
           |				"updated": {
           |					"singleton": {}
           |				}
           |			},
           |			"c1"
           |		],
           |		[
           |			"Settings/get",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"state": "$${json-unit.ignore}",
           |				"list": [{
           |					"id": "singleton",
           |					"settings": {
           |						"key1": "value1",
           |						"key2": "value2"
           |					}
           |				}],
           |				"notFound": []
           |			},
           |			"c2"
           |		]
           |	]
           |}""".stripMargin))
  }

  @Test
  def settingsSetShouldReturnCorrectStatesAfterSuccessUpdate(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[JmapSettingsProbe])
      .reset(BOB, Map(("toBeOverrideKey", "toBeOverrideValue")))

    val beforeSettingsSetState: UuidState = server.getProbe(classOf[JmapSettingsProbe])
      .getLatestState(BOB)

    val response: JsonPath = `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .jsonPath()

    val afterSettingsSetState: UuidState = server.getProbe(classOf[JmapSettingsProbe])
      .getLatestState(BOB)

    assertThat(response.getString("methodResponses[0][1].oldState")).isEqualTo(beforeSettingsSetState.serialize)
    assertThat(response.getString("methodResponses[0][1].newState")).isEqualTo(afterSettingsSetState.serialize)
  }

  @Test
  def settingsSetShouldReturnCorrectStatesAfterFailureUpdate(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[JmapSettingsProbe])
      .reset(BOB, Map(("toBeOverrideKey", "toBeOverrideValue")))

    val beforeSettingsSetState: UuidState = server.getProbe(classOf[JmapSettingsProbe])
      .getLatestState(BOB)

    val response: JsonPath = `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton1": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .jsonPath()

    val afterSettingsSetState: UuidState = server.getProbe(classOf[JmapSettingsProbe])
      .getLatestState(BOB)

    assertThat(response.getString("methodResponses[0][1].oldState")).isEqualTo(beforeSettingsSetState.serialize)
    assertThat(response.getString("methodResponses[0][1].newState")).isEqualTo(afterSettingsSetState.serialize)
  }

  @Test
  def settingsSetShouldReturnInitialStatesByDefault(): Unit = {
    val failureResponse: JsonPath = `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"invalidSingleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .jsonPath()

    assertThat(failureResponse.getString("methodResponses[0][1].oldState")).isEqualTo(JmapSettingsStateFactory.INITIAL.serialize)
    assertThat(failureResponse.getString("methodResponses[0][1].newState")).isEqualTo(JmapSettingsStateFactory.INITIAL.serialize)
  }

  @Test
  def settingsSetWithWrongSingletonIdShouldFail(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton1": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           |	"Settings/set",
           |	{
           |		"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |		"oldState": "$${json-unit.ignore}",
           |		"newState": "$${json-unit.ignore}",
           |		"notUpdated": {
           |			"singleton1": {
           |				"type": "invalidArguments",
           |				"description": "id singleton1 must be singleton"
           |			}
           |		}
           |	},
           |	"c1"
           |]""".stripMargin))

  @Test
  def settingsSetWithInvalidSettingKeyShouldFail(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"invalid/setting/key": "value1"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           |	"error",
           |	{
           |		"type": "invalidArguments",
           |		"description": "'/update/singleton/settings/invalid/setting/key' property is not valid: Predicate failed: 'invalid/setting/key' contains some invalid characters. Should be [#a-zA-Z0-9-_#.] and no longer than 255 chars."
           |	},
           |	"c1"
           |]""".stripMargin))

  @Test
  def shouldFailWhenCreate(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"create": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           |	"Settings/set",
           |	{
           |		"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |		"oldState": "$${json-unit.ignore}",
           |		"newState": "$${json-unit.ignore}",
           |		"notCreated": {
           |			"singleton": {
           |				"type": "invalidArguments",
           |				"description": "'create' is not supported on singleton objects"
           |			}
           |		}
           |	},
           |	"c1"
           |]""".stripMargin))

  @Test
  def shouldFailWhenDestroy(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"destroy": ["singleton"]
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           |	"Settings/set",
           |	{
           |		"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |		"oldState": "$${json-unit.ignore}",
           |		"newState": "$${json-unit.ignore}",
           |		"notDestroyed": {
           |			"singleton": {
           |				"type": "invalidArguments",
           |				"description": "'destroy' is not supported on singleton objects"
           |			}
           |		}
           |	},
           |	"c1"
           |]""".stripMargin))

  @Test
  def mixedCaseCreateAndUpdateAndDestroy(): Unit =
    `given`
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |				"create": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				},
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				},
           |				"destroy": ["singleton"]
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           | 	"Settings/set",
           | 	{
           | 		"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           | 		"oldState": "$${json-unit.ignore}",
           | 		"newState": "$${json-unit.ignore}",
           | 		"updated": {
           | 			"singleton": {}
           | 		},
           | 		"notCreated": {
           | 			"singleton": {
           | 				"type": "invalidArguments",
           | 				"description": "'create' is not supported on singleton objects"
           | 			}
           | 		},
           | 		"notDestroyed": {
           | 			"singleton": {
           | 				"type": "invalidArguments",
           | 				"description": "'destroy' is not supported on singleton objects"
           | 			}
           | 		}
           | 	},
           | 	"c1"
           | ]""".stripMargin))

  @Test
  def shouldForbiddenDelegation(server: GuiceJamesServer): Unit = {
    val bobAccountId: String = ACCOUNT_ID

    server.getProbe(classOf[DelegationProbe])
      .addAuthorizedUser(BOB, ANDRE)

    `given`
      .auth().basic(ANDRE.asString(), ANDRE_PASSWORD)
      .body(
        s"""{
           |	"using": ["urn:ietf:params:jmap:core", "com:linagora:params:jmap:settings"],
           |	"methodCalls": [
           |		[
           |			"Settings/set",
           |			{
           |				"accountId": "$bobAccountId",
           |				"update": {
           |					"singleton": {
           |						"settings": {
           |							"key1": "value1",
           |							"key2": "value2"
           |						}
           |					}
           |				}
           |			},
           |			"c1"
           |		]
           |	]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .body("methodResponses[0]", jsonEquals(
        s"""[
           |	"error",
           |	{
           |		"type": "forbidden",
           |		"description": "Access to other accounts settings is forbidden"
           |	},
           |	"c1"
           |]""".stripMargin))
  }
}