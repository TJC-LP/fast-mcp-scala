package fastmcp.macros

import scala.annotation.StaticAnnotation

/**
 * An annotation for marking methods that should have their JSON schemas
 * automatically generated at compile time.
 */
class InferSchema extends StaticAnnotation