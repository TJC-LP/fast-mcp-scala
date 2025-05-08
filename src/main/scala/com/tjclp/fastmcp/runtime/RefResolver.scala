package com.tjclp.fastmcp.runtime

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
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
      case (4, f: Function4[?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function4[Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3))
      case (5, f: Function5[?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4)
        )
      case (6, f: Function6[?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5)
        )
      case (7, f: Function7[?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6)
        )
      case (8, f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7)
        )
      case (9, f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8)
        )
      case (10, f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9)
        )
      case (11, f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10)
        )
      case (12, f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11)
        )
      case (13, f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12)
        )
      case (14, f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13)
        )
      case (15, f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14)
        )
      case (16, f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function16[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15)
        )
      case (17, f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function17[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16)
        )
      case (18, f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function18[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17)
        )
      case (19, f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function19[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18)
        )
      case (20, f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function20[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19)
        )
      case (21, f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function21[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20)
        )
      case (
            22,
            f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          ) =>
        f.asInstanceOf[Function22[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20),
          args(21)
        )
      case _ => // universal fallback
        val handle = MethodHandles
          .lookup()
          .findVirtual(
            fun.getClass,
            "apply",
            MethodType.genericMethodType(args.length)
          )
        handle.invokeWithArguments((fun :: args).map(_.asInstanceOf[Object]).asJava)
