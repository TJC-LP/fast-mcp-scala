package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facade for the TS SDK's `AjvJsonSchemaValidator`. The Scala `JsMcpServer` uses it to
  * validate incoming `tools/call` arguments against the tool's stored `inputSchema` *before*
  * handing off to the decoder — matching the JVM's Jackson-based validation semantics.
  */
@JSImport("@modelcontextprotocol/sdk/validation/ajv.js", "AjvJsonSchemaValidator")
@js.native
class AjvJsonSchemaValidator() extends js.Object:
  def getValidator(schema: js.Any): js.Function1[js.Any, JsonSchemaValidatorResult] = js.native

/** Result shape returned by the validator. `valid: true` ⇒ `data` holds the input (passed through).
  * `valid: false` ⇒ `errorMessage` holds a human-readable description of the failure.
  */
@js.native
trait JsonSchemaValidatorResult extends js.Object:
  val valid: Boolean = js.native
  val data: js.UndefOr[js.Any] = js.native
  val errorMessage: js.UndefOr[String] = js.native
