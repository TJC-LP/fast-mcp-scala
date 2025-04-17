package fastmcp.runtime

import java.lang.invoke.{MethodHandles, MethodType}
import scala.jdk.CollectionConverters.*

/** Runtime utility for resolving function references and invoking methods. This class is extracted
  * from the macro utilities to allow for runtime resolution without requiring macro expansion.
  */
object RefResolver:

  /** Invoke a function object with a list of arguments. Uses MethodHandle API for more efficient
    * function invocation.
    *
    * @param fun
    *   The function object to invoke
    * @param args
    *   The arguments to pass to the function
    * @return
    *   The result of the function invocation
    * @throws IllegalArgumentException
    *   if the function cannot be invoked with the given arguments
    */
  def invokeFunctionWithArgs(fun: Any, args: List[Any]): Any =
    (args.length, fun) match
      case (0, f: Function0[?]) => f()
      case (1, f: Function1[?, ?]) => f.asInstanceOf[Function1[Any, Any]](args.head)
      case (2, f: Function2[?, ?, ?]) => f.asInstanceOf[Function2[Any, Any, Any]](args(0), args(1))
      case (3, f: Function3[?, ?, ?, ?]) =>
        f.asInstanceOf[Function3[Any, Any, Any, Any]](args(0), args(1), args(2))
      case _ => // universal fallback
        val handle = MethodHandles
          .lookup()
          .findVirtual(
            fun.getClass,
            "apply",
            MethodType.genericMethodType(args.length)
          )
        handle.invokeWithArguments((fun :: args).map(_.asInstanceOf[Object]).asJava)
