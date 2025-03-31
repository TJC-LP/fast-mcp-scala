package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import zio.*
import zio.json.*

import scala.util.Random

/**
 * A simple example showing how to use FastMCPScala
 */
object SimpleServer extends ZIOAppDefault:
  // Define a JSON codec for the result
  given JsonCodec[CalculatorResult] = DeriveJsonCodec.gen[CalculatorResult]

  override def run =
    // Create a new FastMCPScala server
    val server = FastMCPScala("SimpleCalculator", "0.1.0")

    for {
      // Register a simple calculator tool
      _ <- server.tool(
        name = "calculator",
        description = Some("A simple calculator that performs basic arithmetic operations"),
        handler = args => {
          val operation = args.getOrElse("operation", "add").toString
          val numbers = args.getOrElse("numbers", List(0.0, 0.0)).asInstanceOf[List[Double]]

          val result = operation match {
            case "add" => numbers.sum
            case "multiply" => numbers.product
            case "subtract" => numbers.reduceOption(_ - _).getOrElse(0.0)
            case "divide" => numbers.reduceOption(_ / _).getOrElse(0.0)
            case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")
          }

          val calculatorResult = CalculatorResult(result, operation, numbers)
          ZIO.succeed(calculatorResult)
        }
      )

      // Register a greeting resource
      _ <- server.resource(
        uri = "/greeting",
        name = Some("Greeting"),
        description = Some("Returns a friendly greeting"),
        handler = () => ZIO.succeed("Hello from FastMCPScala!")
      )

      // Register a templated resource for user profiles
      _ <- server.resourceTemplate(
        uriPattern = "/users/{id}/profile",
        name = Some("User Profile"),
        description = Some("Returns information about a user"),
        handler = params => {
          val userId = params.getOrElse("id", "unknown")
          ZIO.succeed(s"Profile for user $userId: Name=User$userId, Age=${Random.nextInt(100)}")
        }
      )

      // Register a simple prompt
      _ <- server.prompt(
        name = "introduction",
        description = Some("Creates an introduction based on a person's name and interests"),
        arguments = Some(List(
          PromptArgument("name", Some("Person's name"), true),
          PromptArgument("interests", Some("Person's interests"), false)
        )),
        handler = args => {
          val name = args.getOrElse("name", "").toString
          val interests = args.getOrElse("interests", "").toString

          val messages = List(
            Message(
              role = Role.User,
              content = TextContent(s"Please introduce $name who likes $interests")
            ),
            Message(
              role = Role.Assistant,
              content = TextContent(
                s"""Meet $name!
                   |
                   |$name is a wonderful person who enjoys $interests.
                   |Please give $name a warm welcome!""".stripMargin
              )
            )
          )

          ZIO.succeed(messages)
        }
      )

      // Run the server with stdio transport
      _ <- server.runStdio()
    } yield ()

  // Define a simple data class for calculator results
  case class CalculatorResult(
                               result: Double,
                               operation: String,
                               inputs: List[Double]
                             )