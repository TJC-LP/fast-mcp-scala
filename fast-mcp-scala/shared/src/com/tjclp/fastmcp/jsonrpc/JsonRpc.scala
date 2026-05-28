package com.tjclp.fastmcp.jsonrpc

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.Protocol

/** A JSON-RPC 2.0 request/response id. The spec allows `string | number`; we keep numbers as `Long`
  * (the spec discourages fractional ids) and preserve the original kind so responses echo the
  * client's id shape exactly.
  */
enum RequestId:
  case StrId(value: String)
  case NumId(value: Long)

object RequestId:

  given JsonCodec[RequestId] = JsonCodec(
    JsonEncoder[Json].contramap[RequestId] {
      case StrId(s) => Json.Str(s)
      case NumId(n) => Json.Num(BigDecimal(n))
    },
    JsonDecoder[Json].mapOrFail {
      case Json.Str(s) => Right(StrId(s))
      // Json.Num wraps java.math.BigDecimal — wrap in scala BigDecimal for isWhole/toLong.
      case Json.Num(n) if BigDecimal(n).isWhole => Right(NumId(BigDecimal(n).toLong))
      case other => Left(s"JSON-RPC id must be a string or whole number, got: $other")
    }
  )

/** A JSON-RPC 2.0 error object (the `error` member of an error response). */
case class JsonRpcErrorObject(code: Int, message: String, data: Option[Json] = None)

object JsonRpcErrorObject:
  given JsonCodec[JsonRpcErrorObject] = DeriveJsonCodec.gen[JsonRpcErrorObject]

/** The JSON-RPC 2.0 message ADT.
  *
  * Wire discrimination is *structural*, not tag-based: a message with `method` + `id` is a request,
  * `method` without `id` is a notification, `result` is a success response, `error` is an error
  * response. We hand-roll the codec to honor that (and to emit `jsonrpc: "2.0"` and omit absent
  * `params`/`data` precisely).
  *
  * Batching was dropped from the spec at 2025-06-18, so there is no array case.
  */
sealed trait JsonRpcMessage

object JsonRpcMessage:

  /** An inbound or server-initiated request expecting a response. */
  case class Request(id: RequestId, method: String, params: Option[Json] = None)
      extends JsonRpcMessage

  /** A fire-and-forget notification (no `id`, no response). */
  case class Notification(method: String, params: Option[Json] = None) extends JsonRpcMessage

  /** A successful response carrying a result. */
  case class Success(id: RequestId, result: Json) extends JsonRpcMessage

  /** An error response. `id` is optional: a parse error before the id is known carries `null`. */
  case class Failure(id: Option[RequestId], error: JsonRpcErrorObject) extends JsonRpcMessage

  private val V = Protocol.JsonRpcVersion

  given JsonEncoder[JsonRpcMessage] = JsonEncoder[Json].contramap { msg =>
    val base = List("jsonrpc" -> Json.Str(V))
    val fields: List[(String, Json)] = msg match
      case Request(id, method, params) =>
        base ++ List("id" -> id.toJsonAST.toOption.get, "method" -> Json.Str(method)) ++
          params.map("params" -> _).toList
      case Notification(method, params) =>
        base ++ List("method" -> Json.Str(method)) ++ params.map("params" -> _).toList
      case Success(id, result) =>
        base ++ List("id" -> id.toJsonAST.toOption.get, "result" -> result)
      case Failure(id, error) =>
        val idJson = id.flatMap(_.toJsonAST.toOption).getOrElse(Json.Null)
        base ++ List("id" -> idJson, "error" -> error.toJsonAST.toOption.get)
    Json.Obj(fields*)
  }

  given JsonDecoder[JsonRpcMessage] = JsonDecoder[Json].mapOrFail {
    case Json.Obj(fields) =>
      val m = fields.toMap
      val hasId = m.contains("id") && m.get("id").exists(_ != Json.Null)
      (m.get("method"), m.get("result"), m.get("error")) match
        case (Some(Json.Str(method)), _, _) =>
          val params = m.get("params")
          if hasId then m("id").as[RequestId].map(Request(_, method, params))
          else Right(Notification(method, params))
        case (_, Some(result), _) =>
          m.get("id")
            .toRight("JSON-RPC success response missing `id`")
            .flatMap(_.as[RequestId])
            .map(Success(_, result))
        case (_, _, Some(err)) =>
          val idOpt =
            if hasId then m("id").as[RequestId].toOption else None
          err.as[JsonRpcErrorObject].map(Failure(idOpt, _))
        case _ =>
          Left("Not a valid JSON-RPC message: missing `method`, `result`, and `error`")
    case other => Left(s"JSON-RPC message must be a JSON object, got: $other")
  }

  given JsonCodec[JsonRpcMessage] =
    JsonCodec(summon[JsonEncoder[JsonRpcMessage]], summon[JsonDecoder[JsonRpcMessage]])
