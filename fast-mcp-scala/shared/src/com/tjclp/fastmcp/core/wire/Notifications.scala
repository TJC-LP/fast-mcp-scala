package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.ProgressToken

/** Notification method-name constants and `params` shapes.
  *
  * Notifications carry no `id` and expect no response. The method names are centralized here so the
  * router and emit sites agree on the wire strings.
  */
object NotificationMethods:
  val Initialized: String = "notifications/initialized"
  val Cancelled: String = "notifications/cancelled"
  val Progress: String = "notifications/progress"
  val Message: String = "notifications/message"
  val ResourcesUpdated: String = "notifications/resources/updated"
  val ResourcesListChanged: String = "notifications/resources/list_changed"
  val ToolsListChanged: String = "notifications/tools/list_changed"
  val PromptsListChanged: String = "notifications/prompts/list_changed"

/** `notifications/cancelled` params. `requestId` is the id of the in-flight request to abort; the
  * M4 router cancels the matching fiber. Task cancellation uses `tasks/cancel` instead, so
  * `requestId` targets non-task requests only.
  */
case class CancelledNotificationParams(
    requestId: Option[Json] = None,
    reason: Option[String] = None
)

object CancelledNotificationParams:
  given JsonCodec[CancelledNotificationParams] = DeriveJsonCodec.gen[CancelledNotificationParams]

/** `notifications/progress` params. `progressToken` correlates with the originating request;
  * `progress` increases monotonically; `total` is the denominator when known.
  */
case class ProgressNotificationParams(
    progressToken: ProgressToken,
    progress: Double,
    total: Option[Double] = None,
    message: Option[String] = None
)

object ProgressNotificationParams:
  given JsonCodec[ProgressNotificationParams] = DeriveJsonCodec.gen[ProgressNotificationParams]

/** `notifications/resources/updated` params — sent only to clients that subscribed via
  * `resources/subscribe`.
  */
case class ResourceUpdatedNotificationParams(uri: String)

object ResourceUpdatedNotificationParams:

  given JsonCodec[ResourceUpdatedNotificationParams] =
    DeriveJsonCodec.gen[ResourceUpdatedNotificationParams]
