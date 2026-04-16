package com.tjclp.fastmcp.server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*

import com.tjclp.fastmcp.server.McpContext

/** Describes one argument for a resource template placeholder. */
case class ResourceArgument(
    name: String,
    description: Option[String],
    required: Boolean = true
)

case class ResourceDefinition(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String] = Some("text/plain"),
    isTemplate: Boolean = false,
    arguments: Option[List[ResourceArgument]] = None
)

/** Function type for resource handlers */
type ResourceHandler = () => ZIO[Any, Throwable, String | Array[Byte]]

/** Function type for resource template handlers */
type ResourceTemplateHandler = Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]

/** Manager for MCP resources */
class ResourceManager extends Manager[ResourceDefinition]:
  private val staticResources = new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler)]()
  private val templateResources =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceTemplateHandler)]()

  def addStaticResource(
      uri: String,
      handler: ResourceHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      staticResources.put(uri, (definition, handler))
      ()
    }

  def addTemplateResource(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      templateResources.put(uriPattern, (definition, handler))
      ()
    }

  override def listDefinitions(): List[ResourceDefinition] =
    (staticResources.values().asScala.map(_._1) ++
      templateResources.values().asScala.map(_._1)).toList

  def listStaticResources(): List[ResourceDefinition] =
    staticResources.values().asScala.map(_._1).toList

  def listTemplateResources(): List[ResourceDefinition] =
    templateResources.values().asScala.map(_._1).toList

  def getStaticResourceHandler(uri: String): Option[ResourceHandler] =
    Option(staticResources.get(uri)).map(_._2)

  /** Alias used by FastMcpServer */
  def getResourceHandler(uri: String): Option[ResourceHandler] = getStaticResourceHandler(uri)

  def getTemplateResourceHandler(uriPattern: String): Option[ResourceTemplateHandler] =
    Option(templateResources.get(uriPattern)).map(_._2)

  /** Alias used by FastMcpServer */
  def getTemplateHandler(uriPattern: String): Option[ResourceTemplateHandler] = getTemplateResourceHandler(uriPattern)

  def getResourceDefinition(uri: String): Option[ResourceDefinition] =
    Option(staticResources.get(uri)).map(_._1)

  def listTemplateDefinitions(): List[ResourceDefinition] = listTemplateResources()

  /** Extract parameters from a URI matching a template pattern */
  def extractTemplateParams(
      template: String,
      uri: String
  ): Option[Map[String, String]] =
    val paramNames = """\{(\w+)\}""".r.findAllMatchIn(template).map(_.group(1)).toList
    // Split template on placeholders, quote each literal segment, then rejoin with capture groups
    val segments = """\{\w+\}""".r.split(template).map(Regex.quote).toList
    val regexStr = segments
      .zipAll(paramNames.map(_ => "([^/]+)"), "", "")
      .flatMap { case (seg, cap) => List(seg, cap) }
      .mkString
    val regex = new Regex(s"^$regexStr$$")
    regex.findFirstMatchIn(uri).map { m =>
      paramNames.zipWithIndex.map { case (name, idx) =>
        name -> m.group(idx + 1)
      }.toMap
    }

  def readResource(
      uri: String,
      context: Option[McpContext]
  ): ZIO[Any, Throwable, String | Array[Byte]] =
    getStaticResourceHandler(uri) match
      case Some(handler) => handler()
      case None =>
        val templateMatch = templateResources
          .entrySet()
          .asScala
          .flatMap { entry =>
            extractTemplateParams(entry.getKey, uri).map(params => (entry.getValue._2, params))
          }
          .headOption

        templateMatch match
          case Some((handler, params)) => handler(params)
          case None =>
            ZIO.fail(
              new ResourceNotFoundError(s"Resource '$uri' not found")
            )

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ResourceError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class ResourceNotFoundError(message: String) extends ResourceError(message)
