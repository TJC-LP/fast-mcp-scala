package fastmcp.macros

import scala.quoted.*
import scala.annotation.tailrec
import java.lang.reflect.Method

object MapToFunctionMacro:

  /**
   * Given a function `f`, returns a new function: `Map[String, Any] => R`,
   * where R is the return type of the original function.
   * The returned function will look up each parameter by name in the `Map`,
   * cast it to the correct type, and invoke `f` with those arguments.
   *
   * Example:
   * {{{
   *   def createUser(name: String, age: Int) = s"User($name, $age)"
   *   val fn = callByMap(createUser) // fn: Map[String, Any] => String
   *   System.err.println(fn(Map("name" -> "Alice", "age" -> 42))) // => "User(Alice, 42)"
   * }}}
   */
  transparent inline def callByMap[F](inline f: F): Any =
    ${ callByMapImpl('f) }

  def callByMapImpl[F: Type](f: Expr[F])(using q: Quotes): Expr[Any] =
    import q.reflect.*

    // Helper class to store parameter information
    case class ParamInfo(name: String, tpe: TypeRepr)

    // Extract the parameters and return type from the function
    @tailrec
    def extractParamsAndReturnType(term: Term): (List[ParamInfo], TypeRepr) =
      val tpe = term.tpe.widen

      tpe match
        case mt: MethodType =>
          val params = mt.paramNames.zip(mt.paramTypes).map {
            case (name, tpe) => ParamInfo(name, tpe)
          }
          (params, mt.resType)

        case pt: PolyType =>
          extractParamsAndReturnType(term.appliedToTypes(pt.paramNames.map(_ => TypeRepr.of[Any])))

        case atpe @ AppliedType(base, args) if base.typeSymbol.fullName.startsWith("scala.Function") =>
          val paramTypes = args.init // Last type is return type
          val returnType = args.last
          val params = paramTypes.indices.map(i =>
            ParamInfo(s"arg$i", paramTypes(i))
          ).toList
          (params, returnType)

        case _ =>
          report.errorAndAbort(s"Couldn't extract parameters from function: ${term.show}, type: ${tpe.show}")

    // Try to extract real parameter names from method references
    @tailrec
    def tryGetRealParamNames(term: Term): Option[List[String]] = term match
      case Inlined(_, _, inner) => tryGetRealParamNames(inner)
      case Block(_, expr)      => tryGetRealParamNames(expr)
      case ident @ Ident(_) if ident.symbol.isDefDef =>
        ident.symbol.paramSymss.headOption.map(_.map(_.name))
      case select @ Select(_, _) if select.symbol.isDefDef =>
        select.symbol.paramSymss.headOption.map(_.map(_.name))
      case closure @ Closure(methRef, _) if methRef.symbol.isDefDef =>
        methRef.symbol.paramSymss.headOption.map(_.map(_.name))
      case _ => None

    // Extract parameters and return type
    val (params, returnTypeRepr) = extractParamsAndReturnType(f.asTerm)

    // Try to get real parameter names
    val realNames = tryGetRealParamNames(f.asTerm)
    val namedParams = realNames match
      case Some(names) if names.length == params.length =>
        names.zip(params).map { case (name, param) => param.copy(name = name) }
      case _ => params

    // Create a lambda expression with the correct return type
    returnTypeRepr.asType match
      case '[returnType] =>
        '{
          (map: Map[String, Any]) =>
            // Store the function value
            val fnValue = $f

            // Build the (name -> typeString) list, marking Scala 3 enums with "enum <FQN>"
            val paramInfo: List[(String, String)] = ${
              Expr(namedParams.map { p =>
                val tpeSym = p.tpe.typeSymbol
                val isEnum = tpeSym.flags.is(Flags.Enum)
                val enumFqn = tpeSym.fullName
                val typeString =
                  if isEnum then s"enum $enumFqn"
                  else p.tpe.show
                (p.name -> typeString)
              })
            }

            // Get the runtime arguments from the map
            val args = paramInfo.map { case (name, tpeStr) =>
              map.get(name) match
                case Some(value) =>
                  try 
                    // Improved enum handling for Scala 3 enums
                    if (tpeStr.contains("enum ")) {
                      try {
                        val result = handleEnumConversion(tpeStr, value)
                        if (result != null) {
                          result  // Successfully converted enum value
                        } else {
                          System.err.println(s"WARNING: Using original value because enum conversion failed: $value")
                          value  // Fallback to original value if conversion fails
                        }
                      } catch {
                        case e: Exception =>
                          System.err.println(s"ERROR in enum conversion: ${e.getMessage}")
                          // Default to the original value in case of error
                          value
                      }
                    }
                    else value
                  catch
                    case e: ClassCastException =>
                      throw new RuntimeException("Parameter '" + name + "' has incorrect type. Expected: " + tpeStr + ", got: " + value.getClass.getName, e)
                    case e: Exception =>
                      throw new RuntimeException("Error converting parameter '" + name + "': " + e.getMessage, e)
                case None =>
                  // Check if the parameter type is Option, in which case we can use None
                  if (tpeStr.startsWith("Option["))
                    None
                  else
                    throw new RuntimeException("Missing required parameter: '" + name + "'")
            }

            // Use reflection to invoke the function with arbitrary number of arguments
            val result = invokeFunctionWithArgs(fnValue, args)
            result.asInstanceOf[returnType]
        }.asExprOf[Map[String, Any] => returnType]

  // Helper method to invoke a function with a list of arguments using reflection
  private def invokeFunctionWithArgs(function: Any, args: List[Any]): Any =
    function match
      // Handle standard Scala functions
      case f: Function0[?] => f.apply()
      case f: Function1[?, ?] => f.asInstanceOf[Function1[Any, Any]].apply(args(0))
      case f: Function2[?, ?, ?] => f.asInstanceOf[Function2[Any, Any, Any]].apply(args(0), args(1))
      case f: Function3[?, ?, ?, ?] => f.asInstanceOf[Function3[Any, Any, Any, Any]].apply(args(0), args(1), args(2))
      case f: Function4[?, ?, ?, ?, ?] => f.asInstanceOf[Function4[Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3))
      case f: Function5[?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4))
      case f: Function6[?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5))
      case f: Function7[?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6))
      case f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7))
      case f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8))
      case f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9))
      case f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10))
      case f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11))
      case f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12))
      case f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13))
      case f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14))
      case f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15))
      case f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16))
      case f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17))
      case f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18))
      case f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19))
      case f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function21[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20))
      case f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function22[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20), args(21))

      // For methods with more than 22 parameters or other types, use Java reflection
      case _ =>
        try
          // Convert to method reference if needed
          val methodObj = function.getClass.getMethods.find(_.getName == "apply").getOrElse {
            throw new RuntimeException("Could not find method to invoke on " + function.getClass.getName)
          }
          // Invoke the method with the arguments
          methodObj.invoke(function, args.map(_.asInstanceOf[Object]).toArray*)
        catch
          case e: Exception =>
            throw new RuntimeException("Failed to invoke function: " + e.getMessage, e)
            
  /**
   * Helper method to convert a string value to an Enum case
   * Uses reflection to find the valueOf method or values method on the enum class
   * Enhanced to handle Scala 3 enums
   */
  private def convertToEnum(enumClassName: String, value: String): Any =
    try
      System.err.println(s"Attempting to convert string '$value' to enum: $enumClassName")
      
      // Get the enum class - handle various forms including nested classes
      val enumClass = loadEnumClass(enumClassName)
      System.err.println(s"Found enum class: ${enumClass.getName}")
      
      // Find the companion object for Scala enums
      val (companionClass, companionInstance) = getCompanionObject(enumClass)
      
      // Strategy 1: Try to use the Scala 3 enum case-insensitive matching
      val result = try {
        // Get all enum values
        val enumValues = getEnumValues(enumClass, companionClass, companionInstance)
        
        // Try to find a matching enum value by name (case-insensitive)
        val matchByName = enumValues.find { enumCase =>
          val caseName = enumCase.toString.toLowerCase
          val valueLower = value.toLowerCase
          caseName == valueLower || caseName.replace("_", "").toLowerCase == valueLower
        }
        
        matchByName.getOrElse(null)
      } catch {
        case e: Exception => 
          System.err.println(s"Case-insensitive enum matching failed: ${e.getMessage}")
          null
      }
      
      if (result != null) {
        System.err.println(s"Found enum case by case-insensitive matching: $result")
        return result
      }
      
      // Strategy 2: Try valueOf method on companion object (Scala 3 style)
      try {
        if (companionInstance != null) {
          val valueOfMethod = companionClass.getMethod("valueOf", classOf[String])
          val enumValue = valueOfMethod.invoke(companionInstance, value)
          System.err.println(s"Found enum case using valueOf: $enumValue")
          return enumValue
        }
      } catch {
        case e: Exception => 
          System.err.println(s"valueOf method failed: ${e.getMessage}")
      }
      
      // Strategy 3: Try direct field access
      try {
        val field = enumClass.getField(value)
        val enumValue = field.get(null)
        System.err.println(s"Found enum case by field access: $enumValue")
        return enumValue
      } catch {
        case e: Exception =>
          System.err.println(s"Field access failed: ${e.getMessage}")
      }
      
      // Strategy 4: Last resort - try if there's a fromName method
      try {
        if (companionInstance != null) {
          val fromNameMethod = companionClass.getMethod("fromName", classOf[String])
          val enumValue = fromNameMethod.invoke(companionInstance, value)
          System.err.println(s"Found enum case using fromName: $enumValue")
          return enumValue
        }
      } catch {
        case e: Exception =>
          System.err.println(s"fromName method failed: ${e.getMessage}")
      }
      
      // If all strategies fail, throw an exception
      throw new RuntimeException(s"Could not convert '$value' to enum type $enumClassName")
    catch
      case e: Exception =>
        throw new RuntimeException(s"Failed to convert value '$value' to enum type $enumClassName: ${e.getMessage}", e)
        
  /**
   * Helper method to load an enum class, handling various forms of class names
   */
  private def loadEnumClass(enumClassName: String): Class[_] = {
    // Try several strategies to load the class
    val possibleClassNames = List(
      enumClassName,
      enumClassName + "$",  // For companion objects
      enumClassName.replace("$", "."),  // For nested classes
      "scala." + enumClassName  // For scala package
    )
    
    val errors = scala.collection.mutable.ListBuffer[Throwable]()
    
    for (className <- possibleClassNames) {
      try {
        return Class.forName(className)
      } catch {
        case e: ClassNotFoundException => 
          errors += e
          // Continue to next attempt
      }
    }
    
    // Handle special case for nested enums - we might need to modify the class name
    // This matters for ManualServer$TransformationType
    if (enumClassName.contains("$")) {
      val parts = enumClassName.split("\\$", 2)
      if (parts.length == 2) {
        val outerClassNames = List(
          parts(0),
          parts(0) + "$"
        )
        
        // Try loading the outer class first, then access the inner class
        for (outerClassName <- outerClassNames) {
          try {
            val outerClass = Class.forName(outerClassName)
            // Look for the inner class/enum
            val innerClasses = outerClass.getDeclaredClasses
            val matchingInnerClass = innerClasses.find(_.getSimpleName == parts(1))
            
            if (matchingInnerClass.isDefined) {
              return matchingInnerClass.get
            }
          } catch {
            case e: Exception => 
              errors += e
              // Continue to next attempt
          }
        }
      }
    }
    
    // If we get here, all attempts failed
    throw new ClassNotFoundException(s"Could not load enum class: $enumClassName, tried: ${possibleClassNames.mkString(", ")}", 
                                    errors.headOption.orNull)
  }
  
  /**
   * Helper method to get the companion object for a Scala enum class
   */
  private def getCompanionObject(enumClass: Class[_]): (Class[_], Object) = {
    try {
      // For Scala objects, the companion is typically ClassName$
      val companionClassName = if (!enumClass.getName.endsWith("$")) {
        enumClass.getName + "$"
      } else {
        enumClass.getName
      }
      
      try {
        val companionClass = Class.forName(companionClassName)
        // Get the MODULE$ field for the singleton instance
        val moduleField = companionClass.getField("MODULE$")
        val companionInstance = moduleField.get(null)
        (companionClass, companionInstance)
      } catch {
        case e: Exception =>
          System.err.println(s"Failed to get companion object: ${e.getMessage}")
          (enumClass, null)
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to get companion class: ${e.getMessage}")
        (enumClass, null)
    }
  }
  
  /**
   * Helper method to get all enum values from a class
   */
  private def getEnumValues(enumClass: Class[_], companionClass: Class[_], companionInstance: Object): Array[Object] = {
    // Try multiple strategies to get enum values
    
    // Strategy 1: Call values() on the companion object
    try {
      if (companionInstance != null) {
        val valuesMethod = companionClass.getMethod("values")
        return valuesMethod.invoke(companionInstance).asInstanceOf[Array[Object]]
      }
    } catch {
      case e: Exception => 
        System.err.println(s"Failed to get values from companion: ${e.getMessage}")
    }
    
    // Strategy 2: Call static values() method on the enum class
    try {
      val valuesMethod = enumClass.getMethod("values")
      return valuesMethod.invoke(null).asInstanceOf[Array[Object]]
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to get values from enum class: ${e.getMessage}")
    }
    
    // Strategy 3: Get all the static fields of the enum type
    try {
      val fields = enumClass.getFields.filter { field =>
        java.lang.reflect.Modifier.isStatic(field.getModifiers) && 
        field.getType == enumClass
      }
      
      return fields.map(_.get(null)).toArray
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to get values from fields: ${e.getMessage}")
    }
    
    // If all strategies fail, return empty array
    System.err.println("Failed to get enum values using any method")
    Array.empty[Object]
  }
  
  /**
   * Centralized enum conversion method that handles all aspects of converting a value to an enum
   * This is the main entry point for enum conversions used by the mapping logic
   */
  private def handleEnumConversion(tpeStr: String, value: Any): Any = {
    // Extract the full enum class name from the type string
    val enumClassName = tpeStr.trim.replaceAll(".*enum\\s+(\\S+).*", "$1").trim()
    System.err.println(s"[handleEnumConversion] Converting value to enum: $enumClassName (value: $value, type: ${if (value != null) value.getClass.getName else "null"})")
    
    // Skip if the value is already null
    if (value == null) {
      System.err.println("[handleEnumConversion] Value is null, returning null")
      return null
    }
    
    try {
      // First, load the enum class and get its companion object and values
      val enumClass = loadEnumClass(enumClassName)
      val (companionClass, companionInstance) = getCompanionObject(enumClass)
      val enumValues = getEnumValues(enumClass, companionClass, companionInstance)
      
      // Value already has the correct type?
      if (enumClass == value.getClass || enumClass.isInstance(value)) {
        System.err.println(s"[handleEnumConversion] Value already has the correct type: ${value.getClass.getName}")
        return value
      }
      
      // If value is a string, try string-based conversion
      if (value.isInstanceOf[String]) {
        val strValue = value.toString
        System.err.println(s"[handleEnumConversion] String value: '$strValue'")
        
        // Step 1: Try direct case-insensitive name matching with the enum values
        val matchByName = enumValues.find(v => v.toString.equalsIgnoreCase(strValue))
        if (matchByName.isDefined) {
          System.err.println(s"[handleEnumConversion] Found enum by case-insensitive name matching: ${matchByName.get}")
          return matchByName.get
        }
        
        // Step 2: Try valueOf method on companion object
        if (companionInstance != null) {
          try {
            val valueOfMethod = companionClass.getMethod("valueOf", classOf[String])
            val result = valueOfMethod.invoke(companionInstance, strValue)
            System.err.println(s"[handleEnumConversion] Found enum using valueOf: $result")
            return result
          } catch {
            case e: Exception =>
              System.err.println(s"[handleEnumConversion] valueOf failed: ${e.getMessage}")
          }
        }
        
        // Step 3: Try direct field access on the enum class
        try {
          val enumField = enumClass.getField(strValue)
          val result = enumField.get(null)
          System.err.println(s"[handleEnumConversion] Found enum by field access: $result")
          return result
        } catch {
          case e: Exception =>
            System.err.println(s"[handleEnumConversion] Field access failed: ${e.getMessage}")
        }
        
        // Step 4: Try fromName if available
        if (companionInstance != null) {
          try {
            val fromNameMethod = companionClass.getMethod("fromName", classOf[String])
            val result = fromNameMethod.invoke(companionInstance, strValue)
            System.err.println(s"[handleEnumConversion] Found enum using fromName: $result")
            return result
          } catch {
            case e: Exception =>
              System.err.println(s"[handleEnumConversion] fromName failed: ${e.getMessage}")
          }
        }
      }
      
      // If value is an integer, try ordinal-based conversion
      else if (value.isInstanceOf[Int] || value.isInstanceOf[java.lang.Integer]) {
        val ordinal = if (value.isInstanceOf[Int]) value.asInstanceOf[Int] else value.asInstanceOf[java.lang.Integer].intValue()
        System.err.println(s"[handleEnumConversion] Trying ordinal conversion with value: $ordinal")
        
        // Try fromOrdinal method (Scala 3 specific)
        if (companionInstance != null) {
          try {
            val fromOrdinalMethod = companionClass.getMethod("fromOrdinal", classOf[Int])
            val result = fromOrdinalMethod.invoke(companionInstance, Int.box(ordinal))
            System.err.println(s"[handleEnumConversion] Found enum using fromOrdinal: $result")
            return result
          } catch {
            case e: Exception =>
              System.err.println(s"[handleEnumConversion] fromOrdinal failed: ${e.getMessage}")
          }
        }
        
        // Direct array access by ordinal
        if (enumValues.nonEmpty && ordinal >= 0 && ordinal < enumValues.length) {
          System.err.println(s"[handleEnumConversion] Found enum at ordinal $ordinal: ${enumValues(ordinal)}")
          return enumValues(ordinal)
        }
      }
      
      // If value is another enum, try conversion based on name or ordinal
      else if (value.getClass.isEnum) {
        System.err.println(s"[handleEnumConversion] Value is already an enum of type: ${value.getClass.getName}")
        
        // Try converting by name
        return handleEnumConversion(tpeStr, value.toString)
      }
      
      // For any other type, try string conversion as a last resort
      else {
        System.err.println(s"[handleEnumConversion] Using string conversion for type: ${value.getClass.getName}")
        return handleEnumConversion(tpeStr, value.toString)
      }
      
      // If all conversion attempts failed but we have enum values,
      // return the first value as a default
      if (enumValues.nonEmpty) {
        System.err.println(s"[handleEnumConversion] All conversion attempts failed. Using default enum value: ${enumValues(0)}")
        return enumValues(0)
      }
      
      // Complete failure case
      System.err.println("[handleEnumConversion] Could not convert value to enum and no default available")
      return null
    }
    catch {
      case e: Exception =>
        System.err.println(s"[handleEnumConversion] Exception during conversion: ${e.getMessage}")
        e.printStackTrace(System.err)
        null
    }
  }
  
  /**
   * Helper method to convert an integer ordinal to an Enum case
   * Uses reflection to find the values method and get the enum case by index
   * Enhanced to handle Scala 3 enums
   */
  private def convertToEnumByOrdinal(enumClassName: String, ordinal: Int): Any =
    try
      System.err.println(s"Attempting to convert ordinal $ordinal to enum: $enumClassName")
      
      // Get the enum class - handle various forms including nested classes
      val enumClass = loadEnumClass(enumClassName)
      System.err.println(s"Found enum class: ${enumClass.getName}")
      
      // Find the companion object for Scala enums
      val (companionClass, companionInstance) = getCompanionObject(enumClass)
      
      // Strategy 1: Try Scala 3 specific fromOrdinal method on companion object
      try {
        if (companionInstance != null) {
          val fromOrdinalMethod = companionClass.getMethod("fromOrdinal", classOf[Int])
          val enumValue = fromOrdinalMethod.invoke(companionInstance, Int.box(ordinal))
          System.err.println(s"Found enum case using fromOrdinal($ordinal): $enumValue")
          return enumValue
        }
      } catch {
        case e: Exception => 
          System.err.println(s"fromOrdinal method failed: ${e.getMessage}")
      }
      
      // Strategy 2: Get all values and access by ordinal
      try {
        val enumValues = getEnumValues(enumClass, companionClass, companionInstance)
        
        if (enumValues.nonEmpty && ordinal >= 0 && ordinal < enumValues.length) {
          val enumValue = enumValues(ordinal)
          System.err.println(s"Found enum case at ordinal $ordinal: $enumValue")
          return enumValue
        } else {
          System.err.println(s"Invalid ordinal $ordinal - valid range is 0 to ${enumValues.length - 1}")
        }
      } catch {
        case e: Exception =>
          System.err.println(s"Accessing enum by ordinal failed: ${e.getMessage}")
      }
      
      // Strategy 3: Try converting ordinal to string and using string conversion
      try {
        val ordinalStr = ordinal.toString
        System.err.println(s"Falling back to string conversion for ordinal: $ordinal")
        return convertToEnum(enumClassName, ordinalStr)
      } catch {
        case e: Exception =>
          System.err.println(s"String fallback for ordinal failed: ${e.getMessage}")
      }
      
      // If all strategies fail
      throw new RuntimeException(s"Failed to convert ordinal $ordinal to enum type $enumClassName")
    catch
      case e: Exception =>
        throw new RuntimeException(s"Failed to convert ordinal $ordinal to enum type $enumClassName: ${e.getMessage}", e)