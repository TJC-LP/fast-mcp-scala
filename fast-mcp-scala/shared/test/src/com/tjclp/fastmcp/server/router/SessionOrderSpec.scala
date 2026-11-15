package com.tjclp.fastmcp
package server.router

import scala.concurrent.ExecutionContext

import org.scalatest.funsuite.AsyncFunSuite

/** The session stores sort sessions by a key that other requests change while the sort runs.
  * `Session.inOrderOf` reads the key once per item, so the sort always sees one consistent order
  * (Antithesis run 2231d6e2: `sortBy` on the live key failed inside the JDK's sort, and an
  * `initialize` at the session cap was answered 500).
  */
class SessionOrderSpec extends AsyncFunSuite:

  // Asynchronous like every suite in `shared/test/src`, which Scala.js requires of them.
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  test("the key is read once per item, and the result is in the order of those readings") {
    // A key that moves every time it is read, as a session's does under traffic. 200 items is
    // well past the 32 from which the JDK's sort checks its comparator.
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    val readings = scala.collection.mutable.Map.empty[Int, Long]
    val moving: Int => Long = item =>
      val n = reads.incrementAndGet()
      // Deterministic, and different at every read of the same item.
      val key = ((item.toLong * 7919L) + n.toLong * 104729L) % 1009L
      val _ = readings.getOrElseUpdate(item, key)
      key
    val items = (0 until 200).toList
    val sorted = Session.inOrderOf(items)(moving)
    assert(reads.get == items.size, "the key was read more than once per item")
    assert(sorted.sorted == items, "an item was lost or repeated")
    assert(sorted.map(readings) == sorted.map(readings).sorted, "not in the order of the readings")
  }
