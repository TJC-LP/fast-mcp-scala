package com.tjclp.fastmcp.server.manager

import scala.jdk.CollectionConverters.*

import io.modelcontextprotocol.spec.McpSchema

/** JVM-only toJava extension for ResourceDefinition. */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
private[fastmcp] object ResourceConversions:

  extension (rd: ResourceDefinition)
    def toJava: McpSchema.Resource | McpSchema.ResourceTemplate =
      if rd.isTemplate then
        val experimentalAnnotationsMap = new java.util.HashMap[String, Object]()
        rd.arguments.foreach { argList =>
          val javaArgsList = argList.map { arg =>
            val argMap = new java.util.HashMap[String, Object]()
            argMap.put("name", arg.name)
            arg.description.foreach(d => argMap.put("description", d))
            argMap.put("required", java.lang.Boolean.valueOf(arg.required))
            argMap.asInstanceOf[Object]
          }.asJava
          experimentalAnnotationsMap.put("fastmcp_resource_arguments", javaArgsList)
        }

        val annotations = new McpSchema.Annotations(null, null)
        // TODO: Revisit if Java SDK adds direct support for experimental data in Annotations

        new McpSchema.ResourceTemplate(
          rd.uri,
          rd.name.orNull,
          rd.description.orNull,
          rd.mimeType.getOrElse("text/plain"),
          annotations
        )
      else
        McpSchema.Resource
          .builder()
          .uri(rd.uri)
          .name(rd.name.orNull)
          .description(rd.description.orNull)
          .mimeType(rd.mimeType.getOrElse("text/plain"))
          .annotations(new McpSchema.Annotations(null, null))
          .build()
