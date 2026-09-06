package com.tjclp.fastmcp.jsonrpc

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.codec.JsonLimits
import com.tjclp.fastmcp.core.Protocol

/** Linear field lookup over a decoded JSON object. Last occurrence wins — the semantics
  * `Chunk.toMap` gave duplicate keys — so behaviour is unchanged while no HashMap is ever built
  * from client-chosen keys (N hash-colliding keys used to cost O(N²) comparisons per `toMap`).
  */
private[fastmcp] object JsonFields:

  def get(fields: Chunk[(String, Json)], key: String): Option[Json] =
    val idx = fields.lastIndexWhere(_._1 == key)
    if idx < 0 then None else Some(fields(idx)._2)

  def get(json: Json, key: String): Option[Json] = json match
    case Json.Obj(fields) => get(fields, key)
    case _ => None

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
      case Json.Num(n) =>
        longId(n)
          .map(NumId(_))
          .toRight("JSON-RPC id must be a string or a whole number in 64-bit range")
      case other =>
        Left(s"JSON-RPC id must be a string or whole number, got: ${JsonLimits.typeName(other)}")
    }
  )

  /** A numeric id is accepted only if it is whole and fits a `Long` — an out-of-range id would
    * otherwise be wrapped by `toLong` and the reply would echo a different id than the client sent.
    * Cheap on any exponent: a negative scale below -19 means |n| >= 10^20 (past `Long`), so
    * `longValueExact`'s `setScale(0)` (which would materialise 10^|scale|) is never reached for a
    * huge exponent; for the remaining scales it is O(digits), and zio-json caps the mantissa.
    */
  private def longId(n: java.math.BigDecimal): Option[Long] =
    if n.signum == 0 then Some(0L)
    else if n.scale < -19 then None
    else
      try Some(n.longValueExact)
      catch case _: ArithmeticException => None

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
  *
  * The decoder looks the envelope members up by linear scan ([[JsonFields]]); unknown top-level
  * fields are tolerated and never materialised into a map.
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

  /** A parseable JSON object that violates JSON-RPC 2.0 structure (`id: null` on a request, wrong
    * or missing `jsonrpc`, non-string `method`, fractional id, none of method/result/error). Never
    * sent by this implementation — it exists so the dispatch layer can answer `-32600 Invalid
    * Request` with the offender's id echoed, instead of misclassifying (a null-id request used to
    * decode as a droppable [[Notification]]) or degrading to `-32700`.
    */
  case class Invalid(id: Option[RequestId], reason: String) extends JsonRpcMessage

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
      case Invalid(id, reason) =>
        // Outbound Invalid is a programming error, but the encoder must stay total: render it as
        // the -32600 error response it represents.
        val idJson = id.flatMap(_.toJsonAST.toOption).getOrElse(Json.Null)
        val err = JsonRpcErrorObject(-32600, s"Invalid Request: $reason")
        base ++ List("id" -> idJson, "error" -> err.toJsonAST.toOption.get)
    Json.Obj(fields*)
  }

  given JsonDecoder[JsonRpcMessage] = JsonDecoder[Json].mapOrFail {
    case Json.Obj(fields) =>
      def field(key: String): Option[Json] = JsonFields.get(fields, key)
      val idField = field("id")
      // Best-effort id for echoing back on -32600 (null / fractional ids echo as null).
      val idOpt = idField.filter(_ != Json.Null).flatMap(_.as[RequestId].toOption)
      def invalid(reason: String): Right[String, JsonRpcMessage] = Right(Invalid(idOpt, reason))
      if !field("jsonrpc").contains(Json.Str(V)) then
        invalid(s"""missing or invalid `jsonrpc` version (expected "$V")""")
      else
        (field("method"), field("result"), field("error")) match
          case (Some(Json.Str(method)), _, _) =>
            val params = field("params")
            idField match
              case None => Right(Notification(method, params))
              case Some(Json.Null) => invalid("request `id` must not be null")
              case Some(rawId) =>
                rawId.as[RequestId] match
                  case Right(id) => Right(Request(id, method, params))
                  case Left(reason) => invalid(reason)
          case (Some(_), _, _) =>
            invalid("`method` must be a string")
          case (_, Some(result), _) =>
            idField
              .toRight("JSON-RPC success response missing `id`")
              .flatMap(_.as[RequestId])
              .map(Success(_, result))
          case (_, _, Some(err)) =>
            err.as[JsonRpcErrorObject].map(Failure(idOpt, _))
          case _ =>
            invalid("missing `method`, `result`, and `error`")
    case other =>
      Left(s"JSON-RPC message must be a JSON object, got: ${JsonLimits.typeName(other)}")
  }

  given JsonCodec[JsonRpcMessage] =
    JsonCodec(summon[JsonEncoder[JsonRpcMessage]], summon[JsonDecoder[JsonRpcMessage]])
