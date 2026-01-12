package com.tjclp.fastmcp.examples

import java.time.LocalDateTime
import java.util.UUID

import scala.collection.mutable

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Example MCP server demonstrating complex task management with nested case classes and
  * collections - showcasing the enhanced JacksonConverter capabilities.
  */
object TaskManagerServer extends ZIOAppDefault:

  // Domain models
  case class Task(
      id: String,
      title: String,
      description: String,
      status: TaskStatus,
      priority: Priority,
      tags: List[String],
      assignee: Option[String],
      createdAt: LocalDateTime,
      dueDate: Option[LocalDateTime]
  )

  enum TaskStatus:
    case Todo, InProgress, Done, Cancelled

  enum Priority:
    case Low, Medium, High, Critical

  case class TaskFilter(
      status: Option[TaskStatus] = None,
      priority: Option[Priority] = None,
      assignee: Option[String] = None,
      tags: List[String] = Nil
  )

  case class TaskUpdate(
      title: Option[String] = None,
      description: Option[String] = None,
      status: Option[TaskStatus] = None,
      priority: Option[Priority] = None,
      tags: Option[List[String]] = None,
      assignee: Option[String] = None,
      dueDate: Option[LocalDateTime] = None
  )

  case class TaskStats(
      total: Int,
      byStatus: Map[String, Int],
      byPriority: Map[String, Int],
      overdue: Int
  )

  // Custom JacksonConverter for LocalDateTime
  given JacksonConverter[LocalDateTime] = JacksonConverter.fromPartialFunction[LocalDateTime] {
    case str: String => LocalDateTime.parse(str)
  }

  // Derive converters for our domain models
  given JacksonConverter[Task] = DeriveJacksonConverter.derived[Task]
  given JacksonConverter[TaskFilter] = DeriveJacksonConverter.derived[TaskFilter]
  given JacksonConverter[TaskUpdate] = DeriveJacksonConverter.derived[TaskUpdate]
  given JacksonConverter[TaskStats] = DeriveJacksonConverter.derived[TaskStats]
  // Enums use the default converter

  // In-memory task storage
  private val tasks = mutable.Map[String, Task]()

  // Initialize with sample data
  tasks ++= Map(
    "1" -> Task(
      "1",
      "Implement user authentication",
      "Add OAuth2 authentication to the API",
      TaskStatus.InProgress,
      Priority.High,
      List("backend", "security"),
      Some("alice"),
      LocalDateTime.now().minusDays(3),
      Some(LocalDateTime.now().plusDays(2))
    ),
    "2" -> Task(
      "2",
      "Update documentation",
      "Update API documentation with new endpoints",
      TaskStatus.Todo,
      Priority.Medium,
      List("docs"),
      Some("bob"),
      LocalDateTime.now().minusDays(1),
      Some(LocalDateTime.now().plusDays(5))
    ),
    "3" -> Task(
      "3",
      "Fix production bug",
      "Users report crash on mobile app",
      TaskStatus.Done,
      Priority.Critical,
      List("bug", "mobile"),
      Some("alice"),
      LocalDateTime.now().minusDays(2),
      Some(LocalDateTime.now().minusDays(1))
    )
  )

  @Tool(
    name = Some("createTask"),
    description = Some("Create a new task with the specified details")
  )
  def createTask(
      @ToolParam("Task title") title: String,
      @ToolParam("Task description") description: String,
      @ToolParam("Priority level") priority: Priority,
      @ToolParam("Tags for categorization") tags: List[String],
      @ToolParam("Assignee username") assignee: Option[String] = None,
      @ToolParam("Due date in ISO format") dueDate: Option[LocalDateTime] = None
  ): Task =
    val task = Task(
      id = UUID.randomUUID().toString,
      title = title,
      description = description,
      status = TaskStatus.Todo,
      priority = priority,
      tags = tags,
      assignee = assignee,
      createdAt = LocalDateTime.now(),
      dueDate = dueDate
    )
    tasks += (task.id -> task)
    task

  @Tool(
    name = Some("updateTask"),
    description = Some("Update an existing task with new values")
  )
  def updateTask(
      @ToolParam("Task ID") taskId: String,
      @ToolParam("Fields to update") update: TaskUpdate
  ): String =
    tasks.get(taskId) match
      case None => s"Error: Task $taskId not found"
      case Some(task) =>
        val updated = task.copy(
          title = update.title.getOrElse(task.title),
          description = update.description.getOrElse(task.description),
          status = update.status.getOrElse(task.status),
          priority = update.priority.getOrElse(task.priority),
          tags = update.tags.getOrElse(task.tags),
          assignee = update.assignee.orElse(task.assignee),
          dueDate = update.dueDate.orElse(task.dueDate)
        )
        tasks += (taskId -> updated)
        s"Task $taskId updated successfully"

  @Tool(
    name = Some("listTasks"),
    description = Some("List tasks with optional filtering")
  )
  def listTasks(
      @ToolParam("Filter criteria") filter: TaskFilter
  ): List[Task] =
    tasks.values
      .filter { task =>
        filter.status.forall(_ == task.status) &&
        filter.priority.forall(_ == task.priority) &&
        filter.assignee.forall(a => task.assignee.contains(a)) &&
        (filter.tags.isEmpty || filter.tags.forall(task.tags.contains))
      }
      .toList
      .sortBy(_.createdAt)
      .reverse

  @Tool(
    name = Some("getTaskStats"),
    description = Some("Get statistics about all tasks")
  )
  def getTaskStats(): TaskStats =
    val allTasks = tasks.values.toList
    val now = LocalDateTime.now()

    TaskStats(
      total = allTasks.size,
      byStatus = allTasks.groupBy(_.status.toString).view.mapValues(_.size).toMap,
      byPriority = allTasks.groupBy(_.priority.toString).view.mapValues(_.size).toMap,
      overdue = allTasks.count(task =>
        task.status != TaskStatus.Done &&
          task.dueDate.exists(_.isBefore(now))
      )
    )

  @Tool(
    name = Some("searchTasks"),
    description = Some("Search tasks by text in title or description")
  )
  def searchTasks(
      @ToolParam("Search query") query: String
  ): List[Task] =
    val lowerQuery = query.toLowerCase
    tasks.values
      .filter { task =>
        task.title.toLowerCase.contains(lowerQuery) ||
        task.description.toLowerCase.contains(lowerQuery)
      }
      .toList
      .sortBy(_.createdAt)
      .reverse

  override def run: URIO[Any, ExitCode] =
    (for
      _ <- Console.printLine("Starting Task Manager MCP Server...")
      server <- ZIO.succeed(FastMcpServer("TaskManagerServer"))
      _ <- ZIO.attempt(server.scanAnnotations[TaskManagerServer.type])
      _ <- server.runStdio()
    yield ()).exitCode
