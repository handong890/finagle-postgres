package com.twitter.finagle.postgres.generic

import scala.collection.immutable.Queue

import com.twitter.finagle.postgres.{Param, PostgresClient, Row}
import com.twitter.util.Future
import scala.language.existentials

case class Query[T](parts: Seq[String], queryParams: Seq[QueryParam], cont: Row => T) {
  def run(client: PostgresClient): Future[Seq[T]] = {
    val (queryString, params) = impl
    client.prepareAndQuery[T](queryString, params: _*)(cont)
  }

  def exec(client: PostgresClient): Future[Int] = {
    val (queryString, params) = impl
    client.prepareAndExecute(queryString, params: _*)
  }

  def map[U](fn: T => U): Query[U] = copy(cont = cont andThen fn)

  def as[U](implicit rowDecoder: RowDecoder[U], columnNamer: ColumnNamer): Query[U] = {
    copy(cont = row => rowDecoder(row)(columnNamer))
  }

  private def impl: (String, Seq[Param[_]]) = {
    val (last, placeholders, params) = queryParams.foldLeft((1, Queue.empty[Seq[String]], Queue.empty[Param[_]])) {
      case ((start, placeholders, params), next) =>
        val nextPlaceholders = next.placeholders(start)
        val nextParams = next.params
        (start + nextParams.length, placeholders enqueue nextPlaceholders, params ++ nextParams)
    }

    val queryString = parts.zipAll(placeholders, "", Seq.empty).flatMap {
      case (part, ph) => Seq(part, ph.mkString(", "))
    }.mkString

    (queryString, params)
  }


}

object Query {
  implicit class RowQueryOps(val self: Query[Row]) extends AnyVal {
    def ++(that: Query[Row]): Query[Row] = Query[Row](
      parts = if(self.parts.length > self.queryParams.length)
        (self.parts.dropRight(1) :+ (self.parts.last + that.parts.head)) ++ that.parts.tail
      else
        self.parts ++ that.parts,
      queryParams = self.queryParams ++ that.queryParams,
      cont = self.cont
    )

    def ++(that: String): Query[Row] = Query[Row](
      parts = if(self.parts.length > self.queryParams.length)
          self.parts.dropRight(1) :+ (self.parts.last + that)
        else
          self.parts :+ that,
      queryParams = self.queryParams,
      cont = self.cont
    )
  }
}
