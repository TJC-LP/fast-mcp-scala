package com.tjclp.fastmcp
package facades
package runtime

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Facade for the Web-Standard `Response` constructor — used to build responses in stateless HTTP
  * mode when we short-circuit error paths (405 for disallowed GET/DELETE, 400 when the client sends
  * an unrecognized session id, etc.).
  */
@js.native
@JSGlobal("Response")
class WebResponse(
    body: js.UndefOr[String] = js.undefined,
    init: js.UndefOr[WebResponseInit] = js.undefined
) extends js.Object

trait WebResponseInit extends js.Object:
  val status: js.UndefOr[Int]
  val statusText: js.UndefOr[String]
  val headers: js.UndefOr[js.Dictionary[String]]

object WebResponseInit:

  def apply(status: Int, headers: Map[String, String] = Map.empty): WebResponseInit =
    js.Dynamic
      .literal(
        status = status,
        headers = js.Dictionary[String](headers.toSeq*)
      )
      .asInstanceOf[WebResponseInit]
