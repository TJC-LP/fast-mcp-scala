package com.tjclp.fastmcp.core.skills

/** Extension-based `mimeType` defaults for skill files. An author can always override per file
  * (`SkillFile.text(..., mimeType = Some(...))`); these are the values used when they do not.
  */
object SkillMimeTypes:

  private val byExtension: Map[String, String] = Map(
    "md" -> "text/markdown",
    "markdown" -> "text/markdown",
    "txt" -> "text/plain",
    "json" -> "application/json",
    "yaml" -> "application/yaml",
    "yml" -> "application/yaml",
    "toml" -> "application/toml",
    "xml" -> "application/xml",
    "html" -> "text/html",
    "htm" -> "text/html",
    "css" -> "text/css",
    "csv" -> "text/csv",
    "tsv" -> "text/tab-separated-values",
    "py" -> "text/x-python",
    "sh" -> "application/x-sh",
    "bash" -> "application/x-sh",
    "js" -> "text/javascript",
    "mjs" -> "text/javascript",
    "ts" -> "text/typescript",
    "scala" -> "text/x-scala",
    "sc" -> "text/x-scala",
    "java" -> "text/x-java-source",
    "rb" -> "text/x-ruby",
    "go" -> "text/x-go",
    "rs" -> "text/x-rust",
    "sql" -> "application/sql",
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "svg" -> "image/svg+xml",
    "webp" -> "image/webp",
    "pdf" -> "application/pdf",
    "zip" -> "application/zip",
    "gz" -> "application/gzip",
    "wasm" -> "application/wasm"
  )

  val TextDefault = "text/plain"
  val BinaryDefault = "application/octet-stream"

  /** Default for a file at `relativePath`; `isText` decides the fallback when the extension is
    * unknown.
    */
  def forPath(relativePath: String, isText: Boolean): String =
    val name = relativePath.substring(relativePath.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    val ext = if dot >= 0 && dot < name.length - 1 then name.substring(dot + 1).toLowerCase else ""
    byExtension.getOrElse(ext, if isText then TextDefault else BinaryDefault)

  /** Extensions the JVM directory loader treats as text when the bytes are valid UTF-8. */
  val TextExtensions: Set[String] = byExtension.collect {
    case (ext, mime)
        if mime.startsWith("text/") || mime == "application/json" ||
          mime == "application/yaml" || mime == "application/toml" || mime == "application/xml" ||
          mime == "application/sql" || mime == "application/x-sh" || mime == "image/svg+xml" =>
      ext
  }.toSet
